package com.taskflow.stats.service;

import com.taskflow.stats.client.TaskClient;
import com.taskflow.stats.mapper.StatsAssigneeLoadMapper;
import com.taskflow.stats.mapper.StatsCompletionDailyMapper;
import com.taskflow.stats.mapper.StatsOverdueDailyMapper;
import com.taskflow.stats.mapper.StatsTaskDailyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 统计聚合服务：事件增量维护 + rebuild 全量重算（两条路径同一口径）。
 *
 * <p>口径（PRD 4.3 / 库表设计第 6 章）：</p>
 * <ul>
 *   <li>stats_task_daily 只计顶层任务（parentId 为空），统计日 = 任务创建日（东八区）；</li>
 *   <li>状态桶：new/doing/wait/done/close 五列随状态流转 ±1（创建即入 new 桶）；</li>
 *   <li>完成指标（stats_task_daily 的创建日队列口径）：进入 done/close 时 completed+1，
 *       完成时长 = 完成时刻（增量路径取事件处理时刻，回填路径取 updatedAt）- createdAt，
 *       按时 = 完成时刻 ≤ dueAt；</li>
 *   <li>紧急任务 P0（V2，PRD 4.3.2）：p0_unfinished = 当日创建且优先级=P0 且当前状态∈未完成。
 *       task.assigned(P0 新任务) +1；task.status.changed 未完成→终态 -1、终态→未完成保守 +1
 *       （优先级调整不产生 status.changed 事件，P0↔非P0 的存量变化由 rebuild 校准）；</li>
 *   <li>完成日聚合 stats_completion_daily（V2，趋势 completed 数据源）：与 stats_task_daily
 *       的创建日队列不同轴，按"进入终态的那一次"（from∈未完成 → to∈done/close）归入完成日，
 *       done→close 归档（from=done）不重复计；只计顶层任务；</li>
 *   <li>stats_assignee_load 计全部任务（含子任务）的未完成（new/doing/wait）数；
 *       转派负载调整带完成态守卫：载荷携带 status∈{done,close} 时不 ±（完成态任务转派不改负载）；</li>
 *   <li>stats_overdue_daily 由 task.overdue 事件按（快照日, 创建日）累积。</li>
 * </ul>
 */
@Service
public class StatsAggregateService {

    private static final Logger log = LoggerFactory.getLogger(StatsAggregateService.class);

    /** 项目统一时区（与 task-service 默认到期时间口径一致） */
    public static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    /** 未完成状态集合（人员负载口径） */
    public static final Set<String> UNFINISHED = Set.of("new", "doing", "wait");

    /** 完成状态集合（完成时长/按时率口径：已完成与已归档，PRD 4.3.2） */
    private static final Set<String> COMPLETED = Set.of("done", "close");

    /** rebuild 分页拉取大小 */
    private static final int REBUILD_PAGE_SIZE = 1000;

    private final StatsTaskDailyMapper dailyMapper;
    private final StatsOverdueDailyMapper overdueMapper;
    private final StatsAssigneeLoadMapper loadMapper;
    private final StatsCompletionDailyMapper completionMapper;
    private final TaskClient taskClient;

    public StatsAggregateService(StatsTaskDailyMapper dailyMapper,
                                 StatsOverdueDailyMapper overdueMapper,
                                 StatsAssigneeLoadMapper loadMapper,
                                 StatsCompletionDailyMapper completionMapper,
                                 TaskClient taskClient) {
        this.dailyMapper = dailyMapper;
        this.overdueMapper = overdueMapper;
        this.loadMapper = loadMapper;
        this.completionMapper = completionMapper;
        this.taskClient = taskClient;
    }

    // ==================== 事件增量 ====================

    /**
     * task.assigned：顶层任务按创建日行 total+1、new+1、pX+1；
     * 优先级=P0 的新任务（状态=new ∈ 未完成）p0_unfinished+1（PRD 4.3.2）；
     * 人员负载 +1（含子任务，与 rebuild 口径一致）。
     *
     * @param p 事件载荷
     */
    public void onAssigned(Map<String, Object> p) {
        if (isTopLevel(p)) {
            LocalDate date = dateOf(str(p.get("createdAt")), LocalDate.now(ZONE));
            String priority = str(p.get("priority"));
            long[] prio = priorityDelta(priority, 1);
            dailyMapper.upsertDelta(date, 1, 1, 0, 0, 0, 0,
                    prio[0], prio[1], prio[2], prio[3],
                    "P0".equals(priority) ? 1 : 0, 0, BigDecimal.ZERO, 0);
        }
        Long assigneeId = toLong(p.get("assigneeId"));
        if (assigneeId != null) {
            loadMapper.upsertDelta(assigneeId, 1);
        }
    }

