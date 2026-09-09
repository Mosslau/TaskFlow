package com.taskflow.stats.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 人员负载当前值表（PRD 4.3.3 人员负载图；事件驱动 ±1）。
 */
@TableName("stats_assignee_load")
public class StatsAssigneeLoad {

    /** 处理人（逻辑引用 app_user.id；查询时排除 admin 角色） */
    @TableId
    private Long assigneeId;

    /** 名下未完成（待办+进行中+待验收）任务数 */
    private Long unfinishedCount;

    /** 最后更新时间（UTC） */
    private OffsetDateTime updatedAt;

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public Long getUnfinishedCount() {
        return unfinishedCount;
    }

    public void setUnfinishedCount(Long unfinishedCount) {
        this.unfinishedCount = unfinishedCount;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
