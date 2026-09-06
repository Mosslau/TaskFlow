package com.taskflow.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.auth.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * app_user 表访问层。
 * 继承 MyBatis-Plus BaseMapper 即获得 selectById / insert / updateById /
 * selectList(Wrapper) 等通用 CRUD，无需手写 XML。
 */
// @Mapper：MyBatis 扫描并生成代理实现
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
