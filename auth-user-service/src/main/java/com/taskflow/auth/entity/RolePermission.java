package com.taskflow.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 角色权限矩阵表 role_permission（3 角色 × 14 权限点，PRD 3.2/4.5.4）。
 */
@TableName("role_permission")
public class RolePermission {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色 id（FK → role.id） */
    private Long roleId;

    /** 权限点键（14 个之一，PRD 3.2） */
    private String permissionKey;

    /** 开关：TRUE 授予 / FALSE 收回 */
    private Boolean enabled;

    /** 创建时间（UTC） */
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
    public String getPermissionKey() { return permissionKey; }
    public void setPermissionKey(String permissionKey) { this.permissionKey = permissionKey; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
