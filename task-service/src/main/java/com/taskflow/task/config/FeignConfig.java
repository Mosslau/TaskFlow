package com.taskflow.task.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 配置：调用其他服务时透传当前请求的身份头（X-User-Id / X-Role-Key），
 * 使 auth-user-service 的拦截器能正常识别调用方身份。
 */
@Configuration
public class FeignConfig {

    /**
     * 身份头透传拦截器。
     */
    @Bean
    public RequestInterceptor identityForwardingInterceptor() {
        return template -> {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return;
            }
            var request = attrs.getRequest();
            String userId = request.getHeader("X-User-Id");
            String roleKey = request.getHeader("X-Role-Key");
            if (userId != null) {
                template.header("X-User-Id", userId);
            }
            if (roleKey != null) {
                template.header("X-Role-Key", roleKey);
            }
        };
    }
}
