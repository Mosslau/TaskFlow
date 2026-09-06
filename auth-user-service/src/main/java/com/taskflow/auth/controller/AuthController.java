package com.taskflow.auth.controller;

import com.taskflow.auth.config.AuthContext;
import com.taskflow.auth.service.AuthService;
import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（接口文档 3.1）：登录 / 注销 / 刷新 / 改密。
 * 登录与刷新在拦截器白名单内，无需身份头。
 */
// @RestController：返回值直接序列化为 JSON（Result 信封）
// @RequestMapping：类级路径前缀 /auth/api/v1
@RestController
@RequestMapping("/auth/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录（接口 #1）。
     *
     * @param body {account, password}
     * @return {token, refreshToken, expiresIn, user:{id,name,account,roleKey,permissions,mustChangePassword}}
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return Result.ok(authService.login(body.get("account"), body.get("password")));
    }

    /**
     * 注销（接口 #2）：当前 JWT 进黑名单，刷新令牌删除。
     * 身份来自拦截器写入的 AuthContext；原始 token 从 Authorization 头取。
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(AuthContext.getUserId(), stripBearer(authorization));
        return Result.ok();
    }

    /**
     * 刷新令牌换新 JWT（接口 #3）。
     *
     * @param body {refreshToken}；userId 从身份头取（网关对过期 JWT 也透传 userId，见网关过滤器）
     * @return {token, expiresIn}
     */
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody Map<String, String> body,
                                               @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // 白名单接口：userId 依赖网关在"令牌过期但签名合法"时透传；直连调试时可手工带
        return Result.ok(authService.refresh(Long.valueOf(userId), body.get("refreshToken")));
    }

    /**
     * 修改本人密码（接口 #4）：改后旧令牌全部失效，下次登录不再强制改密。
     *
     * @param body {oldPassword, newPassword}
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody Map<String, String> body,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.changePassword(AuthContext.getUserId(),
                body.get("oldPassword"), body.get("newPassword"), stripBearer(authorization));
        return Result.ok();
    }

    /** 去掉 "Bearer " 前缀 */
    private static String stripBearer(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return authorization;
    }
}
