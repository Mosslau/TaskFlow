package com.taskflow.task.config;

import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.RedisUtils;
import com.taskflow.task.client.UserClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 身份与权限拦截器（task-service 版）。
 *
 * <p>与 auth-user-service 的差异：权限点不查本服务 DB，读共享 Redis 缓存
 * {@code auth:perms:{roleKey}}（架构 4.1）；矩阵变更时 auth-user-service 主动失效。</p>
 *
 * <p>M5 缺陷修复：缓存未命中（如 Redis 重启/被清）时回源 auth-user-service 拉取
 * 该角色权限点并持久回填，不再因缓存过期误判 3001。</p>
 */
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 白名单：健康检查 */
    private static final Set<String> WHITELIST = Set.of("/task/api/v1/ping");

    /** 探针白名单前缀（M7）：Actuator 的 health/prometheus/info 端点不鉴权，
     *  供 Prometheus 抓取与容器探针直连调用（网关侧同样放行 /actuator/**） */
    private static final String ACTUATOR_PREFIX = "/actuator/";

    private final RedisUtils redis;
    private final UserClient userClient;

    public AuthInterceptor(RedisUtils redis, UserClient userClient) {
        this.redis = redis;
        this.userClient = userClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // 白名单命中或 Actuator 探针端点：直接放行，不做身份校验（M7）
        String path = request.getRequestURI();
        if (WHITELIST.contains(path) || path.startsWith(ACTUATOR_PREFIX)) {
            return true;
        }

        // ① 身份头（网关验签后透传）
        String userIdHeader = request.getHeader("X-User-Id");
        String roleKey = request.getHeader("X-Role-Key");
        if (userIdHeader == null || roleKey == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        AuthContext.set(Long.valueOf(userIdHeader), roleKey);

        // ② 权限点校验（共享 Redis 缓存，未命中回源）
        RequirePerm requirePerm = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (requirePerm != null) {
            String cacheKey = "auth:perms:" + roleKey;
            String cached = redis.get(cacheKey);
            if (cached == null) {
                cached = fetchAndCachePerms(cacheKey, roleKey);
            }
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

    /**
     * 回源 auth-user-service 取该角色权限点并持久回填缓存。
     *
     * @return 逗号分隔的权限点；回源失败返回空串（按无权限处理，不阻断主链路判定）
     */
    private String fetchAndCachePerms(String cacheKey, String roleKey) {
        try {
            Object data = userClient.getRolePermissions(roleKey).get("data");
            if (data instanceof Map<?, ?> map && map.get("permissions") instanceof Collection<?> perms) {
                String joined = perms.stream().map(String::valueOf).collect(Collectors.joining(","));
                // 与 auth 侧一致：持久保存，矩阵变更由 auth 主动失效
                redis.set(cacheKey, joined);
                log.info("权限缓存回源回填: roleKey={}, perms={}", roleKey, perms.size());
                return joined;
            }
        } catch (Exception e) {
            log.warn("权限缓存回源失败（按无权限处理）: roleKey={}, err={}", roleKey, e.getMessage());
        }
        return "";
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }
}
