package com.taskflow.common;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis 常用封装（架构文档 2.2：Redis 承担令牌/缓存/限流/分布式锁）。
 *
 * <p>本项目 Redis 的四类用途及对应方法：</p>
 * <ul>
 *   <li>令牌黑名单 / 刷新令牌 → {@link #set} + {@link #hasKey}</li>
 *   <li>权限矩阵与用户快照缓存 → {@link #set} + {@link #get} + {@link #delete}</li>
 *   <li>定时任务分布式锁 → {@link #setIfAbsent}</li>
 *   <li>登录失败计数 → {@link #set}（带 15 分钟过期，PRD 7.3.1）</li>
 * </ul>
 *
 * <p>键值统一为 String：跨服务可读、排查方便（redis-cli 直接看）。
 * 使用方由各服务通过配置类注册为 Bean（common 不做组件扫描）。</p>
 */
public class RedisUtils {

    /** 底层模板：key 与 value 均为 String 序列化 */
    private final StringRedisTemplate redis;

    /**
     * 构造（由使用方的配置类注入 StringRedisTemplate）。
     *
     * @param redis Spring Data Redis 的字符串模板
     */
    public RedisUtils(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 写值并设置过期时间。过期是清理的主要手段（黑名单、锁定计数都依赖 TTL 自动失效）。
     *
     * @param key   键（建议带服务前缀，如 "auth:blacklist:{jti}"）
     * @param value 值
     * @param ttl   存活时间
     */
    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    /**
     * 读值。
     *
     * @param key 键
     * @return 值；不存在返回 null
     */
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    /**
     * 删键（缓存主动失效用，如权限矩阵变更后立即删缓存）。
     *
     * @param key 键
     */
    public void delete(String key) {
        redis.delete(key);
    }

    /**
     * 判断键是否存在（黑名单查询用）。
     *
     * @param key 键
     * @return 存在返回 true
     */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /**
     * SETNX + 过期时间（原子）：分布式锁与幂等占位。
     * 定时任务抢锁典型用法：{@code if (setIfAbsent("lock:due-scan", "1", Duration.ofMinutes(5))) { 执行扫描 } }
     *
     * @param key   锁键
     * @param value 锁值（建议带实例标识，便于排查持锁者）
     * @param ttl   锁的兜底过期时间（防持锁者宕机导致死锁）
     * @return 抢锁成功返回 true；键已存在（他人持锁）返回 false
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl));
    }
}
