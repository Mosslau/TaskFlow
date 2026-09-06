package com.taskflow.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 任务主表 task（库表设计文档第 4 章；PRD 4.1.1）。
 * 子任务同表，parent_id 指向顶层任务（仅一级嵌套，DB 触发器兜底）。
 */
@TableName("task")
public class Task {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务编号：TSK- + task_no_seq，全局唯一不复用 */
    private String taskNo;

    /** 标题（1-100 字符） */
    private String title;

    /** 描述（≤ 2000 字符） */
    private String description;

    /** 任务类型（6 枚举；子任务继承父任务） */
    private String taskType;

    /** 优先级：P0/P1/P2/P3 */
    private String priority;

    /** 状态机：new 待办 / doing 进行中 / wait 待验收 / done 已完成 / close 已归档 */
    private String status;

    /** 创建人 id（逻辑引用 auth_user_db.app_user） */
    private Long creatorId;

    /** 处理人 id（逻辑引用；taskAdmin/user 角色的在职用户） */
    private Long assigneeId;

    /** 到期时间（精确到分钟） */
    private OffsetDateTime dueAt;

    /** 进度 0-100，步进 5 */
    private Integer progress;

    /** 来源渠道：网页 / Excel 导入 / OpenAPI */
    private String source;

    /** 父任务 id（顶层任务为 NULL） */
    private Long parentId;

    /** 到期前 24h 提醒已发标记 */
    private Boolean dueReminded;

    /** 创建时间（UTC） */
    private OffsetDateTime createdAt;

    /** 更新时间（状态或字段变更刷新；完成时长与按时率以此计） */
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; }
    public OffsetDateTime getDueAt() { return dueAt; }
    public void setDueAt(OffsetDateTime dueAt) { this.dueAt = dueAt; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Boolean getDueReminded() { return dueReminded; }
    public void setDueReminded(Boolean dueReminded) { this.dueReminded = dueReminded; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
