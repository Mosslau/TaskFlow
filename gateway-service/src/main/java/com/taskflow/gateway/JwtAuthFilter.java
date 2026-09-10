package com.taskflow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.JwtUtils;
import com.taskflow.common.Result;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>API Key（X-API-Key）链路已由 {@link ApiKeyAuthFilter}（M3.5）在本过滤器之前处理：
 * 携带 X-API-Key 头的请求到此处时要么已被拦截、要么身份头已注入，故直接放行。</p>
 */
// @Component：声明为 Spring 组件；实现 GlobalFilter 即对全部路由生效，无需注册
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

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

    /** Actuator 端点前缀（M7）：/actuator/health、/actuator/prometheus 等免鉴权 */
    private static final String ACTUATOR_PREFIX = "/actuator/";

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
        // M7：Actuator 探针/指标端点不鉴权（Prometheus 抓取与容器探针直连，无 JWT）
        if (path.startsWith(ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }
        if (WHITELIST.contains(path) || PING_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        // M3.5：X-API-Key 请求已由 ApiKeyAuthFilter 鉴权并注入身份头，本过滤器跳过
        if (exchange.getRequest().getHeaders().getFirst("X-API-Key") != null) {
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

        // 黑名单检查（登出/改密后旧令牌；M6 #4 键改为按令牌唯一 jti，
        // 与 auth-user-service 拉黑口径一致；旧版无 jti 令牌回退整串）
        String jti = claims.getId();
        String blacklistKey = (jti != null && !jti.isBlank())
                ? "auth:blacklist:" + jti : "auth:blacklist:" + token;
        return redis.hasKey(blacklistKey).flatMap(inBlacklist -> {
            if (Boolean.TRUE.equals(inBlacklist)) {
                return reject(exchange, ErrorCode.TOKEN_INVALID);
            }
            // M6 #1：X-Task-Source 只许由网关 OpenAPI 链路注入，JWT 请求一律剥离防伪造
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .headers(h -> h.remove("X-Task-Source"))
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
        log.warn("请求被网关拦截: path={}, code={}, message={}",
                exchange.getRequest().getPath().value(), errorCode.getCode(), errorCode.getMessage());
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
     * 过滤器顺序：次于 ApiKeyAuthFilter（X-API-Key 请求先由它接管），早于 RateLimitFilter。
     * M7 链路追踪引入后依次为：TraceIdFilter(MIN) → ApiKeyAuthFilter(+1) → 本过滤器(+2) → RateLimitFilter(+3)。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
