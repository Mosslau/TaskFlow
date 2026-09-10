package com.taskflow.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 链路追踪过滤器（M7 可观测性）：网关是整条链路的入口，负责生成/透传 X-Trace-Id。
 *
 * <p>处理流程：</p>
 * <ol>
 *   <li>优先取请求头 X-Trace-Id（前端/压测脚本/Nginx 已带则沿用，保证跨系统串联）；
 *       缺失则生成一个无横线 UUID</li>
 *   <li>把 X-Trace-Id 写回请求头透传下游，四个 servlet 服务的 TraceIdFilter 会将其放入 MDC</li>
 *   <li>响应头同样写回 X-Trace-Id，调用方可直接拿到本次链路标识</li>
 *   <li>请求结束打一条 INFO 日志（方法/路径/状态/耗时/traceId），供按 traceId 反查</li>
 * </ol>
 *
 * <p>顺序取 {@link Ordered#HIGHEST_PRECEDENCE}：先于 ApiKeyAuthFilter、JwtAuthFilter、
 * RateLimitFilter 执行，保证它们拦截/告警时也已在同一条链路上下文里。</p>
 *
 * <p>说明：网关为 WebFlux 响应式栈，日志统一在消息中显式携带 traceId，
 * 不使用 MDC（避免事件循环线程复用造成跨请求串号）；下游 servlet 服务才走 MDC。</p>
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    /** 链路标识请求/响应头（与各 servlet 服务 TraceIdFilter 约定一致） */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String incoming = request.getHeaders().getFirst(TRACE_ID_HEADER);
        // 上游已带则沿用（跨网关/前端联调场景），否则网关生成
        String traceId = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : incoming.trim();

        // 写回请求头 → 透传下游服务
        ServerHttpRequest mutated = request.mutate()
                .header(TRACE_ID_HEADER, traceId)
                .build();
        ServerHttpResponse response = exchange.getResponse();
        // 先占位：即使后续过滤器短路（401/403/429）也能带上链路标识
        response.getHeaders().set(TRACE_ID_HEADER, traceId);
        // 下游服务同样回写 X-Trace-Id，会被路由过滤器原样复制到网关响应造成重复；
        // 提交前统一覆盖（set 语义）为网关生成的唯一值，保证响应头只有一个 X-Trace-Id
        response.beforeCommit(() -> {
            response.getHeaders().set(TRACE_ID_HEADER, traceId);
            return Mono.empty();
        });

        String method = request.getMethod().name();
        String path = request.getPath().value();
        long start = System.currentTimeMillis();

        return chain.filter(exchange.mutate().request(mutated).build())
                // doFinally：正常返回/异常/取消都会执行，保证每条请求都有可 grep 的链路日志
                .doFinally(signal -> log.info("网关请求完成: method={}, path={}, status={}, cost={}ms, traceId={}",
                        method, path, response.getStatusCode(), System.currentTimeMillis() - start, traceId));
    }

    /**
     * 过滤器顺序：最先执行，先于其它三个全局过滤器（它们依次为 +1/+2/+3）。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
