package com.taskflow.auth.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限点校验注解（PRD 3.5.1：后端接口做独立鉴权，不依赖前端隐藏）。
 *
 * <p>标在 Controller 方法上，{@link AuthInterceptor} 在请求进入方法前
 * 校验当前角色是否拥有该权限点，无权限抛 403（3001）。</p>
 *
 * <p>用法：{@code @RequirePerm("manageUser")}</p>
 */
// @Target(METHOD)：只能标注在方法上
@Target(ElementType.METHOD)
// @Retention(RUNTIME)：运行时保留，拦截器才能通过反射读到
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /**
     * 所需权限点键（PRD 3.2 的 14 个之一），如 "manageUser" / "setPerm"。
     *
     * @return 权限点键
     */
    String value();
}
