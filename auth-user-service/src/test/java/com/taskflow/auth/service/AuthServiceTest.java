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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试（Mockito 全 Mock，不依赖 DB/Redis）。
 * 覆盖：登录锁定（PRD 7.3.1）、停用拒登、成功签发、改密规则与旧令牌失效。
 */
// @ExtendWith(MockitoExtension.class)：启用 @Mock 注解初始化
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AppUserMapper userMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private RedisUtils redis;

    /** 真实 BCrypt（encode/matches 是纯计算，不需 Mock） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    /** 真实 JwtUtils（纯计算） */
    private final JwtUtils jwtUtils = new JwtUtils("unit-test-secret", Duration.ofHours(2));

    private AuthService authService;

    /** 每个用例前组装被测对象 */
    @BeforeEach
    void setUp() {
        authService = new AuthService(userMapper, roleMapper, permissionService,
                passwordEncoder, jwtUtils, redis);
    }

    /** 造一个 active 状态的测试用户 */
    private AppUser activeUser() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setAccount("zhangsan");
        user.setName("张三");
        user.setPasswordHash(passwordEncoder.encode("Passw0rd"));
        user.setRoleId(3L);
        user.setStatus("active");
        user.setMustChangePassword(false);
        return user;
    }

    @Test
    @DisplayName("锁定中的账号直接 3003，不校验密码")
    void lockedAccountRejected() {
        when(redis.hasKey("auth:lock:zhangsan")).thenReturn(true);

        BizException e = assertThrows(BizException.class,
                () -> authService.login("zhangsan", "whatever"));
        assertEquals(ErrorCode.ACCOUNT_LOCKED, e.getErrorCode());
        // 锁定期间不查库、不验密（防探测）
        verify(userMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("连续 5 次失败后第 5 次即返回 3003（含不存在账号）")
    void fifthFailureLocks() {
        when(redis.hasKey(anyString())).thenReturn(false);
        lenient().when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        // 模拟计数器递增：1,2,3,4,5
        when(redis.increment(anyString())).thenReturn(1L, 2L, 3L, 4L, 5L);

        // 前 4 次：3002 且剩余次数递减
        for (int i = 0; i < 4; i++) {
            BizException e = assertThrows(BizException.class,
                    () -> authService.login("nobody", "wrong"));
            assertEquals(ErrorCode.BAD_CREDENTIALS, e.getErrorCode());
        }
        // 第 5 次：直接 3003
        BizException e5 = assertThrows(BizException.class,
                () -> authService.login("nobody", "wrong"));
        assertEquals(ErrorCode.ACCOUNT_LOCKED, e5.getErrorCode());
        // 锁定标记已写入
        verify(redis).set(eq("auth:lock:nobody"), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("停用账号返回 3004")
    void disabledAccountRejected() {
        when(redis.hasKey(anyString())).thenReturn(false);
        AppUser user = activeUser();
        user.setStatus("disabled");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);

        BizException e = assertThrows(BizException.class,
                () -> authService.login("zhangsan", "Passw0rd"));
        assertEquals(ErrorCode.ACCOUNT_DISABLED, e.getErrorCode());
    }

    @Test
    @DisplayName("登录成功：签发令牌、清失败计数、返回权限点")
    @SuppressWarnings("unchecked")
    void loginSuccess() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(activeUser());
        Role role = new Role();
        role.setId(3L);
        role.setRoleKey("user");
        when(roleMapper.selectById(3L)).thenReturn(role);
        when(permissionService.getEnabledPermissions("user"))
                .thenReturn(java.util.Set.of("viewAssigned", "updateAssigned", "transferAssigned"));

        Map<String, Object> result = authService.login("zhangsan", "Passw0rd");

        assertNotNull(result.get("token"));
        assertNotNull(result.get("refreshToken"));
        Map<String, Object> user = (Map<String, Object>) result.get("user");
        assertEquals("user", user.get("roleKey"));
        // 成功登录清除失败计数
        verify(redis).delete("auth:fail:zhangsan");
    }

    @Test
    @DisplayName("改密：新密码不合规 1001 / 旧密码错误 3002")
    void changePasswordRules() {
        when(userMapper.selectById(1L)).thenReturn(activeUser());

        // 纯数字不合规
        assertThrows(BizException.class,
                () -> authService.changePassword(1L, "Passw0rd", "12345678", null));
        // 太短不合规
        assertThrows(BizException.class,
                () -> authService.changePassword(1L, "Passw0rd", "Ab1", null));
        // 旧密码错误
        BizException e = assertThrows(BizException.class,
                () -> authService.changePassword(1L, "WrongPass1", "NewPass123", null));
        assertEquals(ErrorCode.BAD_CREDENTIALS, e.getErrorCode());
        // 失败场景均未写库
        verify(userMapper, never()).updateById(any(AppUser.class));
    }

    @Test
    @DisplayName("改密成功：更新哈希、取消强制改密、旧令牌拉黑、刷新令牌删除")
    void changePasswordSuccess() {
        when(userMapper.selectById(1L)).thenReturn(activeUser());

        authService.changePassword(1L, "Passw0rd", "NewPass123", "old-token");

        verify(userMapper).updateById(any(AppUser.class));
        verify(redis).set(eq("auth:blacklist:old-token"), eq("1"), any(Duration.class));
        verify(redis).delete("auth:refresh:1");
    }
}
