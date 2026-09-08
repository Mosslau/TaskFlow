package com.taskflow.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器注册（notification-service）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 注册身份拦截器（拦截 /notification/api/v1/** 全部请求） */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/notification/api/v1/**");
    }
}
