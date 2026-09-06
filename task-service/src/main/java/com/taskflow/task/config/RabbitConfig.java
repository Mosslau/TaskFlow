package com.taskflow.task.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RabbitMQ 拓扑与调度开关（架构文档 3.3）。
 */
// @EnableScheduling：启用 @Scheduled 定时任务（outbox 投递线程轮询）
@EnableScheduling
@Configuration
public class RabbitConfig {

    /** 任务域事件 exchange（topic），所有 task.* 事件发到这里 */
    public static final String TASK_EXCHANGE = "task.events";

    /**
     * 声明 topic exchange（生产者侧声明是幂等的，消费者侧也会声明）。
     */
    @Bean
    public TopicExchange taskExchange() {
        return new TopicExchange(TASK_EXCHANGE, true, false);
    }
}
