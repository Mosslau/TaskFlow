package com.taskflow.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.stats.client.AuthUserClient;
import com.taskflow.stats.entity.StatsAssigneeLoad;
import com.taskflow.stats.entity.StatsCompletionDaily;
import com.taskflow.stats.entity.StatsTaskDaily;
import com.taskflow.stats.mapper.StatsAssigneeLoadMapper;
import com.taskflow.stats.mapper.StatsCompletionDailyMapper;
import com.taskflow.stats.mapper.StatsOverdueDailyMapper;
import com.taskflow.stats.mapper.StatsTaskDailyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 统计总览查询（接口 #47，PRD 4.3）：全部指标从预聚合表汇总，禁实时全表扫描。
 *
 * <p>区间口径：以任务创建时间（stats_task_daily.stat_date）落入区间为准，只计顶层任务；
 * 紧急任务 P0（KPI.p0）取 p0_unfinished（区间创建日 ∩ 优先级 P0 ∩ 当前未完成，PRD 4.3.2）；
 * 趋势 completed（V2）改读 stats_completion_daily 按"完成日落在同一日历区间"聚合——
 * completed 与 created 的区间含义不同轴：created 按创建日过滤、completed 按完成日过滤
 * （PRD 趋势图常规语义，代码注释已写明）；
 * 逾期取 stats_overdue_daily 最新快照日（误差 ≤ 24h，决策基线 #7）；
 * 趋势区间跨度 ≤ 70 天按日聚合，否则按周聚合（date 为周一）。</p>
 */
@Service
public class OverviewService {

    private static final Logger log = LoggerFactory.getLogger(OverviewService.class);

    /** 趋势按日聚合的区间跨度上限（天），超过则按周聚合（PRD 4.3.3） */
    private static final long DAILY_TREND_MAX_DAYS = 70;

    private final StatsTaskDailyMapper dailyMapper;
    private final StatsOverdueDailyMapper overdueMapper;
    private final StatsAssigneeLoadMapper loadMapper;
    private final StatsCompletionDailyMapper completionMapper;
    private final AuthUserClient authUserClient;

    public OverviewService(StatsTaskDailyMapper dailyMapper,
                           StatsOverdueDailyMapper overdueMapper,
                           StatsAssigneeLoadMapper loadMapper,
                           StatsCompletionDailyMapper completionMapper,
                           AuthUserClient authUserClient) {
        this.dailyMapper = dailyMapper;
        this.overdueMapper = overdueMapper;
        this.loadMapper = loadMapper;
        this.completionMapper = completionMapper;
        this.authUserClient = authUserClient;
    }

