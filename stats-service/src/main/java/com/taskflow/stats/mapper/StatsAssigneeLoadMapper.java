package com.taskflow.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.stats.entity.StatsAssigneeLoad;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 人员负载当前值 Mapper */
@Mapper
public interface StatsAssigneeLoadMapper extends BaseMapper<StatsAssigneeLoad> {

    /**
     * 负载增量 UPSERT：无行则建行，有行则 unfinished_count += 增量。
     * GREATEST(0, ...) 防负兜底（转派事件不携带任务状态，完成态任务转派等边界
     * 可能造成漂移，最终一致性由 rebuild 兜底）。
     *
     * @param assigneeId 处理人 id
     * @param delta      unfinished_count 增量（可负）
     * @return 影响行数
     */
    @Insert("INSERT INTO stats_assignee_load (assignee_id, unfinished_count, updated_at)"
            + " VALUES (#{assigneeId}, #{delta}, now())"
            + " ON CONFLICT (assignee_id) DO UPDATE SET"
            + " unfinished_count = GREATEST(0, stats_assignee_load.unfinished_count + EXCLUDED.unfinished_count),"
            + " updated_at = now()")
    int upsertDelta(@Param("assigneeId") Long assigneeId, @Param("delta") long delta);

    /** 清空人员负载表（rebuild 用） */
    @Update("TRUNCATE TABLE stats_assignee_load")
    void truncate();
}
