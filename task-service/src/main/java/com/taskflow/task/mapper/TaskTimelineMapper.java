package com.taskflow.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.task.entity.TaskTimeline;
import org.apache.ibatis.annotations.Mapper;

/** task_timeline 表访问层（只增不改：业务代码只调 insert / select）。 */
@Mapper
public interface TaskTimelineMapper extends BaseMapper<TaskTimeline> {
}
