package com.taskflow.notification;

import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * notification-service 健康检查接口。
 *
 * <p>请求：{@code GET /notification/api/v1/ping}（直连 8083 或经网关 8000 转发）</p>
 */
// @RestController = @Controller + @ResponseBody：返回值直接序列化为 JSON
@RestController
public class PingController {

    /**
     * 健康检查。
     *
     * @return 标准信封，data 固定为 "pong"
     */
    // @GetMapping：HTTP GET /notification/api/v1/ping 映射到本方法
    @GetMapping("/notification/api/v1/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
