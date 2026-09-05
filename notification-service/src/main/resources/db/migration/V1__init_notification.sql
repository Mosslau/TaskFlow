-- ============================================================
-- V1: notification_db 初始 schema（库表设计文档第 5 章）
-- ============================================================

CREATE TABLE notification (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipient_id  BIGINT NOT NULL,
    event_type    TEXT NOT NULL,
    summary       TEXT NOT NULL,
    task_id       BIGINT,
    is_read       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  notification IS '站内消息（PRD 4.6.2；保留 180 天，过期定时清理）';
COMMENT ON COLUMN notification.recipient_id IS '接收人（逻辑引用 app_user.id）';
COMMENT ON COLUMN notification.event_type IS '触发事件（PRD 4.6.1 的 8 类之一）';
COMMENT ON COLUMN notification.summary IS '消息摘要';
COMMENT ON COLUMN notification.task_id IS '关联任务（逻辑引用 task_db.task.id；点击跳转详情用，可空）';
COMMENT ON COLUMN notification.is_read IS '已读标记（未读数角标按此统计）';
COMMENT ON COLUMN notification.created_at IS '消息时间（UTC；列表倒序）';
CREATE INDEX idx_notification_unread ON notification (recipient_id) WHERE NOT is_read;
CREATE INDEX idx_notification_list ON notification (recipient_id, created_at DESC);
CREATE INDEX idx_notification_created ON notification (created_at);

CREATE TABLE mail_record (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id      BIGINT,
    recipient    TEXT NOT NULL,
    subject      TEXT NOT NULL,
    result       TEXT NOT NULL CHECK (result IN ('success', 'failed')),
    retry_count  INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  mail_record IS '邮件记录（PRD 4.6.3；保留 1 年）';
COMMENT ON COLUMN mail_record.task_id IS '关联任务（逻辑引用；SMTP 故障告警 admin 的邮件不关联任务，可空）';
COMMENT ON COLUMN mail_record.recipient IS '收件人邮箱';
COMMENT ON COLUMN mail_record.subject IS '主题：【任务管理】<事件> <任务编号> <任务标题>';
COMMENT ON COLUMN mail_record.result IS '发送结果：success / failed（重试 3 次仍失败记 failed 并告警）';
COMMENT ON COLUMN mail_record.retry_count IS '已重试次数（指数退避，≤ 3）';
COMMENT ON COLUMN mail_record.created_at IS '发送时间（UTC）';
CREATE INDEX idx_mail_record_task ON mail_record (task_id);
CREATE INDEX idx_mail_record_created ON mail_record (created_at);

CREATE TABLE processed_event (
    event_id      UUID PRIMARY KEY,
    consumer      TEXT NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  processed_event IS '消费幂等去重表（INSERT 冲突即跳过；架构文档第 5 章）';
COMMENT ON COLUMN processed_event.event_id IS '已消费的事件 ID';
COMMENT ON COLUMN processed_event.consumer IS '消费者标识，如 notification.mail';
COMMENT ON COLUMN processed_event.processed_at IS '消费时间（UTC）';
