package com.taskflow.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 工具（架构文档 4.1）：载荷含 userId 与 roleKey。
 * 密钥从配置注入，禁止硬编码。
 */
public class JwtUtils {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtUtils(String secret, Duration ttl) {
        this.key = Keys.hmacShaKeyFor(pad(secret).getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = ttl.toMillis();
    }

    /** HS256 要求密钥 ≥ 32 字节，不足则循环填充（开发环境容错） */
    private static String pad(String secret) {
        StringBuilder sb = new StringBuilder(secret);
        while (sb.length() < 32) {
            sb.append(secret);
        }
        return sb.toString();
    }

    public String generate(long userId, String roleKey) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("roleKey", roleKey)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并验签；失败抛 BizException(TOKEN_INVALID)。
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    public long getUserId(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }

    public String getRoleKey(Claims claims) {
        return claims.get("roleKey", String.class);
    }
}
