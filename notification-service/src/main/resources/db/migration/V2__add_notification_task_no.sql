-- ============================================================
-- V2: notification 增加 task_no 列（M3）
-- 接口 #43 响应含 taskNo；事件载荷自带 taskNo，随消息落库，避免跨服务回查。
-- ============================================================

ALTER TABLE notification ADD COLUMN IF NOT EXISTS task_no TEXT;
COMMENT ON COLUMN notification.task_no IS '关联任务编号（TSK-xxx；列表展示与点击跳转用，可空）';
