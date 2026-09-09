package com.taskflow.task.config;

import com.taskflow.common.RedisUtils;
import com.taskflow.task.client.UserClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 公共 Bean 注册 + 拦截器注册（task-service）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final StringRedisTemplate redis;
    private final UserClient userClient;

    // @Lazy：WebConfig ↔ FeignClient 存在初始化循环（WebMvcConfigurer 早期初始化），
    // 延迟代理到首个请求再解析真实客户端，打破循环。
    public WebConfig(StringRedisTemplate redis, @Lazy UserClient userClient) {
        this.redis = redis;
        this.userClient = userClient;
    }

    /** Redis 工具 Bean */
    @Bean
    public RedisUtils redisUtils() {
        return new RedisUtils(redis);
    }

    /** 注册身份与权限拦截器（拦截 /task/api/v1/** 全部请求；回源需 UserClient） */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(redisUtils(), userClient))
                .addPathPatterns("/task/api/v1/**");
    }
}
