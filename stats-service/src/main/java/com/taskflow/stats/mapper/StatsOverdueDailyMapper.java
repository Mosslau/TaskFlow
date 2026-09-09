package com.taskflow.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.stats.entity.StatsOverdueDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/** 逾期任务日快照 Mapper */
@Mapper
public interface StatsOverdueDailyMapper extends BaseMapper<StatsOverdueDaily> {

    /**
     * 逾期快照累积 UPSERT：同一快照日 + 创建日已存在则 overdue_count += 增量。
     *
     * @param snapshotDate 快照日
     * @param createdDate  任务创建日
     * @param delta        overdue_count 增量
     * @return 影响行数
     */
    @Insert("INSERT INTO stats_overdue_daily (snapshot_date, task_created_date, overdue_count)"
            + " VALUES (#{snapshotDate}, #{createdDate}, #{delta})"
            + " ON CONFLICT (snapshot_date, task_created_date) DO UPDATE SET"
            + " overdue_count = stats_overdue_daily.overdue_count + EXCLUDED.overdue_count")
    int upsertDelta(@Param("snapshotDate") LocalDate snapshotDate,
                    @Param("createdDate") LocalDate createdDate,
                    @Param("delta") long delta);

    /** @return 最新快照日（KPI 逾期取最新日快照，决策基线 #7），无数据返回 null */
    @Select("SELECT MAX(snapshot_date) FROM stats_overdue_daily")
    LocalDate maxSnapshotDate();

    /**
     * 指定快照日的逾期数（按创建日区间过滤，start/end 可空）。
     *
     * @param snapshotDate 快照日
     * @param start        创建日起（含），可空
     * @param end          创建日止（含），可空
     * @return 逾期任务数
     */
    @Select("SELECT COALESCE(SUM(overdue_count),0) FROM stats_overdue_daily"
            + " WHERE snapshot_date = #{snapshotDate}"
            + " AND (CAST(#{start} AS DATE) IS NULL OR task_created_date >= CAST(#{start} AS DATE))"
            + " AND (CAST(#{end} AS DATE) IS NULL OR task_created_date <= CAST(#{end} AS DATE))")
    long sumOverdue(@Param("snapshotDate") LocalDate snapshotDate,
                    @Param("start") LocalDate start,
                    @Param("end") LocalDate end);

    /** 清空逾期快照表（rebuild 用） */
    @Update("TRUNCATE TABLE stats_overdue_daily")
    void truncate();
}
