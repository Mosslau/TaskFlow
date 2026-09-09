package com.taskflow.stats.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * auth-user-service 的 Feign 客户端：人员负载图的姓名解析与 admin 角色过滤。
 * 身份头由 FeignConfig 透传（MQ 消费线程注入系统身份）。
 */
@FeignClient(name = "auth-user-service", path = "/auth/api/v1")
public interface AuthUserClient {

    /**
     * 用户简明列表：人员负载图排除 admin 角色、解析姓名用。
     *
     * @param roleKey 角色键（可空，本服务固定传 null 拉全量）
     * @return 信封包裹的 [{id, name, account, roleKey, status, ...}]
     */
    @GetMapping("/users/lookup")
    Map<String, Object> lookup(@RequestParam(value = "roleKey", required = false) String roleKey);

    /**
     * 单角色权限点（权限缓存未命中时回源——M5 缺陷修复）。
     *
     * @param roleKey 角色键
     * @return 信封包裹的 {roleKey, permissions: [...]}
     */
    @GetMapping("/permissions/roles/{roleKey}")
    Map<String, Object> getRolePermissions(@org.springframework.web.bind.annotation.PathVariable("roleKey") String roleKey);
}
