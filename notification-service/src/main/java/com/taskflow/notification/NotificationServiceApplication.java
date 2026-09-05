package com.taskflow.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 通知域服务启动类。
 *
 * <p>职责（架构文档 3.1）：站内消息、邮件发送与重试（SMTP，指数退避 3 次）、
 * 消息过期清理（180 天）。独占 notification_db。</p>
 *
 * <p>入站：消费 RabbitMQ 中的 task.* 领域事件生成通知（M3 接入，
 * 消费幂等靠 processed_event 表去重）。</p>
 */
// @EnableDiscoveryClient：向 Nacos 注册本服务（notification-service）
@EnableDiscoveryClient
// @SpringBootApplication：配置类 + 自动装配 + 组件扫描（com.taskflow.notification 包及子包）
@SpringBootApplication
public class NotificationServiceApplication {

    /**
     * 服务入口。启动内嵌 Tomcat，监听 8083。
     *
     * @param args 命令行参数（可覆盖配置）
     */
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
