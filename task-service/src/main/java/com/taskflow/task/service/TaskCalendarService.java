package com.taskflow.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.task.client.UserClient;
import com.taskflow.task.entity.Task;
import com.taskflow.task.mapper.TaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日程聚合服务（接口 #39，PRD 4.2.2）。
 *
 * <p>按到期日（due_at）把当月任务（含子任务）聚合到每一天；
 * 应用与任务列表相同的可见性过滤（复用 TaskService.applyVisibility）；
 * 日期口径为东八区（与默认到期时间口径一致）。</p>
 */
@Service
public class TaskCalendarService {

    private static final Logger log = LoggerFactory.getLogger(TaskCalendarService.class);

    /** 日期口径：东八区（PRD 全程 +08:00） */
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    private final TaskMapper taskMapper;
    private final TaskService taskService;
    private final UserClient userClient;

    public TaskCalendarService(TaskMapper taskMapper, TaskService taskService, UserClient userClient) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.userClient = userClient;
    }

    /**
     * 月度日程聚合（接口 #39）。
     *
     * @param month 月份，格式 yyyy-MM（如 2026-09）
     * @return [{date: "2026-09-05", tasks: [{id, taskNo, title, status, priority, assigneeName}]}]
     */
    public List<Map<String, Object>> calendar(String month) {
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "month 格式须为 yyyy-MM");
        }
        OffsetDateTime start = yearMonth.atDay(1).atStartOfDay().atOffset(ZONE);
        OffsetDateTime end = yearMonth.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZONE);

        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<>();
        // ① 可见性过滤（与 TaskService.page() 同款规则，PRD 3.4）
        taskService.applyVisibility(qw);
        // ② 当月到期（含子任务：不加 parent_id 限制）
        qw.ge(Task::getDueAt, start).lt(Task::getDueAt, end)
                .orderByAsc(Task::getDueAt)
                .orderByAsc(Task::getId);

        List<Task> tasks = taskMapper.selectList(qw);
        Map<Long, String> names = userNames();

        // 按到期日（东八区）分组，LinkedHashMap 保持日期升序
        Map<LocalDate, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Task t : tasks) {
            LocalDate date = t.getDueAt().withOffsetSameInstant(ZONE).toLocalDate();
            grouped.computeIfAbsent(date, k -> new ArrayList<>()).add(toItem(t, names));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        grouped.forEach((date, items) -> result.add(Map.of(
                "date", date.toString(), "tasks", items)));
        return result;
    }

    /** 任务实体 → 日程项 */
    private Map<String, Object> toItem(Task t, Map<Long, String> names) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", t.getId());
        item.put("taskNo", t.getTaskNo());
        item.put("title", t.getTitle());
        item.put("status", t.getStatus());
        item.put("priority", t.getPriority());
        item.put("assigneeId", t.getAssigneeId());
        item.put("assigneeName", names.getOrDefault(t.getAssigneeId(), ""));
        return item;
    }

    /** Feign 反查用户姓名表（失败降级为空表，姓名留空） */
    @SuppressWarnings("unchecked")
    private Map<Long, String> userNames() {
        try {
            Map<String, Object> envelope = userClient.lookup(null, null);
            List<Map<String, Object>> users = (List<Map<String, Object>>) envelope.get("data");
            if (users == null) {
                return Map.of();
            }
            Map<Long, String> names = new HashMap<>();
            users.forEach(u -> names.put(Long.valueOf(String.valueOf(u.get("id"))),
                    String.valueOf(u.get("name"))));
            return names;
        } catch (Exception e) {
            log.warn("用户 lookup 调用失败，姓名解析降级为空: {}", e.getMessage());
            return Map.of();
        }
    }
}
