package com.taskflow.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.task.entity.TaskComment;
import org.apache.ibatis.annotations.Mapper;

/**
 * task_comment 表访问层。
 */
@Mapper
public interface TaskCommentMapper extends BaseMapper<TaskComment> {
}
