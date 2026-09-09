package com.taskflow.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.stats.entity.StatsTaskDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** 按创建日日聚合 Mapper：UPSERT 增量 + 区间汇总查询 */
@Mapper
public interface StatsTaskDailyMapper extends BaseMapper<StatsTaskDaily> {

    /**
     * 日聚合增量 UPSERT：无行则建行，有行则各计数列 += 增量。
     * 所有聚合写入（事件消费与 rebuild）统一走此方法，保证口径一致。
     *
     * @param statDate 统计日（任务创建日）
     * @param total    total_count 增量
     * @param newCount new_count 增量
     * @param doing    doing_count 增量
     * @param wait     wait_count 增量
     * @param done     done_count 增量
     * @param close    close_count 增量
     * @param p0       p0_count 增量
     * @param p1       p1_count 增量
     * @param p2       p2_count 增量
     * @param p3       p3_count 增量
     * @param completed completed_count 增量
     * @param hours    completed_hours_sum 增量（小时）
     * @param ontime   ontime_count 增量
     * @return 影响行数
     */
    @Insert("INSERT INTO stats_task_daily (stat_date, total_count, new_count, doing_count, wait_count,"
            + " done_count, close_count, p0_count, p1_count, p2_count, p3_count,"
            + " completed_count, completed_hours_sum, ontime_count)"
            + " VALUES (#{statDate}, #{total}, #{newCount}, #{doing}, #{wait},"
            + " #{done}, #{close}, #{p0}, #{p1}, #{p2}, #{p3}, #{completed}, #{hours}, #{ontime})"
            + " ON CONFLICT (stat_date) DO UPDATE SET"
            + " total_count = stats_task_daily.total_count + EXCLUDED.total_count,"
            + " new_count = stats_task_daily.new_count + EXCLUDED.new_count,"
            + " doing_count = stats_task_daily.doing_count + EXCLUDED.doing_count,"
            + " wait_count = stats_task_daily.wait_count + EXCLUDED.wait_count,"
            + " done_count = stats_task_daily.done_count + EXCLUDED.done_count,"
            + " close_count = stats_task_daily.close_count + EXCLUDED.close_count,"
            + " p0_count = stats_task_daily.p0_count + EXCLUDED.p0_count,"
            + " p1_count = stats_task_daily.p1_count + EXCLUDED.p1_count,"
            + " p2_count = stats_task_daily.p2_count + EXCLUDED.p2_count,"
            + " p3_count = stats_task_daily.p3_count + EXCLUDED.p3_count,"
            + " completed_count = stats_task_daily.completed_count + EXCLUDED.completed_count,"
            + " completed_hours_sum = stats_task_daily.completed_hours_sum + EXCLUDED.completed_hours_sum,"
            + " ontime_count = stats_task_daily.ontime_count + EXCLUDED.ontime_count")
    int upsertDelta(@Param("statDate") LocalDate statDate,
                    @Param("total") long total,
                    @Param("newCount") long newCount,
                    @Param("doing") long doing,
                    @Param("wait") long wait,
                    @Param("done") long done,
                    @Param("close") long close,
                    @Param("p0") long p0,
                    @Param("p1") long p1,
                    @Param("p2") long p2,
                    @Param("p3") long p3,
                    @Param("completed") long completed,
                    @Param("hours") BigDecimal hours,
                    @Param("ontime") long ontime);

    /**
     * 区间汇总（KPI / 分布图数据源）：start/end 均可空（null = 不限，即历史累计）。
     * CAST 解决 PostgreSQL 对 NULL 参数的类型推断问题。
     *
     * @param start 起始日（含），可空
     * @param end   截止日（含），可空
     * @return 各计数列的 SUM（无数据时 0）
     */
    @Select("SELECT COALESCE(SUM(total_count),0) AS total,"
            + " COALESCE(SUM(new_count),0) AS todo,"
            + " COALESCE(SUM(doing_count),0) AS doing,"
            + " COALESCE(SUM(wait_count),0) AS waiting,"
            + " COALESCE(SUM(done_count),0) AS done,"
            + " COALESCE(SUM(close_count),0) AS close,"
            + " COALESCE(SUM(p0_count),0) AS p0,"
            + " COALESCE(SUM(p1_count),0) AS p1,"
            + " COALESCE(SUM(p2_count),0) AS p2,"
            + " COALESCE(SUM(p3_count),0) AS p3,"
            + " COALESCE(SUM(completed_count),0) AS completed,"
            + " COALESCE(SUM(completed_hours_sum),0) AS hours,"
            + " COALESCE(SUM(ontime_count),0) AS ontime"
            + " FROM stats_task_daily"
            + " WHERE (CAST(#{start} AS DATE) IS NULL OR stat_date >= CAST(#{start} AS DATE))"
            + " AND (CAST(#{end} AS DATE) IS NULL OR stat_date <= CAST(#{end} AS DATE))")
    Map<String, Object> sumRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** @return 最早统计日（历史累计区间的展示下界），无数据返回 null */
    @Select("SELECT MIN(stat_date) FROM stats_task_daily")
    LocalDate minStatDate();

    /** 清空日聚合表（rebuild 用） */
    @Update("TRUNCATE TABLE stats_task_daily")
    void truncate();
}
