-- ============================================================
-- TaskFlow 四个业务库初始化脚本
-- 由 docker-compose 挂载到 postgres 容器 /docker-entrypoint-initdb.d/,
-- 仅在数据卷首次初始化(空)时自动执行一次。
--
-- 表结构与种子数据由各服务启动时的 Flyway(spring.flyway.*)负责,
-- 这里只负责建库(应用不能自建库)。
-- ============================================================

CREATE DATABASE auth_user_db;
CREATE DATABASE task_db;
CREATE DATABASE notification_db;
CREATE DATABASE stats_db;
