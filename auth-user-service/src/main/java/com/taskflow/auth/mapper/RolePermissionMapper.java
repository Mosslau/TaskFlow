package com.taskflow.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.auth.entity.RolePermission;
import org.apache.ibatis.annotations.Mapper;

/** role_permission 表访问层（通用 CRUD 由 BaseMapper 提供）。 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {
}
