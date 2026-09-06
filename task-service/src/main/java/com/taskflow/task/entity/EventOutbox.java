package com.taskflow.task.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.task.config.StringJsonbTypeHandler;

import java.time.OffsetDateTime;

/**
 * 本地消息表 event_outbox（架构文档 4.2：事件与业务同事务落库，
 * 后台线程轮询投递 RabbitMQ，保证 at-least-once）。
 */
@TableName(value = "event_outbox", autoResultMap = true)
public class EventOutbox {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件全局唯一 ID（消费端幂等去重键）；DB 为 UUID 列，实体用 String 映射
     * （event_id 只由 DB 默认 gen_random_uuid() 生成，读取经 StringTypeHandler 转字符串；
     * updateStrategy=NEVER：投递回写时不出现在 UPDATE SET 中，避免 String 写回 UUID 列报错） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String eventId;

    /** 事件类型（task.assigned / task.status.changed / ...） */
    private String eventType;

    /** 事件载荷 JSON 文本；DB 为 JSONB 列 */
    @TableField(typeHandler = StringJsonbTypeHandler.class)
    private String payload;

    /** 投递标记 */
    private Boolean delivered;

    /** 事件产生时间（UTC） */
    private OffsetDateTime createdAt;

    /** 投递成功时间（UTC） */
    private OffsetDateTime deliveredAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Boolean getDelivered() { return delivered; }
    public void setDelivered(Boolean delivered) { this.delivered = delivered; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(OffsetDateTime deliveredAt) { this.deliveredAt = deliveredAt; }
}
