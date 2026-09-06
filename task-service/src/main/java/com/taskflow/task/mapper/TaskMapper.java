package com.taskflow.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.task.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * task 表访问层。
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    /**
     * 取任务编号序列的下一个值（库表设计文档：task_no_seq 从 100001 起，接受跳号）。
     *
     * @return 序列下一个值
     */
    @Select("SELECT nextval('task_no_seq')")
    Long nextTaskNo();
}
