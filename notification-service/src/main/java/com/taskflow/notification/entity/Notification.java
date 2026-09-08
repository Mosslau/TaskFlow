package com.taskflow.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 站内消息（PRD 4.6.2；保留 180 天，过期定时清理——清理任务属 M5）。
 */
@TableName("notification")
public class Notification {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人（逻辑引用 app_user.id） */
    private Long recipientId;

    /** 触发事件（task.assigned 等，PRD 4.6.1） */
    private String eventType;

    /** 消息摘要 */
    private String summary;

    /** 关联任务 id（点击跳转详情用，可空） */
    private Long taskId;

    /** 关联任务编号 TSK-xxx（列表展示与跳转用，可空；V2 迁移新增列） */
    private String taskNo;

    /** 已读标记（未读数角标按此统计） */
    private Boolean isRead;

    /** 消息时间（UTC） */
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(Long recipientId) {
        this.recipientId = recipientId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getTaskNo() {
        return taskNo;
    }

    public void setTaskNo(String taskNo) {
        this.taskNo = taskNo;
    }

    public Boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(Boolean isRead) {
        this.isRead = isRead;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
