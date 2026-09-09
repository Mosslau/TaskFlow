package com.taskflow.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 任务评论 task_comment（PRD 4.1.5；可删不可改，不写入操作时间线）。
 */
@TableName("task_comment")
public class TaskComment {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务 id（子任务同表支持） */
    private Long taskId;

    /** 评论人 id（逻辑引用 app_user） */
    private Long commenterId;

    /** 评论内容，纯文本 1-500 字符 */
    private String content;

    /** 评论时间（UTC；按时间正序展示） */
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getCommenterId() { return commenterId; }
    public void setCommenterId(Long commenterId) { this.commenterId = commenterId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
