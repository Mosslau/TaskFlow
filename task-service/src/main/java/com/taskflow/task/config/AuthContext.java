package com.taskflow.task.config;

/**
 * 当前请求身份上下文（ThreadLocal）。
 * 与 auth-user-service 的 AuthContext 同构：网关验签后透传 X-User-Id / X-Role-Key，
 * 拦截器解析写入；请求结束必须清理（防线程池串号）。
 * （微服务惯例：此类横切小件各服务自持，不跨服务共享代码，避免服务间代码耦合——架构文档第 6 章）
 */
public final class AuthContext {

    private AuthContext() {
    }

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE_KEY = new ThreadLocal<>();

    /**
     * 写入身份（拦截器调用）。
     *
     * @param userId  用户 id
     * @param roleKey 角色键
     */
    public static void set(Long userId, String roleKey) {
        USER_ID.set(userId);
        ROLE_KEY.set(roleKey);
    }

    /** @return 当前用户 id */
    public static Long getUserId() {
        return USER_ID.get();
    }

    /** @return 当前角色键 */
    public static String getRoleKey() {
        return ROLE_KEY.get();
    }

    /** 清理（请求结束调用） */
    public static void clear() {
        USER_ID.remove();
        ROLE_KEY.remove();
    }
}
