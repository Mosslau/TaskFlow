package com.taskflow.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.auth.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/** audit_log 表访问层（只增不改：业务代码只调 insert / select）。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
