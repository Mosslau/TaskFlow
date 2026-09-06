package com.taskflow.task.config;

import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.RedisUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;

/**
 * 身份与权限拦截器（task-service 版）。
 *
 * <p>与 auth-user-service 的差异：权限点不查本服务 DB，直接读 auth-user-service
 * 写入的共享 Redis 缓存 {@code auth:perms:{roleKey}}（架构 4.1：权限点校验在各服务
 * 本地完成，映射从 Redis 读；矩阵变更时 auth-user-service 主动失效缓存）。</p>
 */
public class AuthInterceptor implements HandlerInterceptor {

    /** 白名单：健康检查 */
    private static final Set<String> WHITELIST = Set.of("/task/api/v1/ping");

    private final RedisUtils redis;

    public AuthInterceptor(RedisUtils redis) {
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (WHITELIST.contains(request.getRequestURI())) {
            return true;
        }

        // ① 身份头（网关验签后透传）
        String userIdHeader = request.getHeader("X-User-Id");
        String roleKey = request.getHeader("X-Role-Key");
        if (userIdHeader == null || roleKey == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        AuthContext.set(Long.valueOf(userIdHeader), roleKey);

        // ② 权限点校验（读共享 Redis 缓存，不查库）
        RequirePerm requirePerm = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (requirePerm != null) {
            String cached = redis.get("auth:perms:" + roleKey);
            Set<String> perms = (cached == null || cached.isEmpty())
                    ? Set.of() : Set.of(cached.split(","));
            if (!perms.contains(requirePerm.value())) {
                throw new BizException(ErrorCode.PERMISSION_DENIED,
                        "缺少权限点 " + requirePerm.value(),
                        Map.of("required", requirePerm.value(), "roleKey", roleKey));
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }
}
