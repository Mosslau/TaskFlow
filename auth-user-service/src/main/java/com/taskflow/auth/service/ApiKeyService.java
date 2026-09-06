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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    private final ApiKeyMapper apiKeyMapper;
    private final AppUserMapper userMapper;
    private final RoleMapper roleMapper;

    public ApiKeyService(ApiKeyMapper apiKeyMapper, AppUserMapper userMapper, RoleMapper roleMapper) {
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
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
     * 停用 / 启用。
     */
    public void changeStatus(Long id, String status) {
        ApiKey key = mustExist(id);
        key.setStatus(status);
        apiKeyMapper.updateById(key);
    }

    /**
     * 重新生成：更新哈希与前缀，旧 Key 立即失效。
     *
     * @return 新 Key 明文（仅此一次）
     */
    public Map<String, Object> regenerate(Long id) {
        ApiKey key = mustExist(id);
        return persistNewKey(key, null, null);
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
        key.setKeyHash(sha256(plain));
        key.setKeyPrefix(plain.substring(0, 8));
        key.setStatus("active");
        if (key.getId() == null) {
            apiKeyMapper.insert(key);
        } else {
            apiKeyMapper.updateById(key);
        }
        return Map.of("id", key.getId(), "apiKey", plain);
    }

    /** SHA-256 哈希（Key 落库形式） */
    private static String sha256(String raw) {
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

    /** 取 Key 或抛 1002 */
    private ApiKey mustExist(Long id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "API Key 不存在");
        }
        return key;
    }
}
