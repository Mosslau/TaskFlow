package com.taskflow.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * OpenAPI 接入密钥表 api_key（接口设计文档第 2 章）。
 * 静态 Key 方案：哈希落库，明文仅签发时响应一次；一期仅开放 POST /task/api/v1/tasks。
 */
@TableName("api_key")
public class ApiKey {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调用方名称，如"运维监控系统" */
    private String name;

    /** 绑定的专用服务账号 id（FK → app_user.id，角色固定 taskAdmin） */
    private Long userId;

    /** API Key 哈希（SHA-256；明文不落库） */
    // 哈希永不返回给前端
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String keyHash;

    /** 明文前 8 位（如 tfk_a1b2），列表展示与排查用 */
    private String keyPrefix;

    /** 状态：active 有效 / disabled 停用（停用即 401） */
    private String status;

    /** 最近使用时间（回源鉴权时刷新） */
    private OffsetDateTime lastUsedAt;

    /** 签发时间（UTC） */
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