    /**
     * 统计总览。
     *
     * @param range all / month / week / custom
     * @param start 自定义起始日（YYYY-MM-DD，含当日）
     * @param end   自定义截止日（含当日）
     * @return 接口 #47 的 data 结构
     */
    public Map<String, Object> overview(String range, String start, String end) {
        LocalDate today = LocalDate.now(StatsAggregateService.ZONE);
        LocalDate rangeStart;
        LocalDate rangeEnd;
        String labelPrefix;
        switch (range == null ? "all" : range) {
            case "all" -> {
                rangeStart = null;
                rangeEnd = null;
                labelPrefix = "历史累计";
            }
            case "month" -> {
                rangeStart = today.withDayOfMonth(1);
                rangeEnd = today;
                labelPrefix = "本月";
            }
            case "week" -> {
                rangeStart = today.with(DayOfWeek.MONDAY);
                rangeEnd = rangeStart.plusDays(6);
                labelPrefix = "本周";
            }
            case "custom" -> {
                if (start == null || end == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "自定义区间需携带 start 与 end", null);
                }
                try {
                    rangeStart = LocalDate.parse(start);
                    rangeEnd = LocalDate.parse(end);
                } catch (Exception e) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "start/end 日期格式应为 YYYY-MM-DD", null);
                }
                if (rangeEnd.isBefore(rangeStart)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "end 不能早于 start", null);
                }
                labelPrefix = "自定义区间";
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID,
                    "range 仅支持 all/month/week/custom", null);
        }

        // 展示用下界：历史累计取最早统计日（无数据回退今天）
        LocalDate displayStart = rangeStart != null ? rangeStart : dailyMapper.minStatDate();
        if (displayStart == null) {
            displayStart = today;
        }
        LocalDate displayEnd = rangeEnd != null ? rangeEnd : today;
        String rangeLabel = labelPrefix + "（" + displayStart + " 至 " + displayEnd + "）";

        // KPI 与分布：日聚合区间汇总
        Map<String, Object> sum = dailyMapper.sumRange(rangeStart, rangeEnd);
        long total = num(sum.get("total"));
        long todo = num(sum.get("todo"));
        long doing = num(sum.get("doing"));
        long waiting = num(sum.get("waiting"));
        long done = num(sum.get("completed"));
        // 优先级分布口径（区间创建且 P0，不变）
        long p0Created = num(sum.get("p0"));
        // 紧急任务 KPI.p0（V2 口径，PRD 4.3.2）：区间创建且优先级 P0 且当前未完成
        long p0 = num(sum.get("p0_unfinished"));
        long completed = num(sum.get("completed"));
        BigDecimal hours = sum.get("hours") instanceof BigDecimal b ? b : BigDecimal.ZERO;
        long ontime = num(sum.get("ontime"));

        // 逾期：最新快照日 + 创建日区间过滤
        LocalDate snapshot = overdueMapper.maxSnapshotDate();
        long overdue = snapshot == null ? 0 : overdueMapper.sumOverdue(snapshot, rangeStart, rangeEnd);

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total", total);
        kpi.put("done", done);
        kpi.put("unfinished", todo + doing + waiting);
        kpi.put("todo", todo);
        kpi.put("doing", doing);
        kpi.put("waiting", waiting);
        kpi.put("overdue", overdue);
        // KPI.p0 = Σ(区间创建日各行的 p0_unfinished)；p0Percent 保留 1 位小数
        kpi.put("p0", p0);
        kpi.put("p0Percent", total == 0 ? 0
                : BigDecimal.valueOf(p0 * 100.0 / total).setScale(1, RoundingMode.HALF_UP).doubleValue());
        kpi.put("avgCompleteHours", completed == 0 ? 0
                : hours.divide(BigDecimal.valueOf(completed), 1, RoundingMode.HALF_UP).doubleValue());
        kpi.put("onTimeRate", completed == 0 ? 0
                : Math.round(ontime * 100.0 / completed));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rangeLabel", rangeLabel);
        data.put("kpi", kpi);
        data.put("trend", trend(rangeStart, rangeEnd, displayStart, displayEnd));
        data.put("statusDistribution", List.of(
                Map.of("status", "new", "count", todo),
                Map.of("status", "doing", "count", doing),
                Map.of("status", "wait", "count", waiting),
                Map.of("status", "done", "count", num(sum.get("done"))),
                Map.of("status", "close", "count", num(sum.get("close")))));
        data.put("priorityDistribution", List.of(
                Map.of("priority", "P0", "count", p0Created),
                Map.of("priority", "P1", "count", num(sum.get("p1"))),
                Map.of("priority", "P2", "count", num(sum.get("p2"))),
                Map.of("priority", "P3", "count", num(sum.get("p3")))));
        data.put("assigneeLoad", assigneeLoad());
        return data;
    }

    /**
     * 任务趋势：区间跨度 ≤ 70 天按日聚合（缺日补 0），否则按周聚合（date 为周一）。
     *
     * <p>口径（V2）：created 沿用创建日口径——读 stats_task_daily.total_count，按 stat_date
     * 落入 [rangeStart, rangeEnd] 过滤（创建时间轴）；completed 改读 stats_completion_daily.completed，
     * 按 complete_date 落在同一日历区间过滤（完成时间轴）。completed 与 created 的"区间"含义不同：
     * created 是"区间内创建的任务"，completed 是"完成日落在区间内的任务"——两者是不同的任务集合，
     * PRD 趋势图常规语义（代码注释与验收报告已写明该口径差异）。</p>
     */
    private List<Map<String, Object>> trend(LocalDate rangeStart, LocalDate rangeEnd,
                                            LocalDate displayStart, LocalDate displayEnd) {
        // created：创建日（stats_task_daily.stat_date）区间过滤
        QueryWrapper<StatsTaskDaily> qw = new QueryWrapper<>();
        qw.ge(rangeStart != null, "stat_date", rangeStart)
                .le(rangeEnd != null, "stat_date", rangeEnd);
        Map<LocalDate, Long> createdByDate = new HashMap<>();
        for (StatsTaskDaily row : dailyMapper.selectList(qw)) {
            createdByDate.put(row.getStatDate(), row.getTotalCount());
        }
        // completed：完成日（stats_completion_daily.complete_date）区间过滤
        QueryWrapper<StatsCompletionDaily> cqw = new QueryWrapper<>();
        cqw.ge(rangeStart != null, "complete_date", rangeStart)
                .le(rangeEnd != null, "complete_date", rangeEnd);
        Map<LocalDate, Long> completedByDate = new HashMap<>();
        for (StatsCompletionDaily row : completionMapper.selectList(cqw)) {
            completedByDate.put(row.getCompleteDate(), row.getCompleted());
        }

        long days = ChronoUnit.DAYS.between(displayStart, displayEnd) + 1;
        List<Map<String, Object>> trend = new ArrayList<>();
        if (days <= DAILY_TREND_MAX_DAYS) {
            for (LocalDate d = displayStart; !d.isAfter(displayEnd); d = d.plusDays(1)) {
                trend.add(Map.of("date", d.toString(),
                        "created", createdByDate.getOrDefault(d, 0L),
                        "completed", completedByDate.getOrDefault(d, 0L)));
            }
            return trend;
        }
        // 按周聚合：date 为周期起始日（周一），首周从区间起点对齐到周一
        TreeMap<LocalDate, long[]> byWeek = new TreeMap<>();
        createdByDate.forEach((d, v) -> {
            LocalDate weekStart = d.with(DayOfWeek.MONDAY);
            byWeek.computeIfAbsent(weekStart, k -> new long[2])[0] += v;
        });
        completedByDate.forEach((d, v) -> {
            LocalDate weekStart = d.with(DayOfWeek.MONDAY);
            byWeek.computeIfAbsent(weekStart, k -> new long[2])[1] += v;
        });
        LocalDate firstWeek = displayStart.with(DayOfWeek.MONDAY);
        for (LocalDate w = firstWeek; !w.isAfter(displayEnd); w = w.plusWeeks(1)) {
            long[] v = byWeek.getOrDefault(w, new long[2]);
            trend.add(Map.of("date", w.toString(), "created", v[0], "completed", v[1]));
        }
        return trend;
    }

    /**
     * 人员负载：未完成数降序；排除 admin 角色用户，姓名经 auth-user-service lookup 解析。
     * 用户服务不可用时降级为「用户#id」（不过滤，宁多勿缺）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> assigneeLoad() {
        Map<Long, Map<String, Object>> users = new HashMap<>();
        boolean lookupOk = true;
        try {
            Map<String, Object> envelope = authUserClient.lookup(null);
            List<Map<String, Object>> list = (List<Map<String, Object>>) envelope.get("data");
            if (list != null) {
                for (Map<String, Object> u : list) {
                    users.put(StatsAggregateService.toLong(u.get("id")), u);
                }
            }
        } catch (Exception e) {
            lookupOk = false;
            log.warn("用户 lookup 失败，人员负载降级为不过滤 admin: {}", e.getMessage());
        }
        boolean finalLookupOk = lookupOk;
        return loadMapper.selectList(null).stream()
                .filter(row -> {
                    if (!finalLookupOk) {
                        return true;
                    }
                    Map<String, Object> u = users.get(row.getAssigneeId());
                    return u == null || !"admin".equals(u.get("roleKey"));
                })
                .sorted((a, b) -> Long.compare(b.getUnfinishedCount(), a.getUnfinishedCount()))
                .map(row -> {
                    Map<String, Object> u = users.get(row.getAssigneeId());
                    String name = u == null ? "用户#" + row.getAssigneeId() : String.valueOf(u.get("name"));
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("assigneeId", row.getAssigneeId());
                    item.put("assigneeName", name);
                    item.put("unfinished", row.getUnfinishedCount());
                    return item;
                })
                .collect(Collectors.toList());
    }

    /** SUM 结果转 long（PG 的 SUM(bigint) 经驱动可能返回 Long/BigInteger/BigDecimal） */
    private static long num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(v.toString());
    }
}
