package com.taskflow.notification.config;

import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 身份拦截器（notification-service 版）。
 *
 * <p>站内消息接口「登录即可」，无权限点要求（PRD 4.6.2），
 * 因此只校验网关透传的身份头存在即可，不查权限。</p>
 */
public class AuthInterceptor implements HandlerInterceptor {

    /** 白名单：健康检查 */
    private static final Set<String> WHITELIST = Set.of("/notification/api/v1/ping");

    /** 探针白名单前缀（M7）：Actuator 的 health/prometheus/info 端点不鉴权，
     *  供 Prometheus 抓取与容器探针直连调用（网关侧同样放行 /actuator/**） */
    private static final String ACTUATOR_PREFIX = "/actuator/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        // 白名单命中或 Actuator 探针端点：直接放行，不做身份校验（M7）
        String path = request.getRequestURI();
        if (WHITELIST.contains(path) || path.startsWith(ACTUATOR_PREFIX)) {
            return true;
        }
        // 身份头（网关验签后透传）
        String userIdHeader = request.getHeader("X-User-Id");
        String roleKey = request.getHeader("X-Role-Key");
        if (userIdHeader == null || roleKey == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        AuthContext.set(Long.valueOf(userIdHeader), roleKey);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }
}
