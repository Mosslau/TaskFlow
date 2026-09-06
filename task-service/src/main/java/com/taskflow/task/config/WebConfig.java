package com.taskflow.task.config;

import com.taskflow.common.RedisUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 公共 Bean 注册 + 拦截器注册（task-service）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final StringRedisTemplate redis;

    public WebConfig(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Redis 工具 Bean */
    @Bean
    public RedisUtils redisUtils() {
        return new RedisUtils(redis);
    }

    /** 注册身份与权限拦截器（拦截 /task/api/v1/** 全部请求） */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(redisUtils()))
                .addPathPatterns("/task/api/v1/**");
    }
}
