package com.taskflow.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.task.entity.EventOutbox;
import org.apache.ibatis.annotations.Mapper;

/** event_outbox 表访问层。 */
@Mapper
public interface EventOutboxMapper extends BaseMapper<EventOutbox> {
}
