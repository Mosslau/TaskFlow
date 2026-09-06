package com.taskflow.auth.config;

import com.taskflow.auth.service.PermissionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置：注册身份与权限拦截器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PermissionService permissionService;

    public WebConfig(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * 注册拦截器，拦截所有 /auth/api/v1/** 请求。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(permissionService))
                .addPathPatterns("/auth/api/v1/**");
    }
}
