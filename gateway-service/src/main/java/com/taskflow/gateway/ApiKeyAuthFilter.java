package com.taskflow.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.HashUtils;
import com.taskflow.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * API Key 全局鉴权过滤器（M3.5，接口设计文档第 2 章：静态 Key 仅开放 POST /task/api/v1/tasks）。
 *
 * <p>仅当请求携带 {@code X-API-Key} 头时接管，否则交给 {@link JwtAuthFilter}。处理流程：</p>
 * <ol>
 *   <li>同时携带 JWT 与 API Key 两种互斥凭证 → 1004</li>
 *   <li>查 Redis 校验缓存 {@code tf:apikey:{sha256(key)}}（60s TTL），
 *       未命中用 WebClient 回源 lb://auth-user-service 的 /auth/api/v1/api-keys/validate</li>
 *   <li>Key 无效/已停用 → 401（3006）；停用时 auth 侧会主动删除缓存键，保证立即生效</li>
 *   <li>路径白名单：仅放行 POST /task/api/v1/tasks，其余 → 403（3010）</li>
 *   <li>通过：注入 X-User-Id / X-Role-Key 透传下游；JwtAuthFilter 见 X-API-Key 头会跳过本请求</li>
 * </ol>
 */
@Component
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** API Key 请求头 */
    private static final String API_KEY_HEADER = "X-API-Key";

    /** 校验缓存键前缀（与 auth-user-service ApiKeyService 约定一致，停用/重生成时由 auth 侧删除） */
    private static final String CACHE_PREFIX = "tf:apikey:";

    /** 校验缓存 TTL：60s（回源兜底刷新，停用即时性靠 auth 侧主动删键保证） */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** Key 唯一允许调用的路径与方法（接口文档第 2 章） */
    private static final String ALLOWED_PATH = "/task/api/v1/tasks";

    private final ReactiveStringRedisTemplate redis;
    private final WebClient authClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造：WebClient 挂 LoadBalancer 交换函数，使 lb:// 风格的服务名地址可解析
     * （spring-cloud-starter-loadbalancer 自动装配 ReactorLoadBalancerExchangeFilterFunction）。
     *
     * @param redis      响应式 Redis（校验缓存）
     * @param lbFunction 负载均衡交换函数（按 Nacos 服务名解析 auth-user-service 实例）
     */
    public ApiKeyAuthFilter(ReactiveStringRedisTemplate redis,
                            ReactorLoadBalancerExchangeFilterFunction lbFunction) {
        this.redis = redis;
        this.authClient = WebClient.builder()
                .filter(lbFunction)
                .baseUrl("http://auth-user-service")
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            // 非 API Key 请求：交给 JwtAuthFilter 处理
            return chain.filter(exchange);
        }

        // JWT 与 API Key 互斥，同时携带按非法请求处理
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return reject(exchange, ErrorCode.BAD_REQUEST, "JWT 与 API Key 互斥，请只携带一种凭证");
        }

        String cacheKey = CACHE_PREFIX + HashUtils.sha256Hex(apiKey);
        return redis.opsForValue().get(cacheKey)
                .flatMap(cached -> authorize(exchange, chain, apiKey, cached))
                // 缓存未命中：回源 auth-user-service 校验
                .switchIfEmpty(Mono.defer(() -> validateRemotely(apiKey)
                        .flatMap(identity ->
                                // 回填缓存（JSON：{"userId":..,"roleKey":..}）后走统一授权
                                redis.opsForValue().set(cacheKey, identity, CACHE_TTL)
                                        .then(authorize(exchange, chain, apiKey, identity)))
                        // 回源判为无效/停用/调用失败：统一 401（fail-closed，绝不放空响应）
                        .switchIfEmpty(Mono.defer(() -> reject(exchange, ErrorCode.API_KEY_INVALID, null)))));
    }

    /**
     * 回源校验：GET auth-user-service 的 validate 端点。
     *
     * @return 校验通过返回身份 JSON 串；业务校验失败（3006 等）返回 null
     */
    private Mono<String> validateRemotely(String apiKey) {
        return authClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/auth/api/v1/api-keys/validate")
                        .queryParam("key", apiKey)
                        .build())
                // exchangeToMono：非 2xx（如 401）也读 body 自行判断，不走 WebClient 默认抛异常
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty(""))
                .flatMap(body -> {
                    try {
                        JsonNode envelope = objectMapper.readTree(body);
                        if (envelope.path("code").asInt(-1) != 0) {
                            return Mono.empty(); // Key 无效/停用，按 401 处理
                        }
                        JsonNode data = envelope.path("data");
                        // 只缓存身份所需的两个字段，过期时间等不缓存
                        String identity = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("userId", data.path("userId").asLong())
                                .put("roleKey", data.path("roleKey").asText()));
                        return Mono.just(identity);
                    } catch (Exception e) {
                        log.warn("API Key 回源校验响应解析失败: {}", e.getMessage());
                        return Mono.empty();
                    }
                })
                // auth-user-service 不可达等基础设施错误：不缓存、按 401 拒绝（fail-closed）
                .onErrorResume(e -> {
                    log.error("API Key 回源校验调用失败: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 校验通过后的路径授权与身份头注入。
     *
     * @param identityJson 缓存的身份 JSON（{"userId":..,"roleKey":..}）
     */
    private Mono<Void> authorize(ServerWebExchange exchange, GatewayFilterChain chain,
                                 String apiKey, String identityJson) {
        ServerHttpRequest request = exchange.getRequest();
        // 路径白名单：一期 Key 仅开放 POST /task/api/v1/tasks
        if (!(HttpMethod.POST.equals(request.getMethod()) && ALLOWED_PATH.equals(request.getPath().value()))) {
            return reject(exchange, ErrorCode.API_KEY_PATH_FORBIDDEN, null);
        }
        try {
            JsonNode identity = objectMapper.readTree(identityJson);
            ServerHttpRequest mutated = request.mutate()
                    .headers(h -> h.remove("X-Task-Source")) // 防客户端伪造，仅由本过滤器注入
                    .header("X-User-Id", String.valueOf(identity.path("userId").asLong()))
                    .header("X-Role-Key", identity.path("roleKey").asText())
                    // M6 #1：OpenAPI 渠道来源标记 → task-service 创建任务落 source="OpenAPI"
                    .header("X-Task-Source", "openapi")
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            // 缓存内容损坏：删掉后按回源重新校验（避免脏缓存永久拦截）
            log.warn("API Key 缓存内容损坏，已删除: {}", e.getMessage());
            return redis.delete(CACHE_PREFIX + HashUtils.sha256Hex(apiKey))
                    .then(reject(exchange, ErrorCode.API_KEY_INVALID, null));
        }
    }

    /**
     * 拒绝响应：统一信封 + 对应 HTTP 状态码（与 JwtAuthFilter.reject 同构）。
     */
    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode errorCode, String message) {
        log.warn("API Key 请求被网关拦截: path={}, code={}",
                exchange.getRequest().getPath().value(), errorCode.getCode());
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(errorCode.getHttpStatus()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            Result<Void> body = message == null
                    ? Result.fail(errorCode)
                    : Result.fail(errorCode, message, null);
            byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * 过滤器顺序：先于 JwtAuthFilter 执行，决定是否接管 X-API-Key 请求。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
