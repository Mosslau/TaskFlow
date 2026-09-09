package com.taskflow.acceptance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 白盒访问（四业务库）：数据预造、断言、清理。
 * 全部串行小查询，单连接即可。
 */
public final class Db {

    private static final Map<String, Connection> CONNS = new java.util.concurrent.ConcurrentHashMap<>();

    private Db() {
    }

    private static Connection conn(String db) {
        return CONNS.computeIfAbsent(db, d -> {
            try {
                String url = "jdbc:postgresql://" + Conf.PG_HOST + ":" + Conf.PG_PORT + "/" + d;
                Connection c = DriverManager.getConnection(url, Conf.PG_USER, Conf.PG_PASSWORD);
                c.setAutoCommit(true);
                return c;
            } catch (SQLException e) {
                throw new IllegalStateException("无法连接数据库 " + d, e);
            }
        });
    }

    public static Connection task() {
        return conn(Conf.DB_TASK);
    }

    public static Connection auth() {
        return conn(Conf.DB_AUTH);
    }

    public static Connection notification() {
        return conn(Conf.DB_NOTIFICATION);
    }

    public static Connection stats() {
        return conn(Conf.DB_STATS);
    }

    /** 执行 UPDATE/INSERT/DELETE，返回影响行数 */
    public static int update(String db, String sql, Object... args) {
        try (PreparedStatement ps = conn(db).prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    /** 标量查询 */
    public static String scalar(String db, String sql, Object... args) {
        try (PreparedStatement ps = conn(db).prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 查询失败: " + sql, e);
        }
    }

    public static long scalarLong(String db, String sql, Object... args) {
        String s = scalar(db, sql, args);
        return s == null ? -1L : Long.parseLong(s);
    }

    public static int scalarInt(String db, String sql, Object... args) {
        String s = scalar(db, sql, args);
        return s == null ? -1 : Integer.parseInt(s);
    }

    /** 行集合查询（Map key 为列名小写） */
    public static List<Map<String, Object>> rows(String db, String sql, Object... args) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn(db).prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    }
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 查询失败: " + sql, e);
        }
        return out;
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }
}
