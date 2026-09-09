package com.taskflow.notification.config;

import com.taskflow.common.event.TaskEvents;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RabbitMQ 消费侧拓扑（架构文档 3.3）+ 监听容器工厂。
 *
 * <p>声明与生产者同一个 topic exchange（声明是幂等的）+ 本服务专用持久化队列，
 * 绑定全部 8 类 task.* 事件路由键。task.commented 由 M4 评论产生、
 * task.due.soon / task.overdue 由 M5 定时扫描产生，先绑定好，事件到了即可消费。</p>
 *
 * <p>消费失败进死信（#2 通知侧）：主队列声明 dead-letter-exchange=task.events.dlx、
 * routing=notification.task.events.dlq；容器工厂设 defaultRequeueRejected(false)
 * （Spring Boot 默认容器异常时 requeue 会无限死循环），监听器抛出的异常即按死信参数
 * 落入 DLQ，供人工介入排障；毒消息（JSON 解析失败）在监听器内直接丢弃，不走死信。</p>
 */
@Configuration
public class RabbitConfig {

    /** 任务域事件 exchange（与 task-service 同名） */
    public static final String TASK_EXCHANGE = "task.events";

    /** 本服务消费队列 */
    public static final String QUEUE = "notification.task.events";

    /** 主队列死信 exchange（死信消息经它路由到 DLQ） */
    public static final String DLX = "task.events.dlx";

    /** 主队列死信队列（人工介入排障用） */
    public static final String DLQ = "notification.task.events.dlq";

    @Bean
    public TopicExchange taskExchange() {
        return new TopicExchange(TASK_EXCHANGE, true, false);
    }

    /** 死信 exchange（durable；与主 exchange 同名段区分，避免误绑业务路由键） */
    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DLX, true, false);
    }

    /** 主消费队列：异常消息（requeue=false 被拒）经 DLX 路由到 DLQ */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ)
                .build();
    }

    /** 死信队列：只承接主队列拒收的消息，人工排障后处理（消费端只记 ERROR 日志） */
    @Bean
    public Queue notificationDlq() {
        return QueueBuilder.durable(DLQ).build();
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

    /** 死信队列 → 死信 exchange 绑定（路由键即 x-dead-letter-routing-key） */
    @Bean
    public Declarables deadLetterBindings(TopicExchange deadLetterExchange, Queue notificationDlq) {
        return new Declarables(
                BindingBuilder.bind(notificationDlq).to(deadLetterExchange).with(DLQ));
    }

    /**
     * 自定义监听容器工厂（覆盖 Boot 默认）：消费异常默认不重投。
     *
     * <p>Spring Boot 默认 {@code defaultRequeueRejected=true}：监听器抛异常时消息会
     * 无限 requeue 死循环，必须置 false 才能让消息按主队列死信参数被拒后落入 DLQ。
     * 其余配置沿用 Boot 默认（经 SimpleRabbitListenerContainerFactoryConfigurer 装配），
     * 仅 @RabbitListener 显式指定本工厂。</p>
     */
    @Bean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