    /**
     * task.status.changed：顶层任务按创建日行 from 桶 -1、to 桶 +1；
     * <ul>
     *   <li>to ∈ done/close 时完成指标 +1（stats_task_daily 创建日队列口径不变）；</li>
     *   <li>仅"进入终态的那一次"（from∈未完成 → to∈done/close）计入 stats_completion_daily
     *       （done→close 归档 from=done 不重复计；转派/reject 等不触发）；</li>
     *   <li>P0 任务离开未完成（→终态）p0_unfinished-1；终态回未完成保守 +1；</li>
     *   <li>人员负载按「未完成集合」进出 ±1（含子任务）。</li>
     * </ul>
     *
     * @param p 事件载荷
     */
    public void onStatusChanged(Map<String, Object> p) {
        String from = str(p.get("fromStatus"));
        String to = str(p.get("toStatus"));
        if (isTopLevel(p)) {
            LocalDate date = dateOf(str(p.get("createdAt")), LocalDate.now(ZONE));
            long[] fromBucket = statusBucket(from, -1);
            long[] toBucket = statusBucket(to, 1);
            // 进入终态判定：未完成集合 → 完成集合（避免 reject(doing) 与 done→close 归档误计）
            boolean enteredCompleted = UNFINISHED.contains(from) && COMPLETED.contains(to);
            long completed = 0;
            long ontime = 0;
            BigDecimal hours = BigDecimal.ZERO;
            OffsetDateTime now = OffsetDateTime.now(ZONE);
            if (enteredCompleted) {
                completed = 1;
                OffsetDateTime createdAt = parseTime(str(p.get("createdAt")));
                if (createdAt != null) {
                    hours = hoursBetween(createdAt, now);
                }
                OffsetDateTime dueAt = parseTime(str(p.get("dueAt")));
                if (dueAt != null && !now.isAfter(dueAt)) {
                    ontime = 1;
                }
            }
            // P0 未完成守卫：P0 任务离开未完成集合则 -1；终态回未完成保守 +1（当前状态机不会发生）
            long p0Unfinished = 0;
            if ("P0".equals(str(p.get("priority")))) {
                if (enteredCompleted) {
                    p0Unfinished = -1;
                } else if (COMPLETED.contains(from) && UNFINISHED.contains(to)) {
                    p0Unfinished = 1;
                }
            }
            dailyMapper.upsertDelta(date, 0,
                    fromBucket[0] + toBucket[0], fromBucket[1] + toBucket[1],
                    fromBucket[2] + toBucket[2], fromBucket[3] + toBucket[3],
                    fromBucket[4] + toBucket[4],
                    0, 0, 0, 0, p0Unfinished, completed, hours, ontime);
            if (enteredCompleted) {
                // 完成日口径聚合：complete_date = 事件处理当天（status.changed 载荷无完成时间）
                completionMapper.upsertDelta(LocalDate.now(ZONE), 1, hours, ontime);
            }
        }
        // 人员负载：状态进出未完成集合（转派的负载调整见 onTransferred）
        Long assigneeId = toLong(p.get("assigneeId"));
        boolean fromUnfinished = UNFINISHED.contains(from);
        boolean toUnfinished = UNFINISHED.contains(to);
        if (assigneeId != null && fromUnfinished != toUnfinished) {
            loadMapper.upsertDelta(assigneeId, toUnfinished ? 1 : -1);
        }
    }

    /**
     * task.transferred：人员负载旧处理人 -1、新处理人 +1（转派守卫 V2）。
     * 载荷携带 status 且 status ∈ {done, close}（完成态任务转派）时不调整负载——
     * 完成态任务本就不在未完成负载内，盲目 ± 会把他人负载减错；
     * 载荷缺 status（历史事件/旧契约）按旧逻辑 ±（此时假定任务未完成，漂移由 rebuild 兜底）。
     *
     * @param p 事件载荷
     */
    public void onTransferred(Map<String, Object> p) {
        Object status = p.get("status");
        if (status != null && COMPLETED.contains(status.toString())) {
            log.debug("完成态任务转派不改负载: taskNo={}, status={}",
                    p.get("taskNo"), status);
            return;
        }
        Long oldId = toLong(p.get("oldAssigneeId"));
        Long newId = toLong(p.get("newAssigneeId"));
        if (oldId != null && !oldId.equals(newId)) {
            loadMapper.upsertDelta(oldId, -1);
        }
        if (newId != null && !newId.equals(oldId)) {
            loadMapper.upsertDelta(newId, 1);
        }
    }

