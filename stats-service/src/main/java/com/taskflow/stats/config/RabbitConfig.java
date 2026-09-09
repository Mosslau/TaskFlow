package com.taskflow.stats.config;

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
 * 绑定 6 类 stats 关心的事件路由键。task.approved / task.rejected 只登记幂等不做聚合
 * （状态桶已由 task.status.changed 覆盖，避免双重计数），但绑定先留着（契约只增不改）。</p>
 */
@Configuration
public class RabbitConfig {

    /** 任务域事件 exchange（与 task-service 同名） */
    public static final String TASK_EXCHANGE = "task.events";

    /** 本服务消费队列 */
    public static final String QUEUE = "stats.task.events";

    @Bean
    public TopicExchange taskExchange() {
        return new TopicExchange(TASK_EXCHANGE, true, false);
    }

    @Bean
    public Queue statsQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    /** 绑定 6 类事件路由键到本服务队列 */
    @Bean
    public Declarables statsBindings(TopicExchange taskExchange, Queue statsQueue) {
        List<String> keys = List.of(
                TaskEvents.TASK_ASSIGNED,
                TaskEvents.TASK_TRANSFERRED,
                TaskEvents.TASK_STATUS_CHANGED,
                TaskEvents.TASK_APPROVED,
                TaskEvents.TASK_REJECTED,
                TaskEvents.TASK_OVERDUE);
        return new Declarables(keys.stream()
                .map(key -> BindingBuilder.bind(statsQueue).to(taskExchange).with(key))
                .toList());
    }
}
