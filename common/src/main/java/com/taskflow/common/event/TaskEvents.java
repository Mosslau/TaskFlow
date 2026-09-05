package com.taskflow.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 领域事件常量与载荷定义（架构文档 3.3 节）。
 *
 * <p>事件流：task-service 的本地消息表（event_outbox）投递线程发布到 RabbitMQ
 * topic exchange，notification-service 与 stats-service 按事件类型消费。
 * 通知类事件保持语义化命名；stats 需要的变更统一收敛为 {@link #TASK_STATUS_CHANGED}。</p>
 *
 * <p>schema 演进规则：事件载荷字段只增不改，消费端向前兼容（架构文档 6 章）。</p>
 */
public final class TaskEvents {

    /** 工具类禁止实例化 */
    private TaskEvents() {
    }

    /** 任务创建并指派（含子任务指派）→ 通知处理人；stats 聚合 +1 */
    public static final String TASK_ASSIGNED = "task.assigned";

    /** 任务转派 → 通知新处理人；stats 人员负载调整 */
    public static final String TASK_TRANSFERRED = "task.transferred";

    /** 新评论 → 站内通知创建人与处理人（评论者除外，不发邮件） */
    public static final String TASK_COMMENTED = "task.commented";

    /** 提交验收 → 通知创建人 */
    public static final String TASK_ACCEPTANCE_SUBMITTED = "task.acceptance.submitted";

    /** 验收通过 → 通知处理人；stats 状态聚合调整 */
    public static final String TASK_APPROVED = "task.approved";

    /** 验收驳回（含原因）→ 通知处理人；stats 状态聚合调整 */
    public static final String TASK_REJECTED = "task.rejected";

    /** 一切状态/字段变更（受理/进度/归档等）→ stats 增量维护聚合表 */
    public static final String TASK_STATUS_CHANGED = "task.status.changed";

    /** 到期前 24h 提醒（定时扫描产生，每任务仅一次）→ 通知处理人 */
    public static final String TASK_DUE_SOON = "task.due.soon";

    /** 逾期提醒（每日 09:00 扫描产生）→ 通知处理人与创建人；stats 逾期日快照 */
    public static final String TASK_OVERDUE = "task.overdue";

    /**
     * 事件载荷。
     *
     * <p>{@code eventId} 是消费端幂等去重键：生产者经本地消息表 at-least-once 投递，
     * 可能重复，消费者按 eventId 查 processed_event 表去重（架构文档第 5 章）。</p>
     *
     * @param eventId    事件全局唯一 ID（消费幂等键）
     * @param eventType  事件类型（本类的 TASK_* 常量之一）
     * @param payload    业务载荷（任务 id、操作人、变更前后状态等，字段只增不改）
     * @param occurredAt 事件发生时间（UTC）
     */
    public record TaskEvent(
            UUID eventId,
            String eventType,
            Map<String, Object> payload,
            Instant occurredAt
    ) {
        /**
         * 工厂方法：生成新事件（eventId 随机 UUID，发生时间取当前）。
         *
         * @param eventType 事件类型常量
         * @param payload   业务载荷
         * @return 可投递的事件对象
         */
        public static TaskEvent of(String eventType, Map<String, Object> payload) {
            return new TaskEvent(UUID.randomUUID(), eventType, payload, Instant.now());
        }
    }
}
