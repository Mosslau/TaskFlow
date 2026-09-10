package com.taskflow.task.service;

import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.HashUtils;
import com.taskflow.common.RedisUtils;
import com.taskflow.task.entity.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * 创建任务幂等键（{@code Idempotency-Key}）服务。
 *
 * <p>解决的问题：网络重试 / 前端双击 / 网关重放都会向
 * {@code POST /task/api/v1/tasks} 重放同一份请求体，接入前会落多条任务。</p>
 *
 * <p>语义（标准 Idempotency-Key）：同一「用户 + Key」在 24 小时内只创建一条任务，
 * 重复请求不新建、按首次记录的 taskId 回放同一结果（HTTP 200 + {@code Result.ok(任务)}）。</p>
 *
 * <p>键设计：{@code task:idem:{userId}:{sha256(Idempotency-Key)}}。
 * 夹 userId 保证跨用户隔离；Key 只存 SHA-256（原文不落 Redis，防日志/快照泄露客户端密钥类字符串）；
 * 显式用户段 + 哈希段使 Key 不会与相邻段产生歧义拼接。</p>
 *
 * <p>并发处理（无 DB 唯一约束时的最小代价方案）：</p>
 * <ol>
 *   <li>先读键：已有 taskId 记录 → 直接回放（幂等命中，常见路径）；</li>
 *   <li>键不存在 → {@code SETNX 占位 "PENDING"}（TTL 60s，兜底防持锁者宕机死锁）；
 *       抢到者执行真正的创建，成功后把值改写为 taskId（TTL 24h）；</li>
 *   <li>没抢到（他人处理中）→ 50ms 轮询最多 2s 等结果；等到 taskId 则回放，
 *       超时仍 PENDING 则返回 2014（客户端可用同一 Key 稍后重试，仍是安全重放）；</li>
 *   <li>抢到者创建失败（异常/事务回滚）→ 主动删除占位，让同一 Key 的重试可以再次真正创建。</li>
 * </ol>
 *
 * <p>事务边界：本类在 Controller 层被调用，{@code creator} 内部走
 * {@code TaskService.create}（Spring 代理），返回即已提交，因此「落 Redis 记录」
 * 总发生在任务真正入库之后，不会出现"记录指向不存在的任务"。</p>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** 幂等记录键前缀（键形如 task:idem:{userId}:{sha256}） */
    public static final String KEY_PREFIX = "task:idem:";

    /** 处理中占位值 */
    private static final String PENDING = "PENDING";

    /** 成功记录的存活时间：24 小时（窗口内同 Key 一律回放同一条任务） */
    private static final Duration RECORD_TTL = Duration.ofHours(24);

    /** 占位标记存活时间：60 秒（创建远快于此，仅作宕机兜底） */
    private static final Duration PENDING_TTL = Duration.ofSeconds(60);

    /** 并发重复请求最长等待时长：2 秒 */
    private static final long WAIT_TIMEOUT_MILLIS = 2000L;

    /** 等待结果的轮询间隔：50 毫秒 */
    private static final long POLL_INTERVAL_MILLIS = 50L;

    /** Idempotency-Key 允许的最大长度（字符） */
    public static final int MAX_KEY_LENGTH = 200;

    private final RedisUtils redis;

    /**
     * 构造。
     *
     * @param redis Redis 封装（各服务自注册 Bean）
     */
    public IdempotencyService(RedisUtils redis) {
        this.redis = redis;
    }

    /**
     * 幂等执行「创建任务」动作。
     *
     * <p>{@code idempotencyKey} 为 null（客户端未带请求头）时直接执行 {@code creator}，
     * 行为与接入幂等键之前完全一致。</p>
     *
     * @param idempotencyKey 请求头 Idempotency-Key 原值；null 表示未携带
     * @param userId         当前用户 id（取自网关注入的身份头，作为幂等作用域）
     * @param creator        真正的创建动作（须已提交事务后才返回）
     * @param replayLoader   按 taskId 读取任务；任务已被删除时返回 null
     * @return 本次创建的任务，或首次创建时同一任务的重放
     * @throws BizException 1001（Key 为空/超长）、2014（并发重复且等待超时）、1002（记录的任务已不存在）
     */
    public Task createIdempotent(String idempotencyKey, Long userId,
                                 Supplier<Task> creator, LongFunction<Task> replayLoader) {
        if (idempotencyKey == null) {
            return creator.get();
        }
        String redisKey = redisKey(userId, normalize(idempotencyKey));

        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (true) {
            String current = redis.get(redisKey);

            // ① 已有成功记录：回放首次结果，绝不重复建单
            if (current != null && !PENDING.equals(current)) {
                Task replayed = tryReplay(redisKey, current, replayLoader);
                if (replayed != null) {
                    return replayed;
                }
                // 脏记录已清除：继续走下面的抢占分支重新真正创建
                continue;
            }

            // ② 无记录：抢占位，抢到者负责真正的创建
            if (current == null && redis.setIfAbsent(redisKey, PENDING, PENDING_TTL)) {
                return doCreate(redisKey, creator, replayLoader);
            }

            // ③ 他人处理中：轮询等结果，超时（或被中断）返回 2014
            if (Thread.currentThread().isInterrupted() || System.currentTimeMillis() >= deadline) {
                log.warn("幂等键并发等待超时(2s): key={}", redisKey);
                throw new BizException(ErrorCode.IDEMPOTENT_IN_PROGRESS);
            }
            sleep();
        }
    }

    /**
     * 抢到占位后执行创建并落记录。
     *
     * <p>返回值统一取「库里的那一行」（插入后按 id 回查）：创建响应与后续重放响应因此
     * 逐字段一致（含 DB 生成的 createdAt/updatedAt），"重放得到同一结果"是字面成立而非近似成立。
     * 回查失败时退回内存实体，不影响创建结果。</p>
     *
     * @param redisKey     幂等记录键
     * @param creator      创建动作
     * @param replayLoader 按 taskId 读取任务
     * @return 创建出的任务
     */
    private Task doCreate(String redisKey, Supplier<Task> creator, LongFunction<Task> replayLoader) {
        Task task;
        try {
            task = creator.get();
        } catch (RuntimeException e) {
            // 创建失败（校验失败/事务回滚）：释放占位，使同一 Key 的重试能重新真正创建
            redis.delete(redisKey);
            throw e;
        }
        try {
            redis.set(redisKey, String.valueOf(task.getId()), RECORD_TTL);
        } catch (RuntimeException e) {
            // Redis 异常不牵连已入库的任务：仅告警，客户端仍拿到创建结果（极端降级下失去幂等保护）
            log.error("幂等记录写入失败，降级返回创建结果: key={}, taskId={}", redisKey, task.getId(), e);
        }
        try {
            Task persisted = replayLoader.apply(task.getId());
            return persisted == null ? task : persisted;
        } catch (RuntimeException e) {
            log.warn("创建后回查任务失败，返回内存实体: taskId={}", task.getId(), e);
            return task;
        }
    }

    /**
     * 按记录值回放。
     *
     * @param redisKey     幂等记录键
     * @param recorded     记录值（应为 taskId 字符串）
     * @param replayLoader 按 taskId 读取任务
     * @return 重放的任务；记录值非法（已清除脏记录）时返回 null 表示需重新创建
     * @throws BizException 1002 记录指向的任务已被删除（重放失败的唯一明确语义）
     */
    private Task tryReplay(String redisKey, String recorded, LongFunction<Task> replayLoader) {
        long taskId;
        try {
            taskId = Long.parseLong(recorded.trim());
        } catch (NumberFormatException e) {
            // 非本服务写入的脏值：清除后退化为正常创建，避免把该用户永久卡死在 1002/2014
            log.warn("幂等记录值非法，已清除并退化为新建: key={}, value={}", redisKey, recorded);
            redis.delete(redisKey);
            return null;
        }
        Task task = replayLoader.apply(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "任务已不存在，幂等重放失败");
        }
        log.info("幂等命中，回放首次创建结果: key={}, taskId={}", redisKey, taskId);
        return task;
    }

    /**
     * 校验并归一化 Key。
     *
     * @param rawKey 请求头原值
     * @return 去首尾空白后的 Key
     * @throws BizException 1001 空串/纯空白，或超过 200 字符
     */
    static String normalize(String rawKey) {
        String key = rawKey.trim();
        if (key.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Idempotency-Key 不能为空字符串");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "Idempotency-Key 长度不能超过 " + MAX_KEY_LENGTH + " 字符");
        }
        return key;
    }

    /**
     * 计算 Redis 键。
     *
     * @param userId 用户 id
     * @param key    已归一化的 Idempotency-Key
     * @return {@code task:idem:{userId}:{sha256(key)}}
     */
    static String redisKey(Long userId, String key) {
        return KEY_PREFIX + userId + ":" + HashUtils.sha256Hex(key);
    }

    /** 轮询间隔休眠（中断时恢复中断位并提前结束等待，由上层超时判定收尾） */
    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
