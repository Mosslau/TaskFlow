package com.taskflow.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.auth.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;

/** api_key 表访问层（通用 CRUD 由 BaseMapper 提供）。 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
