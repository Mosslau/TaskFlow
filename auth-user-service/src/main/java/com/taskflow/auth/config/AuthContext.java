package com.taskflow.auth.config;

/**
 * 当前请求身份上下文（ThreadLocal）。
 *
 * <p>来源：网关 JWT 过滤器验签后，把身份透传到请求头
 * {@code X-User-Id} / {@code X-Role-Key}（架构文档 4.1），
 * 本服务的 {@link AuthInterceptor} 解析请求头写入这里，Service/Controller 直接取用。</p>
 *
 * <p>ThreadLocal 与请求线程绑定，请求结束必须 {@link #clear()}（拦截器 afterCompletion 保证），
 * 否则线程池复用会串号。</p>
 */
public final class AuthContext {

    private AuthContext() {
    }

    /** 当前请求用户 id */
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    /** 当前请求角色键（admin / taskAdmin / user） */
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

    /** 清理（请求结束调用，防线程池串号） */
    public static void clear() {
        USER_ID.remove();
        ROLE_KEY.remove();
    }
}
