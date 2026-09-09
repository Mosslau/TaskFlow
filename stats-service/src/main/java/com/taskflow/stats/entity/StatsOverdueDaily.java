package com.taskflow.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;

/**
 * 逾期任务日快照（决策基线 #7）：由每日 task.overdue 扫描事件累积，误差 ≤ 24 小时。
 */
@TableName("stats_overdue_daily")
public class StatsOverdueDaily {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 快照日（每日 09:00 扫描产生） */
    private LocalDate snapshotDate;

    /** 逾期任务的创建日（支撑 KPI 区间过滤） */
    private LocalDate taskCreatedDate;

    /** 该快照日、该创建日的逾期任务数 */
    private Long overdueCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }

    public void setSnapshotDate(LocalDate snapshotDate) {
        this.snapshotDate = snapshotDate;
    }

    public LocalDate getTaskCreatedDate() {
        return taskCreatedDate;
    }

    public void setTaskCreatedDate(LocalDate taskCreatedDate) {
        this.taskCreatedDate = taskCreatedDate;
    }

    public Long getOverdueCount() {
        return overdueCount;
    }

    public void setOverdueCount(Long overdueCount) {
        this.overdueCount = overdueCount;
    }
}
