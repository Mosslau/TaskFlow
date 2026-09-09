package com.taskflow.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.task.entity.ImportBatch;
import org.apache.ibatis.annotations.Mapper;

/**
 * import_batch 表访问层。
 */
@Mapper
public interface ImportBatchMapper extends BaseMapper<ImportBatch> {
}
