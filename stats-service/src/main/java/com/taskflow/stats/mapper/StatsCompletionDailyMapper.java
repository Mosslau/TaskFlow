package com.taskflow.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.stats.entity.StatsCompletionDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 完成日聚合 Mapper（V2）：UPSERT 增量 + 全量重算写入 */
@Mapper
public interface StatsCompletionDailyMapper extends BaseMapper<StatsCompletionDaily> {

    /**
     * 完成日聚合增量 UPSERT：无行则建行，有行则各计数列 += 增量。
     * 只计顶层任务进入终态（from∈未完成 → to∈done/close）的那一次。
     *
     * @param completeDate 完成日
     * @param completed    completed 增量
     * @param hours        hours_sum 增量（小时）
     * @param ontime       ontime_count 增量
     * @return 影响行数
     */
    @Insert("INSERT INTO stats_completion_daily (complete_date, completed, hours_sum, ontime_count)"
            + " VALUES (#{completeDate}, #{completed}, #{hours}, #{ontime})"
            + " ON CONFLICT (complete_date) DO UPDATE SET"
            + " completed = stats_completion_daily.completed + EXCLUDED.completed,"
            + " hours_sum = stats_completion_daily.hours_sum + EXCLUDED.hours_sum,"
            + " ontime_count = stats_completion_daily.ontime_count + EXCLUDED.ontime_count")
    int upsertDelta(@Param("completeDate") LocalDate completeDate,
                    @Param("completed") long completed,
                    @Param("hours") BigDecimal hours,
                    @Param("ontime") long ontime);

    /** 清空完成日聚合表（rebuild 用） */
    @Update("TRUNCATE TABLE stats_completion_daily")
    void truncate();
}
