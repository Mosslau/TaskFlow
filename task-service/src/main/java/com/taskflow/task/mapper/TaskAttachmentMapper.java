package com.taskflow.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.task.entity.TaskAttachment;
import org.apache.ibatis.annotations.Mapper;

/**
 * task_attachment 表访问层。
 */
@Mapper
public interface TaskAttachmentMapper extends BaseMapper<TaskAttachment> {
}
