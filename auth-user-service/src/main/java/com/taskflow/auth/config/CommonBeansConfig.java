package com.taskflow.auth.config;

import com.taskflow.common.JwtUtils;
import com.taskflow.common.RedisUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

/**
 * 公共 Bean 注册（common 模块只做纯工具，Bean 由各服务自行注册）。
 */
// @Configuration：声明本类为配置类，其中的 @Bean 方法产物进入 Spring 容器
@Configuration
public class CommonBeansConfig {

    /**
     * JWT 工具 Bean。
     *
     * @param secret 配置 taskflow.jwt.secret（生产走环境变量 JWT_SECRET）
     * @param ttl    配置 taskflow.jwt.ttl（ISO-8601 时长，默认 PT2H = 2 小时）
     * @return JwtUtils 实例
     */
    // @Bean：方法返回值注册为容器 Bean，按类型注入
    // @Value：从 application.yml / 环境变量读取配置注入参数
    @Bean
    public JwtUtils jwtUtils(@Value("${taskflow.jwt.secret}") String secret,
                             @Value("${taskflow.jwt.ttl:PT2H}") Duration ttl) {
        return new JwtUtils(secret, ttl);
    }

    /**
     * Redis 工具 Bean。
     *
     * @param redis Spring Boot 自动装配的 StringRedisTemplate（连接参数在 application.yml）
     * @return RedisUtils 实例
     */
    @Bean
    public RedisUtils redisUtils(StringRedisTemplate redis) {
        return new RedisUtils(redis);
    }

    /**
     * BCrypt 密码加密器（PRD 7.3.1：密码使用 BCrypt 存储）。
     *
     * @return BCryptPasswordEncoder（每次 hash 自动带随机盐）
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
