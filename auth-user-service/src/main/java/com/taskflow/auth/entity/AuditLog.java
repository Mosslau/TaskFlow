package com.taskflow.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.auth.config.StringJsonbTypeHandler;

import java.time.OffsetDateTime;

/**
 * 审计日志表 audit_log（只增不改，PRD 4.5.4）。
 * 记录权限矩阵每次修改的操作人、时间、变更前后差异。
 */
// autoResultMap=true：让 @TableField 指定的 typeHandler 在查询映射时生效
@TableName(value = "audit_log", autoResultMap = true)
public class AuditLog {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 id（逻辑引用 app_user.id） */
    private Long operatorId;

    /** 操作类型，如 permission.matrix.update */
    private String action;

    /** 变更前后差异 JSON 文本；DB 为 JSONB 列，读写经 StringJsonbTypeHandler 转换 */
    @TableField(typeHandler = StringJsonbTypeHandler.class)
    private String changeDetail;

    /** 操作时间（UTC） */
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getChangeDetail() { return changeDetail; }
    public void setChangeDetail(String changeDetail) { this.changeDetail = changeDetail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
