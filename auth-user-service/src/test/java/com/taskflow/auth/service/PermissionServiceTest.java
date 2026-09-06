package com.taskflow.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.auth.entity.Role;
import com.taskflow.auth.entity.RolePermission;
import com.taskflow.auth.mapper.RoleMapper;
import com.taskflow.auth.mapper.RolePermissionMapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.RedisUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PermissionService 单元测试（Mockito 全 Mock）。
 * 覆盖：admin 锁定项保护（PRD 4.5.4）、无变化不写库、变更写审计 + 缓存失效。
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    private RoleMapper roleMapper;
    @Mock
    private RolePermissionMapper rolePermissionMapper;
    @Mock
    private AuditService auditService;
    @Mock
    private RedisUtils redis;

    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(roleMapper, rolePermissionMapper, auditService, redis);
    }

    /** 造角色与权限单元格的通用 stub */
    private void stubRoleAndCell(String roleKey, String permissionKey, boolean currentEnabled) {
        Role role = new Role();
        role.setId(1L);
        role.setRoleKey(roleKey);
        lenient().when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(role);

        RolePermission cell = new RolePermission();
        cell.setId(10L);
        cell.setRoleId(1L);
        cell.setPermissionKey(permissionKey);
        cell.setEnabled(currentEnabled);
        lenient().when(rolePermissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cell);
    }

    @Test
    @DisplayName("admin 的 manageUser/setPerm 关闭被拒绝（3009）")
    void adminLockProtected() {
        BizException e = assertThrows(BizException.class,
                () -> permissionService.updateMatrixCell(1L, "admin", "manageUser", false));
        assertEquals(ErrorCode.ADMIN_PERM_LOCKED, e.getErrorCode());
        // 锁定项在查库前就拦截
        verify(rolePermissionMapper, never()).updateById(any(RolePermission.class));
    }

    @Test
    @DisplayName("开关值无变化：不写库、不写审计、不清缓存")
    void noChangeNoOp() {
        stubRoleAndCell("user", "viewAssigned", true);

        permissionService.updateMatrixCell(1L, "user", "viewAssigned", true);

        verify(rolePermissionMapper, never()).updateById(any(RolePermission.class));
        verify(auditService, never()).record(any(), anyString(), anyString());
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("有效变更：更新单元格 + 写审计日志 + 失效角色权限缓存")
    void validChangeSideEffects() {
        stubRoleAndCell("taskAdmin", "viewAll", false);

        permissionService.updateMatrixCell(99L, "taskAdmin", "viewAll", true);

        verify(rolePermissionMapper).updateById(any(RolePermission.class));
        verify(auditService).record(eq(99L), eq("permission.matrix.update"), anyString());
        verify(redis).delete("auth:perms:taskAdmin");
    }

    @Test
    @DisplayName("不存在的权限点返回 1002")
    void unknownPermissionKey() {
        Role role = new Role();
        role.setId(1L);
        role.setRoleKey("user");
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(role);
        when(rolePermissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> permissionService.updateMatrixCell(1L, "user", "notExists", true));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, e.getErrorCode());
    }
}
