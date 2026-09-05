package com.taskflow.gateway;

import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关健康检查接口。
 *
 * <p>用途：M0 验收网关自身存活与路由配置；负载均衡探活；
 * 前端联调时确认网关可达。网关的 ping 走自身端口（不转发），
 * 业务服务的 ping 经网关路由到对应服务，二者可区分"网关坏"还是"下游坏"。</p>
 */
// @RestController = @Controller + @ResponseBody：
// 本类所有方法的返回值直接序列化为 JSON 响应体（Result 信封），不走视图模板
@RestController
public class PingController {

    /**
     * 健康检查。
     *
     * <p>请求：{@code GET /gateway/api/v1/ping}（无前缀路由，直连网关自身）
     * 响应：{@code {"code":0,"message":"ok","data":"pong","details":null}}</p>
     *
     * @return 标准信封，data 固定为 "pong"
     */
    // @GetMapping：把 HTTP GET /gateway/api/v1/ping 映射到本方法
    @GetMapping("/gateway/api/v1/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
