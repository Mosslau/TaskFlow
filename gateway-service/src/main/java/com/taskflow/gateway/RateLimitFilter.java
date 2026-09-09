package com.taskflow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * 网关级 Redis 限流过滤器（PRD 7.3 / M6.4，M6 缺陷 #5 补实现）。
 *
 * <p>运行在鉴权过滤器之后（order = HIGHEST_PRECEDENCE + 2），此时
 * JWT / API Key 请求已由上游过滤器注入 X-User-Id 身份头，故：</p>
 * <ul>
 *   <li>带 X-User-Id → 按用户维度计数（配额 {@code taskflow.ratelimit.user-per-minute}，默认 300/分）</li>
 *   <li>匿名（登录/刷新等白名单路径）→ 按来源 IP 计数（配额 {@code taskflow.ratelimit.ip-per-minute}，默认 600/分）</li>
 * </ul>
 *
 * <p>窗口：固定分钟桶（键含当前分钟），Redis INCR + EXPIRE，计数键
 * {@code tf:rl:{u|ip}:{identity}:{yyyyMMddHHmm}}，TTL 120s 保证跨分钟不丢计数、窗口外自动清理。
 * 超限返回 HTTP 429 + Result 信封 code=1003（details.retryAfterSeconds）。</p>
 *
 * <p>健康检查与 OPTIONS 预检不计费；登录/刷新等慢路径走 IP 桶且配额放大（600/分），
 * 不影响正常前端轮询（通知 60s 一次）。Redis 抖动时限流放行（fail-open，仅告警），
 * 避免限流组件故障拖垮全部流量——鉴权仍由上游过滤器 fail-closed 兜底。</p>
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 计数键前缀（与 auth:blacklist: 同风格，redis-cli 可直接排查） */
    private static final String KEY_PREFIX = "tf:rl:";

    /** 固定分钟窗口计数键 TTL：120s（多留 60s 覆盖跨分钟边界，防桶丢失） */
    private static final long WINDOW_TTL_SECONDS = 120;

    /** 各业务服务 ping 不计费 */
    private static final Set<String> PING_PATHS = Set.of(
            "/auth/api/v1/ping", "/task/api/v1/ping",
            "/notification/api/v1/ping", "/stats/api/v1/ping");

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 开关（默认开） */
    private final boolean enabled;
    /** 每用户每分钟配额（JWT/API Key 请求，按 X-User-Id） */
    private final long userPerMinute;
    /** 每 IP 每分钟配额（匿名请求，如登录/刷新） */
    private final long ipPerMinute;

    public RateLimitFilter(ReactiveStringRedisTemplate redis,
                           @Value("${taskflow.ratelimit.enabled:true}") boolean enabled,
                           @Value("${taskflow.ratelimit.user-per-minute:300}") long userPerMinute,
                           @Value("${taskflow.ratelimit.ip-per-minute:600}") long ipPerMinute) {
        this.redis = redis;
        this.enabled = enabled;
        this.userPerMinute = userPerMinute;
        this.ipPerMinute = ipPerMinute;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        // 预检与健康检查不计费（浏览器 CORS / 服务探活）
        if (HttpMethod.OPTIONS.equals(request.getMethod())
                || PING_PATHS.contains(request.getPath().value())) {
            return chain.filter(exchange);
        }

        String userId = request.getHeaders().getFirst("X-User-Id");
        boolean identified = StringUtils.hasText(userId);
        long quota = identified ? userPerMinute : ipPerMinute;
        String identity = identified ? "u:" + userId.trim() : "ip:" + clientIp(request);
        // 固定分钟桶：同一分钟累计，下一分钟自动换桶（旧桶 TTL 清理）
        String key = KEY_PREFIX + identity + ":" + (System.currentTimeMillis() / 60_000L);

        return redis.opsForValue().increment(key)
                .flatMap(count -> redis.expire(key, Duration.ofSeconds(WINDOW_TTL_SECONDS)).thenReturn(count))
                .flatMap(count -> count > quota
                        ? reject(exchange, quota)
                        : chain.filter(exchange))
                // 限流属防护性能力：Redis 异常时放行并告警，避免单点故障放大（鉴权仍 fail-closed）
                .onErrorResume(e -> {
                    log.warn("限流检查异常，本次放行: identity={}, err={}", identity, e.getMessage());
                    return chain.filter(exchange);
                });
    }

    /** 来源 IP：优先 X-Forwarded-For 首跳（nginx 等代理场景），否则取直连远端地址 */
    private static String clientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            String first = xff.split(",")[0].trim();
            if (StringUtils.hasText(first)) {
                return first;
            }
        }
        return request.getRemoteAddress() == null
                ? "unknown" : request.getRemoteAddress().getAddress().getHostAddress();
    }

    /**
     * 限流拒绝：HTTP 429 + Result 信封（code=1003，details.retryAfterSeconds）。
     */
    private Mono<Void> reject(ServerWebExchange exchange, long quota) {
        log.warn("请求触发网关限流: path={}, quotaPerMinute={}",
                exchange.getRequest().getPath().value(), quota);
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(ErrorCode.RATE_LIMITED.getHttpStatus()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsString(
                            Result.fail(ErrorCode.RATE_LIMITED,
                                    java.util.Map.of("retryAfterSeconds", retryAfterSeconds())))
                    .getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    /** 当前分钟剩余秒数（至少 1s），供客户端退避 */
    private static int retryAfterSeconds() {
        return Math.max(1, 60 - (int) (System.currentTimeMillis() % 60_000L / 1000L));
    }

    /** 过滤器顺序：鉴权（ApiKey/JWT）之后、路由之前；须能看到身份头 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
