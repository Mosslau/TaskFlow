package com.taskflow.auth.config;

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
 * String ↔ PostgreSQL JSONB 类型处理器。
 *
 * <p>PG 驱动对 jsonb 列不接受 setString（报 column is of type jsonb but
 * expression is of type character varying），必须以 Types.OTHER 形式写入。
 * 本处理器把 Java String 与 jsonb 列互转，用于 audit_log.change_detail 等
 * "原样存取 JSON 文本"的场景（业务侧不需要结构化解析）。</p>
 */
// @MappedTypes / @MappedJdbcTypes：声明本处理器负责 Java String ↔ JDBC OTHER(jsonb) 的映射
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
