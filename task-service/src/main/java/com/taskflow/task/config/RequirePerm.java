package com.taskflow.task.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限点校验注解（与 auth-user-service 同构）：标在 Controller 方法上，
 * 拦截器校验当前角色是否拥有该权限点。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /**
     * 所需权限点键（PRD 3.2 的 14 个之一）。
     *
     * @return 权限点键
     */
    String value();
}
