package com.taskflow.notification.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * auth-user-service 的 Feign 客户端：消费事件时解析用户姓名与邮箱。
 * 身份头由 FeignConfig 透传（MQ 消费线程注入系统身份）。
 */
@FeignClient(name = "auth-user-service", path = "/auth/api/v1")
public interface AuthUserClient {

    /**
     * 用户详情（取 name / email / status）。
     *
     * @param id 用户 id
     * @return 信封包裹的 {id, name, account, email, roleKey, status, ...}
     */
    @GetMapping("/users/{id}")
    Map<String, Object> getUser(@PathVariable("id") Long id);

    /**
     * 用户简明列表：roleKey 过滤用于「邮件失败告警 admin」找全部系统管理员。
     *
     * @param roleKey 角色键（可空）
     * @return 信封包裹的 [{id, name, account, roleKey, status, ...}]
     */
    @GetMapping("/users/lookup")
    Map<String, Object> lookup(@RequestParam(value = "roleKey", required = false) String roleKey);
}
