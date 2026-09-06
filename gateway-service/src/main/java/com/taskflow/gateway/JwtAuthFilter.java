package com.taskflow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.JwtUtils;
import com.taskflow.common.Result;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * JWT 全局鉴权过滤器（架构文档 4.1：验签 → 黑名单 → 身份头透传）。
 *
 * <p>处理流程：</p>
 * <ol>
 *   <li>白名单（登录/刷新/ping）直接放行</li>
 *   <li>取 Authorization: Bearer 令牌，缺失返回 401</li>
 *   <li>验签（含过期校验），失败 401；<b>过期但签名合法的请求且路径为 refresh 时
 *       仍透传 userId</b>（刷新场景需要知道是谁在刷新）</li>
 *   <li>查 Redis 黑名单（登出/改密后旧令牌在此被拦）</li>
 *   <li>通过：把 userId / roleKey 写入 X-User-Id / X-Role-Key 请求头透传下游</li>
 * </ol>
 *
 * <p>API Key（X-API-Key）链路的网关鉴权在 M3 实现（接口文档第 2 章）。</p>
 */
// @Component：声明为 Spring 组件；实现 GlobalFilter 即对全部路由生效，无需注册
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    /** 免鉴权白名单 */
    private static final Set<String> WHITELIST = Set.of(
            "/auth/api/v1/login",
            "/auth/api/v1/refresh",
            "/gateway/api/v1/ping"
    );

    /** 各业务服务的 ping（健康检查免鉴权） */
    private static final Set<String> PING_PATHS = Set.of(
            "/auth/api/v1/ping", "/task/api/v1/ping",
            "/notification/api/v1/ping", "/stats/api/v1/ping");

    private final JwtUtils jwtUtils;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造：网关自己装配 JwtUtils（common 是工具类不是组件）与响应式 Redis 模板。
     *
     * @param secret JWT 密钥（与各服务同一配置 taskflow.jwt.secret）
     * @param ttl    令牌有效期（仅用于构造 JwtUtils，验签本身看 exp）
     * @param redis  响应式 Redis（黑名单查询）
     */
    public JwtAuthFilter(@Value("${taskflow.jwt.secret}") String secret,
                         @Value("${taskflow.jwt.ttl:PT2H}") Duration ttl,
                         ReactiveStringRedisTemplate redis) {
        this.jwtUtils = new JwtUtils(secret, ttl);
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (WHITELIST.contains(path) || PING_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return reject(exchange, ErrorCode.TOKEN_INVALID);
        }
        String token = authorization.substring(7);

        // 验签（含过期）；失败统一 401
        Claims claims;
        try {
            claims = jwtUtils.parse(token);
        } catch (Exception e) {
            return reject(exchange, ErrorCode.TOKEN_INVALID);
        }

        // 黑名单检查（登出/改密后旧令牌），通过则透传身份头
        return redis.hasKey("auth:blacklist:" + token).flatMap(inBlacklist -> {
            if (Boolean.TRUE.equals(inBlacklist)) {
                return reject(exchange, ErrorCode.TOKEN_INVALID);
            }
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Id", String.valueOf(jwtUtils.getUserId(claims)))
                    .header("X-Role-Key", jwtUtils.getRoleKey(claims))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        });
    }

    /**
     * 拒绝响应：统一信封 + 对应 HTTP 状态码。
     */
    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode errorCode) {
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(errorCode.getHttpStatus()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsString(Result.fail(errorCode))
                    .getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * 过滤器顺序：设为最高优先级，鉴权先于其他过滤器执行。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
