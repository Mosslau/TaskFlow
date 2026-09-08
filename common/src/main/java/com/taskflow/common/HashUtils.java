package com.taskflow.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希工具（目前仅 API Key 链路使用：Key 的 SHA-256 是落库形式与网关缓存键，
 * auth-user-service 与 gateway-service 必须用同一算法，故收敛到 common）。
 */
public final class HashUtils {

    private HashUtils() {
    }

    /**
     * SHA-256 并转小写十六进制串（64 位）。
     *
     * @param raw 原文（如 API Key 明文）
     * @return 十六进制哈希串
     */
    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
