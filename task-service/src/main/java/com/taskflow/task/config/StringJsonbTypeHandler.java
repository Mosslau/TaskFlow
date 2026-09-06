package com.taskflow.task.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * String ↔ PostgreSQL JSONB 类型处理器（与 auth-user-service 同构）。
 * PG 驱动对 jsonb 列不接受 setString，必须以 Types.OTHER 形式写入。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public class StringJsonbTypeHandler extends BaseTypeHandler<String> {

    /** 写库：setObject + Types.OTHER，PG 驱动据此按 jsonb 处理 */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    /** 读库：jsonb → String */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
