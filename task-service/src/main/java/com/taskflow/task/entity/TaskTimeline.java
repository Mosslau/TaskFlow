package com.taskflow.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 操作时间线 task_timeline（只增不改，PRD 4.1.4）。
 */
@TableName("task_timeline")
public class TaskTimeline {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务 id */
    private Long taskId;

    /** 操作人 id（逻辑引用 app_user；自动归档记系统操作 0） */
    private Long operatorId;

    /** 动作：创建/受理/更新进度/转派/调整优先级/调整到期/提交验收/验收通过/验收驳回/手动归档/自动归档 */
    private String action;

    /** 备注：进展说明 / 驳回原因 / 转派说明等 */
    private String note;

    /** 操作时间（UTC） */
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
