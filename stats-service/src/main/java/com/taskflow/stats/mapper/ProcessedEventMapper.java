package com.taskflow.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.stats.entity.ProcessedEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 消费幂等去重 Mapper */
@Mapper
public interface ProcessedEventMapper extends BaseMapper<ProcessedEvent> {

    /**
     * 幂等登记：事件已消费过（event_id 冲突）则返回 0，调用方直接跳过。
     *
     * @param eventId  事件 ID（UUID 文本）
     * @param consumer 消费者标识
     * @return 插入行数（0 = 重复事件）
     */
    @Insert("INSERT INTO processed_event (event_id, consumer, processed_at) "
            + "VALUES (#{eventId}::uuid, #{consumer}, now()) "
            + "ON CONFLICT (event_id) DO NOTHING")
    int insertIgnore(@Param("eventId") String eventId, @Param("consumer") String consumer);
}
