package com.taskflow.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 任务附件元数据 task_attachment（PRD 4.1.6）。
 * 文件本体存服务端本地文件系统（架构文档 2.4），落盘文件名为 UUID，防路径穿越。
 */
@TableName("task_attachment")
public class TaskAttachment {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务 id（独立计数 ≤ 10 个） */
    private Long taskId;

    /** 原始文件名（下载按此名返回） */
    private String originalName;

    /** 落盘文件名（UUID，防路径穿越） */
    private String storedName;

    /** 文件大小（字节，单文件 ≤ 20MB） */
    private Long sizeBytes;

    /** 上传人 id（逻辑引用 app_user；删除限本人或 admin） */
    private Long uploaderId;

    /** 上传时间（UTC） */
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Long getUploaderId() { return uploaderId; }
    public void setUploaderId(Long uploaderId) { this.uploaderId = uploaderId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