    /**
     * task.overdue：逾期日快照累积（snapshot_date=今天，task_created_date=载荷创建日）。
     *
     * @param p 事件载荷
     */
    public void onOverdue(Map<String, Object> p) {
        OffsetDateTime createdAt = parseTime(str(p.get("createdAt")));
        if (createdAt == null) {
            log.warn("task.overdue 载荷缺 createdAt，跳过快照累积: {}", p.get("taskNo"));
            return;
        }
        overdueMapper.upsertDelta(LocalDate.now(ZONE), createdAt.atZoneSameInstant(ZONE).toLocalDate(), 1);
    }

    // ==================== rebuild 全量重算 ====================

    /**
     * 回填：清空四张聚合表后，经 Feign 拉 task-service 全量任务重算。
     * 幂等表 processed_event 不清（历史事件不会重投，清掉反而失去防重）。
     *
     * @return 重算摘要 {tasks, days, completionDays, overdue}
     */
    @Transactional
    public Map<String, Object> rebuild() {
        dailyMapper.truncate();
        overdueMapper.truncate();
        loadMapper.truncate();
        completionMapper.truncate();

        // 内存聚合：日聚合（创建日队列）/ 完成日聚合 / 人员负载 / 逾期快照（快照日 = 今天）
        Map<LocalDate, DailyAgg> daily = new TreeMap<>();
        Map<LocalDate, CompletionAgg> completion = new TreeMap<>();
        Map<Long, Long> load = new HashMap<>();
        Map<LocalDate, Long> overdue = new TreeMap<>();
        OffsetDateTime now = OffsetDateTime.now(ZONE);

        int tasks = 0;
        int page = 1;
        while (true) {
            Map<String, Object> envelope = taskClient.list(page, REBUILD_PAGE_SIZE);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) envelope.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = data == null
                    ? List.of() : (List<Map<String, Object>>) data.get("list");
            if (list == null || list.isEmpty()) {
                break;
            }
            for (Map<String, Object> t : list) {
                accumulate(t, daily, completion, load, overdue, now);
                tasks++;
            }
            long total = toLong(data.get("total")) == null ? 0 : toLong(data.get("total"));
            if ((long) page * REBUILD_PAGE_SIZE >= total) {
                break;
            }
            page++;
        }

        daily.forEach((date, agg) -> dailyMapper.upsertDelta(date, agg.total,
                agg.newCount, agg.doing, agg.wait, agg.done, agg.close,
                agg.p0, agg.p1, agg.p2, agg.p3, agg.p0Unfinished, agg.completed, agg.hours, agg.ontime));
        completion.forEach((date, agg) -> completionMapper.upsertDelta(date, agg.completed, agg.hours, agg.ontime));
        load.forEach((assigneeId, count) -> loadMapper.upsertDelta(assigneeId, count));
        LocalDate today = LocalDate.now(ZONE);
        overdue.forEach((createdDate, count) -> overdueMapper.upsertDelta(today, createdDate, count));

