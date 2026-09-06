package com.taskflow.auth.controller;

import com.taskflow.auth.config.RequirePerm;
import com.taskflow.auth.service.ApiKeyService;
import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API Key 管理接口（接口文档 2.1，4 个接口，权限点 manageUser）。
 * Key 明文仅在签发与重生成时返回一次。
 */
@RestController
@RequestMapping("/auth/api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * 签发 API Key。
     *
     * @param body {userId（taskAdmin 服务账号）, name（调用方名称）}
     * @return {id, apiKey（明文，仅此一次）, createdAt}
     */
    @PostMapping
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return Result.ok(apiKeyService.create(
                (String) body.get("name"),
                Long.valueOf(String.valueOf(body.get("userId")))));
    }

    /** Key 列表（不含明文与哈希，只回前缀） */
    @GetMapping
    @RequirePerm("manageUser")
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(apiKeyService.list());
    }

    /** 停用 / 启用 */
    @PutMapping("/{id}/status")
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> changeStatus(@PathVariable Long id,
                                                    @RequestBody Map<String, String> body) {
        apiKeyService.changeStatus(id, body.get("status"));
        return Result.ok(Map.of("id", id, "status", body.get("status")));
    }

    /** 重新生成（旧 Key 立即失效，新明文仅此一次） */
    @PostMapping("/{id}/regenerate")
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> regenerate(@PathVariable Long id) {
        return Result.ok(apiKeyService.regenerate(id));
    }
}
