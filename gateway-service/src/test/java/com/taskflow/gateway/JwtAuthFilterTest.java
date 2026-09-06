package com.taskflow.gateway;

import com.taskflow.common.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * JwtAuthFilter 单元测试（Mock 响应式 Redis + MockServerWebExchange，不启容器）。
 * 覆盖：白名单放行、缺令牌 401、合法令牌透传身份头、黑名单 401、伪造令牌 401。
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private static final String SECRET = "gateway-test-secret";

    @Mock
    private ReactiveStringRedisTemplate redis;

    private JwtAuthFilter filter;
    private final JwtUtils jwt = new JwtUtils(SECRET, Duration.ofHours(2));

    /** 记录下游是否被调用及透传的请求 */
    private final AtomicReference<ServerWebExchange> passedExchange = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        passedExchange.set(exchange);
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(SECRET, Duration.ofHours(2), redis);
        passedExchange.set(null);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> builder) {
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    @DisplayName("白名单（登录/刷新/ping）无令牌直接放行")
    void whitelistPasses() {
        StepVerifier.create(filter.filter(
                        exchange(MockServerHttpRequest.get("/auth/api/v1/login")), chain))
                .verifyComplete();
        assertTrue(passedExchange.get() != null, "白名单请求应到达下游");
    }

    @Test
    @DisplayName("缺 Authorization 头返回 401，不进入下游")
    void missingTokenRejected() {
        MockServerWebExchange ex = exchange(MockServerHttpRequest.get("/auth/api/v1/users"));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
        assertTrue(passedExchange.get() == null, "无令牌请求不应到达下游");
    }

    @Test
    @DisplayName("合法令牌：透传 X-User-Id / X-Role-Key 到下游")
    void validTokenPassesWithIdentityHeaders() {
        lenient().when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        String token = jwt.generate(7L, "taskAdmin");

        MockServerWebExchange ex = exchange(MockServerHttpRequest
                .get("/task/api/v1/tasks")
                .header("Authorization", "Bearer " + token));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        ServerWebExchange passed = passedExchange.get();
        assertTrue(passed != null, "合法令牌应到达下游");
        assertEquals("7", passed.getRequest().getHeaders().getFirst("X-User-Id"));
        assertEquals("taskAdmin", passed.getRequest().getHeaders().getFirst("X-Role-Key"));
    }

    @Test
    @DisplayName("黑名单令牌（登出/改密后）返回 401")
    void blacklistedTokenRejected() {
        lenient().when(redis.hasKey(anyString())).thenReturn(Mono.just(true));
        String token = jwt.generate(7L, "admin");

        MockServerWebExchange ex = exchange(MockServerHttpRequest
                .get("/auth/api/v1/users")
                .header("Authorization", "Bearer " + token));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
        assertTrue(passedExchange.get() == null, "黑名单令牌不应到达下游");
    }

    @Test
    @DisplayName("伪造令牌（签名错误）返回 401")
    void forgedTokenRejected() {
        String forged = jwt.generate(7L, "admin") + "x";
        MockServerWebExchange ex = exchange(MockServerHttpRequest
                .get("/auth/api/v1/users")
                .header("Authorization", "Bearer " + forged));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
        assertTrue(passedExchange.get() == null, "伪造令牌不应到达下游");
    }
}
