package com.taskflow.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 领域事件常量与载荷（架构文档 3.3）。
 * schema 字段只增不改，消费端向前兼容。
 */
public final class TaskEvents {

    private TaskEvents() {
    }

    public static final String TASK_ASSIGNED = "task.assigned";
    public static final String TASK_TRANSFERRED = "task.transferred";
    public static final String TASK_COMMENTED = "task.commented";
    public static final String TASK_ACCEPTANCE_SUBMITTED = "task.acceptance.submitted";
    public static final String TASK_APPROVED = "task.approved";
    public static final String TASK_REJECTED = "task.rejected";
    public static final String TASK_STATUS_CHANGED = "task.status.changed";
    public static final String TASK_DUE_SOON = "task.due.soon";
    public static final String TASK_OVERDUE = "task.overdue";

    /**
     * 事件载荷。eventId 为消费端幂等去重键。
     */
    public record TaskEvent(
            UUID eventId,
            String eventType,
            Map<String, Object> payload,
            Instant occurredAt
    ) {
        public static TaskEvent of(String eventType, Map<String, Object> payload) {
            return new TaskEvent(UUID.randomUUID(), eventType, payload, Instant.now());
        }
    }
}
