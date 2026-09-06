package com.taskflow.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 用户表 app_user（库表设计文档第 3 章）。
 * 用户不可物理删除，停用走 status（PRD 4.5.1）。
 */
// @TableName：MyBatis-Plus 实体与表的映射声明（类名 AppUser ≠ 表名 app_user，必须显式指定）
@TableName("app_user")
public class AppUser {

    /** 主键，数据库 IDENTITY 自增 */
    // @TableId(type = AUTO)：主键交给数据库自增列生成，插入后回填到该字段
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（唯一，字母数字下划线 4-32 位，DB CHECK 兜底） */
    private String account;

    /** 姓名 */
    private String name;

    /** BCrypt 密码哈希（永不返回给前端，序列化时忽略） */
    // @com.fasterxml.jackson.annotation.JsonIgnore：防止任何接口误把密码哈希泄露到响应
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String passwordHash;

    /** 邮箱（通知邮件接收地址） */
    private String email;

    /** 归属部门 id（FK → department.id） */
    private Long departmentId;

    /** 绑定角色 id（FK → role.id，每用户仅一个角色，PRD 4.5.3） */
    private Long roleId;

    /** 在职状态：active 在职 / disabled 停用（禁止登录） */
    private String status;

    /** 下次登录强制改密（新增用户与重置密码后为 TRUE） */
    private Boolean mustChangePassword;

    /** 创建时间（UTC） */
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(Boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
