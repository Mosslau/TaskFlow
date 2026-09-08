package com.taskflow.notification.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 配置：调用其他服务时透传身份头（X-User-Id / X-Role-Key）。
 *
 * <p>与 task-service 的差异：本服务的 Feign 调用大量发生在 MQ 消费线程
 * （无 HTTP 请求上下文），此时注入系统身份头，供 auth-user-service
 * 拦截器识别为内部调用（该服务拦截器只校验头存在，不做再鉴权）。</p>
 */
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor identityForwardingInterceptor() {
        return template -> {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                var request = attrs.getRequest();
                String userId = request.getHeader("X-User-Id");
                String roleKey = request.getHeader("X-Role-Key");
                if (userId != null) {
                    template.header("X-User-Id", userId);
                }
                if (roleKey != null) {
                    template.header("X-Role-Key", roleKey);
                }
                return;
            }
            // MQ 消费线程等无请求上下文场景：注入系统身份（内部调用）
            template.header("X-User-Id", "0");
            template.header("X-Role-Key", "system");
        };
    }
}
