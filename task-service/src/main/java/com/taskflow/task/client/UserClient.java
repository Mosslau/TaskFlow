package com.taskflow.task.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * auth-user-service 的 Feign 客户端（架构 3.2 铁律 2：实时校验走 Feign）。
 * 身份头由 FeignConfig 的拦截器透传。
 */
// @FeignClient：声明式 HTTP 客户端；name 为 Nacos 中的服务名
@FeignClient(name = "auth-user-service", path = "/auth/api/v1")
public interface UserClient {

    /**
     * 用户详情（校验处理人合法性用）。
     *
     * @param id 用户 id
     * @return 信封包裹的用户信息（含 roleKey、status）
     */
    @GetMapping("/users/{id}")
    Map<String, Object> getUser(@PathVariable("id") Long id);
}
