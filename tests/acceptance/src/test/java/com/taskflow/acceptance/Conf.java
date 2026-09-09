package com.taskflow.acceptance;

import java.time.Duration;

/**
 * 环境与超时常量（对准本地全量环境：网关 8000 + PG/Redis/RabbitMQ 本机）。
 */
public final class Conf {

    private Conf() {
    }

    public static final String BASE = System.getenv().getOrDefault("TF_BASE", "http://127.0.0.1:8000");

    /** admin 出厂凭据（must_change_password=TRUE 但 API 不拦，可直接调业务接口） */
    public static final String ADMIN_ACCOUNT = "admin";
    public static final String ADMIN_PASSWORD = "Admin@123";

    /** 默认权限矩阵（PRD 3.3）关键开关，测试用于把矩阵还原成默认态 */
    public static final String[][] DEFAULT_MATRIX = {
            // roleKey, permissionKey, enabled
            {"admin", "manageUser", "true"},
            {"admin", "setPerm", "true"},
            {"taskAdmin", "viewAll", "false"},
            {"taskAdmin", "manageUser", "false"},
            {"taskAdmin", "setPerm", "false"},
            {"user", "viewAll", "false"},
            {"user", "create", "false"},
            {"user", "editOwn", "false"},
            {"user", "deleteOwn", "false"},
            {"user", "transferOwn", "false"},
            {"user", "prioOwn", "false"},
            {"user", "dueOwn", "false"},
            {"user", "viewStats", "false"},
            {"user", "exportData", "false"},
    };

    /** 用户创建后的角色 id */
    public static final long ROLE_ADMIN = 1L;
    public static final long ROLE_TASK_ADMIN = 2L;
    public static final long ROLE_USER = 3L;

    /** PostgreSQL 连接（四业务库） */
    public static final String PG_HOST = "127.0.0.1";
    public static final int PG_PORT = 5432;
    public static final String PG_USER = "postgres";
    public static final String PG_PASSWORD = "root";
    public static final String DB_AUTH = "auth_user_db";
    public static final String DB_TASK = "task_db";
    public static final String DB_NOTIFICATION = "notification_db";
    public static final String DB_STATS = "stats_db";

    /** Redis（锁定键校验） */
    public static final String REDIS_HOST = "127.0.0.1";
    public static final int REDIS_PORT = 6379;
    public static final String REDIS_PASSWORD = "root";

    /** 附件落盘根目录（task-service application.yml） */
    public static final String ATTACHMENT_ROOT = "/tmp/taskflow/attachments";

    /** 事件链路排空等待上限（站内消息/邮件记录断言） */
    public static final Duration EVENT_WAIT = Duration.ofSeconds(120);

    /** 默认部门 id（种子） */
    public static final long DEFAULT_DEPT_ID = 1L;
}
