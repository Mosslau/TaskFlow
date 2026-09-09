package com.taskflow.stats.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 按任务完成日（进入 done/close 的日期）的日粒度完成聚合表（V2，PRD 趋势 completed 数据源）。
 * 只计顶层任务；事件驱动增量 ±1（stats_completion_daily），rebuild 全量重算。
 */
@TableName("stats_completion_daily")
public class StatsCompletionDaily {

    /** 完成日 = 进入终态(done/close)的日期 */
    private LocalDate completeDate;

    /** 当日进入终态（done/close）的任务数 */
    private Long completed;

    /** 当日完成任务完成时长合计（小时，1 位小数）= 完成时刻 - 创建时间 */
    private BigDecimal hoursSum;

    /** 当日完成任务中按时完成数（完成时刻 ≤ 到期时间） */
    private Long ontimeCount;

    public LocalDate getCompleteDate() {
        return completeDate;
    }

    public void setCompleteDate(LocalDate completeDate) {
        this.completeDate = completeDate;
    }

    public Long getCompleted() {
        return completed;
    }

    public void setCompleted(Long completed) {
        this.completed = completed;
    }

    public BigDecimal getHoursSum() {
        return hoursSum;
    }

    public void setHoursSum(BigDecimal hoursSum) {
        this.hoursSum = hoursSum;
    }

    public Long getOntimeCount() {
        return ontimeCount;
    }

    public void setOntimeCount(Long ontimeCount) {
        this.ontimeCount = ontimeCount;
    }
}
