package com.taskflow.stats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 统计域服务启动类。
 *
 * <p>职责（架构文档 3.1）：消费任务领域事件做增量预聚合（日粒度聚合表），
 * 提供统计总览查询接口（GET /stats/api/v1/overview，P95 ≤ 2s，禁实时全表扫描）。
 * 独占 stats_db。</p>
 *
 * <p>入站：消费 RabbitMQ 的 task.status.changed 等事件（M5 接入，
 * 消费幂等靠 processed_event 表去重）。</p>
 */
// @EnableDiscoveryClient：向 Nacos 注册本服务（stats-service）
@EnableDiscoveryClient
// @SpringBootApplication：配置类 + 自动装配 + 组件扫描（com.taskflow.stats 包及子包）
@SpringBootApplication
public class StatsServiceApplication {

    /**
     * 服务入口。启动内嵌 Tomcat，监听 8084。
     *
     * @param args 命令行参数（可覆盖配置）
     */
    public static void main(String[] args) {
        SpringApplication.run(StatsServiceApplication.class, args);
    }
}
