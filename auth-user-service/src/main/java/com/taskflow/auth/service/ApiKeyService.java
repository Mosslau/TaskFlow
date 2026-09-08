package com.taskflow.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.auth.entity.ApiKey;
import com.taskflow.auth.entity.AppUser;
import com.taskflow.auth.entity.Role;
import com.taskflow.auth.mapper.ApiKeyMapper;
import com.taskflow.auth.mapper.AppUserMapper;
import com.taskflow.auth.mapper.RoleMapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.HashUtils;
import com.taskflow.common.RedisUtils;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API Key 管理服务（接口设计文档第 2 章：静态 Key，仅开放 POST /task/api/v1/tasks）。
 *
 * <p>安全要点：Key 明文只存在于签发/重生成的响应中，库里只存 SHA-256 哈希与前 8 位前缀；
 * Key 必须绑定角色为 taskAdmin 的专用服务账号。</p>
 */
@Service
public class ApiKeyService {

    /** 网关校验缓存键前缀（与 gateway-service ApiKeyAuthFilter 约定一致）：tf:apikey:{sha256(明文)} */
    public static final String GATEWAY_CACHE_PREFIX = "tf:apikey:";

    private final ApiKeyMapper apiKeyMapper;
    private final AppUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final RedisUtils redis;

    public ApiKeyService(ApiKeyMapper apiKeyMapper, AppUserMapper userMapper,
                         RoleMapper roleMapper, RedisUtils redis) {
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.redis = redis;
    }

    /**
     * 签发 API Key。
     *
     * @param name   调用方名称
     * @param userId 绑定的服务账号（必须是 taskAdmin 角色）
     * @return id 与 Key 明文（仅此一次）
     * @throws BizException 1001 绑定账号不是 taskAdmin / 1002 账号不存在
     */
    public Map<String, Object> create(String name, Long userId) {
        AppUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "绑定账号不存在");
        }
        Role role = roleMapper.selectById(user.getRoleId());
        if (role == null || !"taskAdmin".equals(role.getRoleKey())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "API Key 只能绑定 taskAdmin 角色的服务账号");
        }
        return persistNewKey(new ApiKey(), name, userId);
    }

    /**
     * Key 列表（不含明文与哈希，只回前缀）。
     */
    public List<Map<String, Object>> list() {
        return apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                        .orderByDesc(ApiKey::getCreatedAt))
                .stream().map(k -> Map.<String, Object>of(
                        "id", k.getId(),
                        "name", k.getName(),
                        "userId", k.getUserId(),
                        "keyPrefix", k.getKeyPrefix(),
                        "status", k.getStatus(),
                        "createdAt", k.getCreatedAt().toString(),
                        "lastUsedAt", k.getLastUsedAt() == null ? "" : k.getLastUsedAt().toString()))
                .collect(Collectors.toList());
    }

    /**
     * 停用 / 启用。状态变更后主动删除网关校验缓存（tf:apikey:{sha256}），保证停用立即 401。
     */
    public void changeStatus(Long id, String status) {
        ApiKey key = mustExist(id);
        key.setStatus(status);
        apiKeyMapper.updateById(key);
        evictGatewayCache(key.getKeyHash());
    }

    /**
     * 重新生成：更新哈希与前缀，旧 Key 立即失效（旧哈希的网关缓存一并删除）。
     *
     * @return 新 Key 明文（仅此一次）
     */
    public Map<String, Object> regenerate(Long id) {
        ApiKey key = mustExist(id);
        String oldHash = key.getKeyHash();
        Map<String, Object> result = persistNewKey(key, null, null);
        evictGatewayCache(oldHash);
        return result;
    }

    /**
     * API Key 内部校验（M3.5：供网关 ApiKeyAuthFilter 缓存未命中时回源调用）。
     *
     * <p>按明文的 SHA-256 精确匹配；不存在 / 已停用统一抛 3006（不区分原因，防探测）。
     * 校验通过即刷新 last_used_at（网关有 60s 缓存，刷新频率天然受限）。</p>
     *
     * @param plain Key 明文
     * @return {userId, account, roleKey, status, expiresAt（一期不过期，固定 null）}
     * @throws BizException 3006 Key 不存在或已停用
     */
    public Map<String, Object> validate(String plain) {
        ApiKey key = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, HashUtils.sha256Hex(plain)));
        if (key == null || !"active".equals(key.getStatus())) {
            throw new BizException(ErrorCode.API_KEY_INVALID);
        }
        AppUser user = userMapper.selectById(key.getUserId());
        Role role = user == null ? null : roleMapper.selectById(user.getRoleId());
        if (user == null || role == null || !"active".equals(user.getStatus())) {
            // 绑定账号或角色异常（被删/停用）视同 Key 失效
            throw new BizException(ErrorCode.API_KEY_INVALID);
        }
        // 刷新最近使用时间（仅回源时发生，网关缓存期内不重复写）
        key.setLastUsedAt(OffsetDateTime.now());
        apiKeyMapper.updateById(key);

        // LinkedHashMap：expiresAt 为 null，不能用 Map.of（不接受 null 值）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("account", user.getAccount());
        data.put("roleKey", role.getRoleKey());
        data.put("status", key.getStatus());
        data.put("expiresAt", null); // api_key 表暂无过期字段，预留
        return data;
    }

    /** 删除网关侧校验缓存（停用 / 重生成后旧 Key 立即 401，不等 60s TTL） */
    private void evictGatewayCache(String keyHash) {
        redis.delete(GATEWAY_CACHE_PREFIX + keyHash);
    }

    /**
     * 生成新 Key 并落库（签发与重生成共用）。
     *
     * @param key    新建的空实体或已有实体（重生成）
     * @param name   签发时的调用方名称（重生成为 null）
     * @param userId 签发时的绑定账号（重生成为 null）
     * @return id 与明文（仅此一次）
     */
    private Map<String, Object> persistNewKey(ApiKey key, String name, Long userId) {
        // Key 格式：tfk_ + 32 位十六进制随机串
        String plain = "tfk_" + AuthService.newToken().substring(0, 32);
        if (name != null) {
            key.setName(name);
        }
        if (userId != null) {
            key.setUserId(userId);
        }
        key.setKeyHash(HashUtils.sha256Hex(plain));
        key.setKeyPrefix(plain.substring(0, 8));
        key.setStatus("active");
        if (key.getId() == null) {
            apiKeyMapper.insert(key);
        } else {
            apiKeyMapper.updateById(key);
        }
        return Map.of("id", key.getId(), "apiKey", plain);
    }

    /** 取 Key 或抛 1002 */
    private ApiKey mustExist(Long id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "API Key 不存在");
        }
        return key;
    }
}
