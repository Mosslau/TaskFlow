package com.taskflow.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.auth.entity.AppUser;
import com.taskflow.auth.entity.Role;
import com.taskflow.auth.mapper.AppUserMapper;
import com.taskflow.auth.mapper.RoleMapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.JwtUtils;
import com.taskflow.common.RedisUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 认证服务（PRD 7.3 + 接口文档 3.1）。
 *
 * <p>覆盖：登录（BCrypt 校验 + 失败锁定 + JWT 签发）、注销（黑名单）、
 * 刷新令牌、修改密码（旧令牌全失效）。</p>
 *
 * <p>Redis 键约定：</p>
 * <ul>
 *   <li>{@code auth:fail:{account}} —— 登录失败计数（5 次锁 15 分钟，TTL 即锁定时长）</li>
 *   <li>{@code auth:lock:{account}} —— 锁定标记</li>
 *   <li>{@code auth:refresh:{userId}} —— 刷新令牌（7 天）</li>
 *   <li>{@code auth:blacklist:{token}} —— JWT 黑名单（TTL = 令牌剩余有效期）</li>
 * </ul>
 */
// @Service：业务层组件，纳入 Spring 容器并可参与声明式事务
@Service
public class AuthService {

    /** 连续失败上限（PRD 7.3.1） */
    private static final int MAX_FAIL = 5;

    /** 锁定时长 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(15);

    /** 刷新令牌有效期（PRD 7.3.2） */
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private final AppUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionService permissionService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RedisUtils redis;

