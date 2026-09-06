package com.taskflow.common;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtUtils 单元测试（纯计算，无外部依赖）。
 */
class JwtUtilsTest {

    private final JwtUtils jwt = new JwtUtils("test-secret-key", Duration.ofHours(2));

    @Test
    @DisplayName("签发-解析往返：userId 与 roleKey 一致")
    void roundTrip() {
        String token = jwt.generate(42L, "taskAdmin");
        Claims claims = jwt.parse(token);
        assertEquals(42L, jwt.getUserId(claims));
        assertEquals("taskAdmin", jwt.getRoleKey(claims));
    }

    @Test
    @DisplayName("篡改载荷的令牌验签失败（3005）")
    void tamperedTokenRejected() {
        String token = jwt.generate(42L, "user");
        // 篡改 payload 段的一个字符
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "xx." + parts[2];

        BizException e = assertThrows(BizException.class, () -> jwt.parse(tampered));
        assertEquals(ErrorCode.TOKEN_INVALID, e.getErrorCode());
    }

    @Test
    @DisplayName("不同密钥签发的令牌验签失败")
    void wrongSecretRejected() {
        JwtUtils other = new JwtUtils("another-secret", Duration.ofHours(2));
        String token = other.generate(1L, "admin");
        assertThrows(BizException.class, () -> jwt.parse(token));
    }

    @Test
    @DisplayName("过期令牌解析失败")
    void expiredTokenRejected() {
        // ttl = 0：签完即过期
        JwtUtils expired = new JwtUtils("test-secret-key", Duration.ZERO);
        String token = expired.generate(1L, "admin");
        BizException e = assertThrows(BizException.class, () -> expired.parse(token));
        assertEquals(ErrorCode.TOKEN_INVALID, e.getErrorCode());
    }

    @Test
    @DisplayName("短密钥自动填充不报错")
    void shortSecretPadded() {
        JwtUtils shortKey = new JwtUtils("abc", Duration.ofMinutes(5));
        String token = shortKey.generate(1L, "admin");
        assertTrue(shortKey.parse(token).getSubject().equals("1"));
    }
}
