package com.taskflow.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.task.config.RabbitConfig;
import com.taskflow.task.entity.EventOutbox;
import com.taskflow.task.mapper.EventOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 本地消息表投递线程（架构文档 4.2 / 第 5 章）。
 *
 * <p>事件在业务事务内写入 event_outbox（delivered=false）；本线程每 2 秒轮询
 * 未投递记录发到 RabbitMQ（routing key = 事件类型），成功标记已投递，
 * 失败下次轮询重投——保证 at-least-once，消费端按 eventId 幂等去重。</p>
 */
@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final EventOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelayService(EventOutboxMapper outboxMapper, RabbitTemplate rabbitTemplate) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 每 2 秒轮询未投递事件并投递。单实例部署无需分布式锁；
     * 多实例时重复投递由消费端 eventId 幂等兜底。
     */
    @Scheduled(fixedDelay = 2000)
    public void relay() {
        List<EventOutbox> pending = outboxMapper.selectList(
                new LambdaQueryWrapper<EventOutbox>()
                        .eq(EventOutbox::getDelivered, false)
                        .orderByAsc(EventOutbox::getCreatedAt)
                        .last("LIMIT 100"));
        for (EventOutbox event : pending) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitConfig.TASK_EXCHANGE, event.getEventType(), event.getPayload());
                event.setDelivered(true);
                event.setDeliveredAt(OffsetDateTime.now());
                outboxMapper.updateById(event);
            } catch (Exception e) {
                // 投递失败：下次轮询重投（at-least-once）
                log.warn("事件投递失败，下轮重试: eventId={}, type={}, err={}",
                        event.getEventId(), event.getEventType(), e.getMessage());
            }
        }
    }
}
