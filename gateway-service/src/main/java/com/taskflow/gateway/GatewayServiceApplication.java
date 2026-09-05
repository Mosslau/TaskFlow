package com.taskflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关服务启动类。
 *
 * <p>职责（架构文档 3.1）：统一入口，路由转发、JWT 校验（M1 接入过滤器）、
 * 限流、CORS。自身无业务逻辑、无数据库。</p>
 *
 * <p>注意：本服务基于 WebFlux（spring-cloud-starter-gateway），
 * 严禁引入 spring-boot-starter-web（两者冲突会导致启动失败或行为异常）。</p>
 */
// @EnableDiscoveryClient：向 Nacos 注册本服务，并启用服务发现
// （lb://auth-user-service 这样的负载均衡路由依赖它从 Nacos 拉取实例列表）
@EnableDiscoveryClient
// @SpringBootApplication：三合一注解 =
//   @SpringBootConfiguration（本类是一个 @Configuration 配置类）
// + @EnableAutoConfiguration（按 classpath 依赖自动装配，如检测到 Gateway 就配好 WebFlux）
// + @ComponentScan（默认扫描本类所在包 com.taskflow.gateway 及其子包）
@SpringBootApplication
public class GatewayServiceApplication {

    /**
     * 服务入口。启动内嵌 Netty（WebFlux 默认容器），监听 8080→8000 端口，
     * 注册到 Nacos 后按 application.yml 的四条模块前缀路由转发请求。
     *
     * @param args 命令行参数（可覆盖配置，如 --server.port=8001）
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
