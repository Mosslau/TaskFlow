package com.taskflow.task;

import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * task-service 健康检查接口。
 *
 * <p>请求：{@code GET /task/api/v1/ping}（直连 8082 或经网关 8000 转发）</p>
 */
// @RestController = @Controller + @ResponseBody：返回值直接序列化为 JSON
@RestController
public class PingController {

    /**
     * 健康检查。
     *
     * @return 标准信封，data 固定为 "pong"
     */
    // @GetMapping：HTTP GET /task/api/v1/ping 映射到本方法
    @GetMapping("/task/api/v1/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
