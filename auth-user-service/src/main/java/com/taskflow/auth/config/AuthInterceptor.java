package com.taskflow.auth.config;

import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.auth.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;

/**
 * 身份与权限拦截器（架构文档 4.1：权限点校验在各服务本地完成）。
 *
 * <p>处理顺序：</p>
 * <ol>
 *   <li>白名单（登录/刷新/ping）直接放行</li>
 *   <li>读取网关透传的身份头 X-User-Id / X-Role-Key 写入 {@link AuthContext}，缺失返回 401</li>
 *   <li>方法标注了 {@link RequirePerm} 时，查「角色→权限点」缓存校验，无权限抛 3001（403）</li>
 * </ol>
 */
public class AuthInterceptor implements HandlerInterceptor {

    /** 无需身份的白名单路径（登录、刷新、健康检查） */
    private static final Set<String> WHITELIST = Set.of(
            "/auth/api/v1/login",
            "/auth/api/v1/refresh",
            "/auth/api/v1/ping"
    );

    /** 权限点查询（带 Redis 缓存） */
    private final PermissionService permissionService;

    public AuthInterceptor(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * Controller 方法执行前的身份与权限校验。
     *
     * @return true 放行；false 拦截（响应已由本方法写出或异常抛出）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 非 Controller 方法（静态资源等）直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        String path = request.getRequestURI();
        if (WHITELIST.contains(path)) {
            return true;
        }

        // ① 身份头解析（网关已验签，服务间内网信任透传头）
        String userIdHeader = request.getHeader("X-User-Id");
        String roleKey = request.getHeader("X-Role-Key");
        if (userIdHeader == null || roleKey == null) {
            // 没有身份头 = 未过网关或未登录，按未认证处理
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        AuthContext.set(Long.valueOf(userIdHeader), roleKey);

        // ② 权限点校验（方法标注了 @RequirePerm 才校验）
        RequirePerm requirePerm = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (requirePerm != null) {
            Set<String> perms = permissionService.getEnabledPermissions(roleKey);
            if (!perms.contains(requirePerm.value())) {
                // PRD 3.5.2：拒绝提示指明缺少的权限与当前角色
                throw new BizException(ErrorCode.PERMISSION_DENIED,
                        "缺少权限点 " + requirePerm.value(),
                        Map.of("required", requirePerm.value(), "roleKey", roleKey));
            }
        }
        return true;
    }

    /**
     * 请求完成后清理 ThreadLocal（无论成功失败，防线程池串号）。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }
}
