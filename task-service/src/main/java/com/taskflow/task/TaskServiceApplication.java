package com.taskflow.task;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 任务域服务启动类。
 *
 * <p>职责（架构文档 3.1）：任务 CRUD、状态机、操作时间线、评论、附件、子任务、
 * 日程聚合、Excel 导入导出、全部定时扫描（到期/逾期/自动归档）。
 * 独占 task_db，是任务数据的唯一写者（架构 3.2 铁律 1）。</p>
 *
 * <p>跨服务协作：</p>
 * <ul>
 *   <li>出站 HTTP：Feign 调 auth-user-service 校验处理人合法性（架构 3.2 铁律 2）</li>
 *   <li>出站事件：领域事件经本地消息表发 RabbitMQ（架构 4.2）</li>
 *   <li>入站：Feign 被 stats/notification 查询（M5）</li>
 * </ul>
 */
// @EnableDiscoveryClient：向 Nacos 注册本服务（task-service）
@EnableDiscoveryClient
// @EnableFeignClients：扫描本包下的 @FeignClient 接口并生成代理实现，
// 使 task-service 能像调本地方法一样 HTTP 调用 auth-user-service
@EnableFeignClients
// @SpringBootApplication：配置类 + 自动装配 + 组件扫描（com.taskflow.task 包及子包）
@SpringBootApplication
public class TaskServiceApplication {

    /**
     * 服务入口。启动内嵌 Tomcat，监听 8082。
     *
     * @param args 命令行参数（可覆盖配置）
     */
    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
