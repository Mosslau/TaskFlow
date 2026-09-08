package com.taskflow.notification.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 消费幂等去重表（processed_event）：事件与业务同事务由生产者落库后
 * at-least-once 投递，本表以 event_id 主键拦截重复消费。
 *
 * <p>注意：当前主键仅 event_id，一个事件只允许一个消费者登记；
 * M5 stats-service 接入消费时需把主键改为 (event_id, consumer) 复合键。</p>
 */
@TableName("processed_event")
public class ProcessedEvent {

    /** 已消费的事件 ID */
    @TableId
    private String eventId;

    /** 消费者标识，如 notification */
    private String consumer;

    /** 消费时间（UTC） */
    private OffsetDateTime processedAt;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getConsumer() {
        return consumer;
    }

    public void setConsumer(String consumer) {
        this.consumer = consumer;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(OffsetDateTime processedAt) {
        this.processedAt = processedAt;
    }
}
