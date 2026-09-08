package com.taskflow.notification.config;

import com.taskflow.common.event.TaskEvents;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RabbitMQ 消费侧拓扑（架构文档 3.3）。
 *
 * <p>声明与生产者同一个 topic exchange（声明是幂等的）+ 本服务专用持久化队列，
 * 绑定全部 8 类 task.* 事件路由键。task.commented 由 M4 评论产生、
 * task.due.soon / task.overdue 由 M5 定时扫描产生，先绑定好，事件到了即可消费。</p>
 */
@Configuration
public class RabbitConfig {

    /** 任务域事件 exchange（与 task-service 同名） */
    public static final String TASK_EXCHANGE = "task.events";

    /** 本服务消费队列 */
    public static final String QUEUE = "notification.task.events";

    @Bean
    public TopicExchange taskExchange() {
        return new TopicExchange(TASK_EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    /** 绑定 8 类事件路由键到本服务队列 */
    @Bean
    public Declarables notificationBindings(TopicExchange taskExchange, Queue notificationQueue) {
        List<String> keys = List.of(
                TaskEvents.TASK_ASSIGNED,
                TaskEvents.TASK_TRANSFERRED,
                TaskEvents.TASK_COMMENTED,
                TaskEvents.TASK_ACCEPTANCE_SUBMITTED,
                TaskEvents.TASK_APPROVED,
                TaskEvents.TASK_REJECTED,
                TaskEvents.TASK_DUE_SOON,
                TaskEvents.TASK_OVERDUE);
        return new Declarables(keys.stream()
                .map(key -> BindingBuilder.bind(notificationQueue).to(taskExchange).with(key))
                .toList());
    }
}
