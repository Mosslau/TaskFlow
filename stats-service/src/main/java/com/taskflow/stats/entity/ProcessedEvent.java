package com.taskflow.stats.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 消费幂等去重表（processed_event）：事件由生产者 at-least-once 投递，
 * 本表以 event_id 主键拦截重复消费（本服务独占 stats_db，与 notification 库互不影响）。
 */
@TableName("processed_event")
public class ProcessedEvent {

    /** 已消费的事件 ID */
    @TableId
    private String eventId;

    /** 消费者标识，固定 stats */
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
