package com.taskflow.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.auth.entity.Role;
import com.taskflow.auth.entity.RolePermission;
import com.taskflow.auth.mapper.RoleMapper;
import com.taskflow.auth.mapper.RolePermissionMapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.RedisUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限矩阵服务（PRD 3.2-3.3 / 4.5.4）。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>「角色 → 已开启权限点集合」查询，带 Redis 缓存（键 {@code auth:perms:{roleKey}}）；
 *       权限点校验在各服务本地完成，缓存是性能与即时生效的关键（架构 4.1）</li>
 *   <li>矩阵读写：即改即存、admin 锁定项保护、变更写审计日志、变更后主动失效缓存</li>
 * </ul>
 */
@Service
public class PermissionService {

    /** 缓存 10 分钟兜底过期（正常靠主动失效，TTL 防缓存永久残留） */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** 14 个权限点目录（PRD 3.2）：key / 名称 / 分组 */
    public static final List<Map<String, String>> PERMISSION_CATALOG = List.of(
            Map.of("key", "viewAll", "name", "查看全部任务", "group", "任务"),
            Map.of("key", "create", "name", "创建任务", "group", "任务"),
            Map.of("key", "editOwn", "name", "编辑自己的任务", "group", "任务"),
            Map.of("key", "deleteOwn", "name", "删除自己的任务", "group", "任务"),
            Map.of("key", "transferOwn", "name", "转派自己的任务", "group", "任务"),
            Map.of("key", "prioOwn", "name", "调整自己任务优先级", "group", "任务"),
            Map.of("key", "dueOwn", "name", "调整自己任务到期时间", "group", "任务"),
            Map.of("key", "viewAssigned", "name", "查看指派给我的", "group", "任务"),
            Map.of("key", "updateAssigned", "name", "更新指派给我的进度", "group", "任务"),
            Map.of("key", "transferAssigned", "name", "转派指派给我的", "group", "任务"),
            Map.of("key", "viewStats", "name", "查看统计总览", "group", "数据"),
            Map.of("key", "exportData", "name", "导出数据", "group", "数据"),
            Map.of("key", "manageUser", "name", "用户与角色管理", "group", "系统"),
            Map.of("key", "setPerm", "name", "配置权限矩阵", "group", "系统"));

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final AuditService auditService;
    private final RedisUtils redis;

    public PermissionService(RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper,
                             AuditService auditService, RedisUtils redis) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.auditService = auditService;
        this.redis = redis;
    }

    /**
     * 查角色已开启的权限点集合（先查 Redis 缓存，未命中回源 DB 并回填）。
     *
     * @param roleKey 角色键
     * @return 已开启的权限点集合
     * @throws BizException 3001 角色不存在
     */
    public Set<String> getEnabledPermissions(String roleKey) {
        String cacheKey = "auth:perms:" + roleKey;
        String cached = redis.get(cacheKey);
        if (cached != null) {
            return cached.isEmpty() ? Set.of() : Set.of(cached.split(","));
        }
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getRoleKey, roleKey));
        if (role == null) {
            throw new BizException(ErrorCode.PERMISSION_DENIED, "角色不存在：" + roleKey);
        }
        Set<String> perms = rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<RolePermission>()
                                .eq(RolePermission::getRoleId, role.getId())
                                .eq(RolePermission::getEnabled, true))
                .stream().map(RolePermission::getPermissionKey).collect(Collectors.toSet());
        // 空集合缓存为空串，防止缓存穿透反复查库
        redis.set(cacheKey, String.join(",", perms), CACHE_TTL);
        return perms;
    }

    /**
     * 读取完整权限矩阵（3 角色 × 14 权限点）。
     *
     * @return [{ roleKey, permissions: {权限点: 开关} }]
     */
    public List<Map<String, Object>> getMatrix() {
        List<Role> roles = roleMapper.selectList(null);
        List<RolePermission> all = rolePermissionMapper.selectList(null);
        return roles.stream().map(role -> {
            Map<String, Boolean> perms = all.stream()
                    .filter(rp -> rp.getRoleId().equals(role.getId()))
                    .collect(Collectors.toMap(RolePermission::getPermissionKey, RolePermission::getEnabled));
            return Map.<String, Object>of("roleKey", role.getRoleKey(), "permissions", perms);
        }).collect(Collectors.toList());
    }

    /**
     * 修改矩阵单格（即改即存，PRD 4.5.4）。
     *
     * <p>防护与副作用：admin 的 manageUser/setPerm 不可关闭（3009）；
     * 变更写审计日志（变更前后值）；主动失效该角色的权限缓存（下一请求即生效）。</p>
     *
     * @param operatorId    操作人 id（写审计日志）
     * @param roleKey       目标角色
     * @param permissionKey 目标权限点
     * @param enabled       新开关值
     * @throws BizException 3009 admin 锁定项 / 1002 角色或权限点不存在
     */
    public void updateMatrixCell(Long operatorId, String roleKey, String permissionKey, boolean enabled) {
        // admin 的 manageUser / setPerm 锁定为开启（PRD 4.5.4：防系统失去管理入口）
        if ("admin".equals(roleKey)
                && ("manageUser".equals(permissionKey) || "setPerm".equals(permissionKey))
                && !enabled) {
            throw new BizException(ErrorCode.ADMIN_PERM_LOCKED);
        }
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getRoleKey, roleKey));
        if (role == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "角色不存在：" + roleKey);
        }
        RolePermission cell = rolePermissionMapper.selectOne(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, role.getId())
                .eq(RolePermission::getPermissionKey, permissionKey));
        if (cell == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "权限点不存在：" + permissionKey);
        }
        boolean oldValue = Boolean.TRUE.equals(cell.getEnabled());
        if (oldValue == enabled) {
            return; // 无变化：不写库、不写审计
        }
        cell.setEnabled(enabled);
        rolePermissionMapper.updateById(cell);

        // 审计日志（PRD 4.5.4：操作人、时间、变更前后值）
        auditService.record(operatorId, "permission.matrix.update",
                "{\"" + permissionKey + "\":{\"" + roleKey + "\":[" + oldValue + "," + enabled + "]}}");

        // 缓存主动失效：目标角色下一次请求即按新矩阵鉴权（PRD 验收条件）
        redis.delete("auth:perms:" + roleKey);
    }
}
