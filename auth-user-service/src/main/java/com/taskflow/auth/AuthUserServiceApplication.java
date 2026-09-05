package com.taskflow.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 认证与用户域服务启动类。
 *
 * <p>职责（架构文档 3.1）：登录认证（JWT 签发/刷新/黑名单）、
 * 用户/部门/角色/权限矩阵管理、审计日志、API Key 管理。
 * 独占 auth_user_db（唯一写者，架构 3.2 铁律 1）。</p>
 *
 * <p>启动时 Flyway 自动执行 db/migration 下的迁移脚本
 * （V1 建表 + V2 种子数据：3 角色、42 行权限矩阵、初始 admin）。</p>
 */
// @EnableDiscoveryClient：向 Nacos 注册本服务（auth-user-service），
// 供网关 lb://auth-user-service 路由与 task-service 的 Feign 调用发现
@EnableDiscoveryClient
// @SpringBootApplication：配置类 + 自动装配 + 组件扫描（com.taskflow.auth 包及子包）
@SpringBootApplication
public class AuthUserServiceApplication {

    /**
     * 服务入口。启动内嵌 Tomcat，监听 8081。
     *
     * @param args 命令行参数（可覆盖配置）
     */
    public static void main(String[] args) {
        SpringApplication.run(AuthUserServiceApplication.class, args);
    }
}