    /** 构造注入全部依赖（Spring 容器自动装配） */
    public AuthService(AppUserMapper userMapper, RoleMapper roleMapper,
                       PermissionService permissionService, BCryptPasswordEncoder passwordEncoder,
                       JwtUtils jwtUtils, RedisUtils redis) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionService = permissionService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.redis = redis;
    }

    /**
     * 登录：校验账号密码，签发 JWT + 刷新令牌。
     *
     * @param account 账号
     * @param rawPassword 明文密码
     * @return token / refreshToken / expiresIn / user（含权限点清单）
     * @throws BizException 3002 密码错（details 含剩余次数）/ 3003 锁定 / 3004 停用
     */
    public Map<String, Object> login(String account, String rawPassword) {
        // ① 锁定检查（优先级最高：锁定期间不校验密码，防探测）
        if (redis.hasKey("auth:lock:" + account)) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }

        // ② 查用户（账号唯一索引命中）
        AppUser user = userMapper.selectOne(
                new LambdaQueryWrapper<AppUser>().eq(AppUser::getAccount, account));
        // 账号不存在与密码错误返回同一错误码，不泄露账号是否存在；
        // 但失败计数对不存在账号同样累计（PRD 7.3.1 锁定按账号维度，防爆破）
        if (user == null) {
            throw failAndBuild(account);
        }
        // ③ 停用账号禁止登录（PRD 4.5.1）
        if ("disabled".equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        // ④ BCrypt 校验（matches 内部处理盐，恒定时间比较防时序攻击）
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw failAndBuild(account);
        }

        // ⑤ 校验通过：清失败计数，查角色与权限点
        redis.delete("auth:fail:" + account);
        Role role = roleMapper.selectById(user.getRoleId());
        Set<String> permissions = permissionService.getEnabledPermissions(role.getRoleKey());

        // ⑥ 签发 JWT（2h）+ 刷新令牌（7 天，存 Redis 供注销时撤销）
        String token = jwtUtils.generate(user.getId(), role.getRoleKey());
        String refreshToken = newToken();
        redis.set("auth:refresh:" + user.getId(), refreshToken, REFRESH_TTL);

        return Map.of(
                "token", token,
                "refreshToken", refreshToken,
                "expiresIn", 7200,
                "user", Map.of(
                        "id", user.getId(),
                        "name", user.getName(),
                        "account", user.getAccount(),
                        "roleKey", role.getRoleKey(),
                        "permissions", permissions,
                        "mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword())));
    }

    /**
     * 注销：当前 JWT 加黑名单（网关校验时拦截），刷新令牌删除。
     *
     * @param userId 当前用户 id
     * @param token  当前 JWT
     */
    public void logout(Long userId, String token) {
        // 黑名单 TTL 覆盖令牌剩余有效期即可（2h），到期自动清理
        redis.set("auth:blacklist:" + token, "1", Duration.ofHours(2));
        redis.delete("auth:refresh:" + userId);
    }

    /**
     * 刷新令牌换发新 JWT（PRD 7.3.2；接口文档 3.1 #3）。
     *
     * @param userId       用户 id（网关/调用方解析自旧 JWT 或请求头）
     * @param refreshToken 客户端持有的刷新令牌
     * @return 新 token 与有效期
     * @throws BizException 3005 刷新令牌无效或不匹配
     */
    public Map<String, Object> refresh(Long userId, String refreshToken) {
        String saved = redis.get("auth:refresh:" + userId);
        if (saved == null || !saved.equals(refreshToken)) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "刷新令牌无效或已过期");
        }
        AppUser user = userMapper.selectById(userId);
        if (user == null || "disabled".equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        Role role = roleMapper.selectById(user.getRoleId());
        return Map.of("token", jwtUtils.generate(userId, role.getRoleKey()), "expiresIn", 7200);
    }

    /**
     * 修改密码：验旧 → 更新哈希 → 旧令牌全失效（黑名单 + 删刷新令牌）→ 取消强制改密标记。
     *
     * @param userId      当前用户 id
     * @param oldPassword 旧密码明文
     * @param newPassword 新密码明文（8-64 位且含字母与数字）
     * @param currentToken 当前 JWT（改密后立即拉黑）
     * @throws BizException 1001 新密码不合规 / 3002 旧密码错误
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword, String currentToken) {
        // 密码规则：8-64 位且同时含字母与数字（接口文档 3.1）
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 64
                || !newPassword.matches(".*[a-zA-Z].*") || !newPassword.matches(".*\\d.*")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码须为 8-64 位且同时包含字母与数字");
        }
        AppUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "旧密码错误");
        }

        // @Transactional：哈希更新与标记清除同事务
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userMapper.updateById(user);

        // 旧令牌全部失效：当前 JWT 拉黑 + 刷新令牌删除（PRD 7.3.2）
        if (currentToken != null) {
            redis.set("auth:blacklist:" + currentToken, "1", Duration.ofHours(2));
        }
        redis.delete("auth:refresh:" + userId);
    }

    /**
     * 记录一次失败并构造应抛出的异常：未达上限返回 3002（含剩余次数）；
     * 达到上限（第 5 次）立即置锁定标记并返回 3003（PRD 7.3.1：连续失败 5 次即锁定）。
     *
     * @param account 账号
     * @return 应抛出的业务异常
     */
    private BizException failAndBuild(String account) {
        int remaining = recordFail(account);
        if (remaining <= 0) {
            return new BizException(ErrorCode.ACCOUNT_LOCKED);
        }
        return new BizException(ErrorCode.BAD_CREDENTIALS,
                "账号或密码错误", Map.of("remainingAttempts", remaining));
    }

    /**
     * 记录一次登录失败；达到上限设置锁定标记。
     *
     * @param account 账号
     * @return 剩余可尝试次数（已锁定时为 0）
     */
    private int recordFail(String account) {
        String key = "auth:fail:" + account;
        // setIfAbsent 建计数起点（TTL 15 分钟滑窗），再自增
        redis.setIfAbsent(key, "0", LOCK_TTL);
        Long fails = redis.increment(key);
        if (fails != null && fails >= MAX_FAIL) {
            redis.set("auth:lock:" + account, "1", LOCK_TTL);
            redis.delete(key);
            return 0;
        }
        return (int) (MAX_FAIL - (fails == null ? 1 : fails));
    }

    /**
     * 生成刷新令牌（URL 安全的随机串）。
     *
     * @return 刷新令牌
     */
    static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