        long overdueTotal = overdue.values().stream().mapToLong(Long::longValue).sum();
        log.info("rebuild 完成: tasks={}, days={}, completionDays={}, overdue={}",
                tasks, daily.size(), completion.size(), overdueTotal);
        return Map.of("tasks", tasks, "days", daily.size(), "completionDays", completion.size(),
                "overdue", overdueTotal);
    }

    /** 单任务计入内存聚合（rebuild 用；与事件增量同口径，完成时刻取 updatedAt） */
    private void accumulate(Map<String, Object> t, Map<LocalDate, DailyAgg> daily,
                            Map<LocalDate, CompletionAgg> completion,
                            Map<Long, Long> load, Map<LocalDate, Long> overdue, OffsetDateTime now) {
        String status = str(t.get("status"));
        OffsetDateTime createdAt = parseTime(str(t.get("createdAt")));
        OffsetDateTime updatedAt = parseTime(str(t.get("updatedAt")));
        OffsetDateTime dueAt = parseTime(str(t.get("dueAt")));
        LocalDate createdDate = createdAt == null
                ? LocalDate.now(ZONE) : createdAt.atZoneSameInstant(ZONE).toLocalDate();

        // 人员负载（含子任务）：未完成状态 +1
        Long assigneeId = toLong(t.get("assigneeId"));
        if (assigneeId != null && UNFINISHED.contains(status)) {
            load.merge(assigneeId, 1L, Long::sum);
        }
        // 逾期快照（含子任务）：未完成且已过期
        if (UNFINISHED.contains(status) && dueAt != null && dueAt.isBefore(now)) {
            overdue.merge(createdDate, 1L, Long::sum);
        }
        // 日聚合 / 完成日聚合：仅顶层任务
        if (t.get("parentId") != null) {
            return;
        }
        DailyAgg agg = daily.computeIfAbsent(createdDate, k -> new DailyAgg());
        agg.total++;
        switch (status) {
            case "new" -> agg.newCount++;
            case "doing" -> agg.doing++;
            case "wait" -> agg.wait++;
            case "done" -> agg.done++;
            case "close" -> agg.close++;
            default -> log.warn("未知任务状态: {}", status);
        }
        String priority = str(t.get("priority"));
        switch (priority) {
            case "P0" -> agg.p0++;
            case "P1" -> agg.p1++;
            case "P2" -> agg.p2++;
            case "P3" -> agg.p3++;
            default -> log.warn("未知优先级: {}", t.get("priority"));
        }
        // p0_unfinished（PRD 4.3.2）：P0 且当前未完成 → 创建日行 +1
        if ("P0".equals(priority) && UNFINISHED.contains(status)) {
            agg.p0Unfinished++;
        }
        // stats_task_daily 的创建日队列完成指标（语义保持）
        if (COMPLETED.contains(status)) {
            agg.completed++;
            if (createdAt != null && updatedAt != null) {
                agg.hours = agg.hours.add(hoursBetween(createdAt, updatedAt));
            }
            if (dueAt != null && updatedAt != null && !updatedAt.isAfter(dueAt)) {
                agg.ontime++;
            }
        }
        // stats_completion_daily（V2，完成日口径）：当前 done/close 的任务按其 updated_at
        // （近似完成时刻，存量无独立完成时间列）归入完成日
        if (COMPLETED.contains(status)) {
            LocalDate completeDate = updatedAt == null
                    ? createdDate : updatedAt.atZoneSameInstant(ZONE).toLocalDate();
            CompletionAgg c = completion.computeIfAbsent(completeDate, k -> new CompletionAgg());
            c.completed++;
            if (createdAt != null && updatedAt != null) {
                BigDecimal h = hoursBetween(createdAt, updatedAt);
                c.hours = c.hours.add(h);
                if (dueAt != null && !updatedAt.isAfter(dueAt)) {
                    c.ontime++;
                }
            }
        }
    }

    /** rebuild 内存聚合的行结构（创建日队列） */
    private static final class DailyAgg {
        long total;
        long newCount;
        long doing;
        long wait;
        long done;
        long close;
        long p0;
        long p1;
        long p2;
        long p3;
        long p0Unfinished;
        long completed;
        BigDecimal hours = BigDecimal.ZERO;
        long ontime;
    }

    /** rebuild 内存聚合的行结构（完成日队列，stats_completion_daily） */
    private static final class CompletionAgg {
        long completed;
        BigDecimal hours = BigDecimal.ZERO;
        long ontime;
    }

    // ==================== 工具 ====================

    /** 顶层任务判定：parentId 为空/null/空串 */
    static boolean isTopLevel(Map<String, Object> p) {
        Object parentId = p.get("parentId");
        return parentId == null || parentId.toString().isBlank();
    }

    /** 状态桶增量向量：[new, doing, wait, done, close]，未知状态全 0 */
    private static long[] statusBucket(String status, long delta) {
        long[] v = new long[5];
        switch (status) {
            case "new" -> v[0] = delta;
            case "doing" -> v[1] = delta;
            case "wait" -> v[2] = delta;
            case "done" -> v[3] = delta;
            case "close" -> v[4] = delta;
            default -> {
            }
        }
        return v;
    }

    /** 优先级增量向量：[p0, p1, p2, p3]，未知全 0 */
    private static long[] priorityDelta(String priority, long delta) {
        long[] v = new long[4];
        switch (priority) {
            case "P0" -> v[0] = delta;
            case "P1" -> v[1] = delta;
            case "P2" -> v[2] = delta;
            case "P3" -> v[3] = delta;
            default -> {
            }
        }
        return v;
    }

    /** 完成时长（小时，1 位小数） */
    private static BigDecimal hoursBetween(OffsetDateTime from, OffsetDateTime to) {
        double hours = Duration.between(from, to).toMillis() / 3_600_000.0;
        return BigDecimal.valueOf(hours).setScale(1, RoundingMode.HALF_UP);
    }

    /** ISO 时间文本解析；空串/非法返回 null */
    static OffsetDateTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 时间文本 → 东八区日期；解析失败用兜底日期 */
    static LocalDate dateOf(String s, LocalDate fallback) {
        OffsetDateTime t = parseTime(s);
        return t == null ? fallback : t.atZoneSameInstant(ZONE).toLocalDate();
    }

    static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = v.toString();
        return s.isBlank() ? null : Long.valueOf(s);
    }

    static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
