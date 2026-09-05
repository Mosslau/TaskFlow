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
 * JWT 工具（架构文档 4.1 登录与鉴权链路）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>auth-user-service 登录成功后用 {@link #generate} 签发令牌</li>
 *   <li>gateway-service 用 {@link #parse} 验签（M1 接入网关过滤器）</li>
 * </ul>
 *
 * <p>令牌设计（PRD 7.3.2）：HS256 对称签名；载荷（claims）含：</p>
 * <ul>
 *   <li>{@code sub}：用户 id（JWT 标准 subject 字段）</li>
 *   <li>{@code roleKey}：角色键（admin/taskAdmin/user），下游服务据此做本地权限点校验</li>
 *   <li>{@code iat / exp}：签发与过期时间，有效期默认 2 小时</li>
 * </ul>
 *
 * <p>密钥从配置 {@code taskflow.jwt.secret} 注入（生产走环境变量，PRD 7.3.5），
 * 登出/改密后的旧令牌由 Redis 黑名单拦截，不在本类职责内。</p>
 */
public class JwtUtils {

    /** HS256 签名密钥（由 secret 字符串派生） */
    private final SecretKey key;

    /** 令牌有效期（毫秒），默认 2 小时（PRD 7.3.2） */
    private final long ttlMillis;

    /**
     * 构造 JWT 工具。
     *
     * @param secret 配置注入的密钥字符串（HS256 要求 ≥ 32 字节，不足会自动填充，见 {@link #pad}）
     * @param ttl    令牌有效期
     */
    public JwtUtils(String secret, Duration ttl) {
        this.key = Keys.hmacShaKeyFor(pad(secret).getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = ttl.toMillis();
    }

    /**
     * 密钥长度兜底：HS256 强制要求密钥 ≥ 256 位（32 字节），
     * 开发环境密钥过短时循环填充至达标（生产必须用足够长的随机密钥）。
     *
     * @param secret 原始密钥字符串
     * @return 长度 ≥ 32 的密钥字符串
     */
    private static String pad(String secret) {
        StringBuilder sb = new StringBuilder(secret);
        while (sb.length() < 32) {
            sb.append(secret);
        }
        return sb.toString();
    }

    /**
     * 签发 JWT。
     *
     * @param userId  用户 id（写入标准 sub 字段）
     * @param roleKey 角色键（写入自定义 claim，下游服务本地鉴权用）
     * @return 紧凑格式的 JWT 字符串（header.payload.signature）
     */
    public String generate(long userId, String roleKey) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))      // sub：用户 id
                .claim("roleKey", roleKey)            // 自定义 claim：角色键
                .issuedAt(Date.from(now))             // iat：签发时间
                .expiration(Date.from(now.plusMillis(ttlMillis))) // exp：过期时间
                .signWith(key)                        // HS256 签名
                .compact();
    }

    /**
     * 解析并验签。签名校验、过期校验失败一律转为业务异常 TOKEN_INVALID（401），
     * 调用方无需区分"过期"和"伪造"。
     *
     * @param token JWT 字符串（不含 "Bearer " 前缀）
     * @return 解析出的载荷
     * @throws BizException 令牌无效或过期（ErrorCode.TOKEN_INVALID）
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException：签名错误/过期/格式错误；IllegalArgumentException：token 为空等
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    /**
     * 从载荷取用户 id。
     *
     * @param claims {@link #parse} 的返回值
     * @return 用户 id
     */
    public long getUserId(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 从载荷取角色键。
     *
     * @param claims {@link #parse} 的返回值
     * @return 角色键（admin / taskAdmin / user）
     */
    public String getRoleKey(Claims claims) {
        return claims.get("roleKey", String.class);
    }
}
