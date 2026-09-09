package com.taskflow.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 按任务创建日的日粒度聚合表（库表设计文档第 6 章）。
 * 只计顶层任务；事件驱动增量 ±1（stats_task_daily）。
 */
@TableName("stats_task_daily")
public class StatsTaskDaily {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 统计日 = 任务创建日（PRD 4.3.1 区间过滤口径） */
    private LocalDate statDate;

    /** 当日创建任务总数 */
    private Long totalCount;

    /** 当日创建任务中当前为待办的数量 */
    private Long newCount;

    /** 当日创建任务中当前为进行中的数量 */
    private Long doingCount;

    /** 当日创建任务中当前为待验收的数量 */
    private Long waitCount;

    /** 当日创建任务中当前为已完成的数量 */
    private Long doneCount;

    /** 当日创建任务中当前为已归档的数量 */
    private Long closeCount;

    /** 当日创建的 P0 任务数（P1-P3 同理） */
    private Long p0Count;
    private Long p1Count;
    private Long p2Count;
    private Long p3Count;

    /** 当日创建且已完成/已归档的任务数 */
    private Long completedCount;

    /** 已完成任务的完成时长合计（小时） */
    private BigDecimal completedHoursSum;

    /** 按时完成数（完成时间 ≤ 到期时间） */
    private Long ontimeCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public Long getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Long totalCount) {
        this.totalCount = totalCount;
    }

    public Long getNewCount() {
        return newCount;
    }

    public void setNewCount(Long newCount) {
        this.newCount = newCount;
    }

    public Long getDoingCount() {
        return doingCount;
    }

    public void setDoingCount(Long doingCount) {
        this.doingCount = doingCount;
    }

    public Long getWaitCount() {
        return waitCount;
    }

    public void setWaitCount(Long waitCount) {
        this.waitCount = waitCount;
    }

    public Long getDoneCount() {
        return doneCount;
    }

    public void setDoneCount(Long doneCount) {
        this.doneCount = doneCount;
    }

    public Long getCloseCount() {
        return closeCount;
    }

    public void setCloseCount(Long closeCount) {
        this.closeCount = closeCount;
    }

    public Long getP0Count() {
        return p0Count;
    }

    public void setP0Count(Long p0Count) {
        this.p0Count = p0Count;
    }

    public Long getP1Count() {
        return p1Count;
    }

    public void setP1Count(Long p1Count) {
        this.p1Count = p1Count;
    }

    public Long getP2Count() {
        return p2Count;
    }

    public void setP2Count(Long p2Count) {
        this.p2Count = p2Count;
    }

    public Long getP3Count() {
        return p3Count;
    }

    public void setP3Count(Long p3Count) {
        this.p3Count = p3Count;
    }

    public Long getCompletedCount() {
        return completedCount;
    }

    public void setCompletedCount(Long completedCount) {
        this.completedCount = completedCount;
    }

    public BigDecimal getCompletedHoursSum() {
        return completedHoursSum;
    }

    public void setCompletedHoursSum(BigDecimal completedHoursSum) {
        this.completedHoursSum = completedHoursSum;
    }

    public Long getOntimeCount() {
        return ontimeCount;
    }

    public void setOntimeCount(Long ontimeCount) {
        this.ontimeCount = ontimeCount;
    }
}
