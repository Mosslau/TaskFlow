package com.taskflow.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 邮件记录（PRD 4.6.3；每封邮件的收件人/主题/时间/结果落库，保留 1 年）。
 */
@TableName("mail_record")
public class MailRecord {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联任务（可空：SMTP 故障告警等不关联任务） */
    private Long taskId;

    /** 收件人邮箱 */
    private String recipient;

    /** 主题：【任务管理】<事件> <任务编号> <任务标题> */
    private String subject;

    /** 发送结果：success / failed */
    private String result;

    /** 已重试次数（指数退避，≤ 3） */
    private Integer retryCount;

    /** 发送时间（UTC） */
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
