package com.taskflow.stats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.event.TaskEvents;
import com.taskflow.stats.config.RabbitConfig;
import com.taskflow.stats.mapper.ProcessedEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 任务域事件消费者（架构 3.3）：增量维护三张聚合表。
 *
 * <p>只消费 task.status.changed 做状态桶调整；task.approved / task.rejected
 * 的状态语义已被其后的 status.changed 覆盖（同一事务内先发语义事件、
 * 再发 status.changed），此处仅登记幂等，避免双重计数。</p>
 *
 * <p>失败策略（与 notification-service 一致）：解析失败或处理异常记日志后吞掉，
 * 投递可靠性靠生产端 outbox 重投；最终一致性由 POST /stats/api/v1/rebuild 兜底。</p>
 */
@Component
public class StatsEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(StatsEventConsumer.class);

    /** 消费幂等标识（processed_event.consumer） */
    private static final String CONSUMER = "stats";

    private final ProcessedEventMapper processedEventMapper;
    private final StatsAggregateService aggregateService;
    private final ObjectMapper objectMapper;

    public StatsEventConsumer(ProcessedEventMapper processedEventMapper,
                              StatsAggregateService aggregateService,
                              ObjectMapper objectMapper) {
        this.processedEventMapper = processedEventMapper;
        this.aggregateService = aggregateService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onEvent(String body) {
        TaskEvents.TaskEvent event;
        try {
            event = objectMapper.readValue(body, TaskEvents.TaskEvent.class);
        } catch (Exception e) {
            log.error("事件反序列化失败，丢弃: {}", body, e);
            return;
        }
        // 幂等去重：生产者 at-least-once，重复投递直接跳过（架构文档第 5 章）
        if (processedEventMapper.insertIgnore(event.eventId().toString(), CONSUMER) == 0) {
            log.debug("重复事件已跳过: {}", event.eventId());
            return;
        }
        log.info("消费事件: type={}, id={}", event.eventType(), event.eventId());
        try {
            dispatch(event);
        } catch (Exception e) {
            log.error("事件处理失败: type={}, id={}", event.eventType(), event.eventId(), e);
        }
    }

    /** 按事件类型分发到聚合逻辑 */
    private void dispatch(TaskEvents.TaskEvent event) {
        Map<String, Object> p = event.payload() == null ? Map.of() : event.payload();
        switch (event.eventType()) {
            case TaskEvents.TASK_ASSIGNED -> aggregateService.onAssigned(p);
            case TaskEvents.TASK_STATUS_CHANGED -> aggregateService.onStatusChanged(p);
            case TaskEvents.TASK_TRANSFERRED -> aggregateService.onTransferred(p);
            case TaskEvents.TASK_OVERDUE -> aggregateService.onOverdue(p);
            case TaskEvents.TASK_APPROVED, TaskEvents.TASK_REJECTED ->
                    log.debug("语义事件仅登记幂等（状态桶已由 task.status.changed 覆盖）: {}", event.eventType());
            default -> log.warn("未知事件类型，仅登记幂等: {}", event.eventType());
        }
    }
}
