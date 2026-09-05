package com.taskflow.auth;

import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * auth-user-service 健康检查接口。
 *
 * <p>请求：{@code GET /auth/api/v1/ping}（可直连 8081，也可经网关 8000 转发）
 * 响应：标准信封 {@code {"code":0,"message":"ok","data":"pong","details":null}}</p>
 */
// @RestController = @Controller + @ResponseBody：返回值直接序列化为 JSON
@RestController
public class PingController {

    /**
     * 健康检查。
     *
     * @return 标准信封，data 固定为 "pong"
     */
    // @GetMapping：把 HTTP GET /auth/api/v1/ping 映射到本方法
    // 路径前缀 /auth/api/v1 与网关路由（接口设计文档 1.1）一致
    @GetMapping("/auth/api/v1/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
