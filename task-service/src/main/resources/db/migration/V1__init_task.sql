-- ============================================================
-- V1: task_db 初始 schema（库表设计文档第 4 章）
-- ============================================================

-- 任务编号序列：从 100001 起，接受跳号（决策基线 #2）
CREATE SEQUENCE task_no_seq START 100001 INCREMENT 1;
COMMENT ON SEQUENCE task_no_seq IS '任务编号序列（100001 起，应用层拼 TSK- 前缀；允许跳号）';

CREATE TABLE task (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_no       TEXT NOT NULL UNIQUE,
    title         TEXT NOT NULL CHECK (LENGTH(title) BETWEEN 1 AND 100),
    description   TEXT CHECK (LENGTH(description) <= 2000),
    task_type     TEXT NOT NULL DEFAULT '项目开发' CHECK (task_type IN (
                      '项目开发', '日常事务', '会议事项', '调研分析', '数据报表', '流程审批')),
    priority      TEXT NOT NULL DEFAULT 'P2' CHECK (priority IN ('P0', 'P1', 'P2', 'P3')),
    status        TEXT NOT NULL DEFAULT 'new'
                  CHECK (status IN ('new', 'doing', 'wait', 'done', 'close')),
    creator_id    BIGINT NOT NULL,
    assignee_id   BIGINT NOT NULL,
    due_at        TIMESTAMPTZ NOT NULL,
    progress      INT NOT NULL DEFAULT 0
                  CHECK (progress BETWEEN 0 AND 100 AND progress % 5 = 0),
    source        TEXT NOT NULL DEFAULT '网页'
                  CHECK (source IN ('网页', 'Excel 导入', 'OpenAPI')),
    parent_id     BIGINT REFERENCES task(id),
    due_reminded  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  task IS '任务主表（PRD 4.1.1；子任务同表，parent_id 指向顶层任务，仅一级嵌套）';
COMMENT ON COLUMN task.task_no IS '任务编号，TSK- + task_no_seq，全局唯一不复用（PRD 5.3.1）';
COMMENT ON COLUMN task.title IS '标题，1-100 字符';
COMMENT ON COLUMN task.description IS '描述（背景、目标与验收标准），≤ 2000 字符';
COMMENT ON COLUMN task.task_type IS '任务类型（6 枚举之一，子任务继承父任务）';
COMMENT ON COLUMN task.priority IS '优先级：P0 紧急 / P1 高 / P2 中 / P3 低';
COMMENT ON COLUMN task.status IS '状态机：new 待办 / doing 进行中 / wait 待验收 / done 已完成 / close 已归档（PRD 4.1.2；子任务仅用 new/doing/done）';
COMMENT ON COLUMN task.creator_id IS '创建人（逻辑引用 auth_user_db.app_user.id）';
COMMENT ON COLUMN task.assignee_id IS '处理人（逻辑引用；角色为 taskAdmin/user 的在职用户，admin 不可担任）';
COMMENT ON COLUMN task.due_at IS '到期时间（精确到分钟，PRD 4.1.1）';
COMMENT ON COLUMN task.progress IS '进度 0-100，步进 5';
COMMENT ON COLUMN task.source IS '来源渠道：网页 / Excel 导入 / OpenAPI（子任务固定"网页"；企微同步等为预留值）';
COMMENT ON COLUMN task.parent_id IS '父任务（顶层任务为 NULL；一级嵌套由触发器 trg_task_parent_top_level 保证）';
COMMENT ON COLUMN task.due_reminded IS '到期前 24h 提醒已发标记（每任务仅一次，PRD 4.6.1 事件 6）';
COMMENT ON COLUMN task.created_at IS '创建时间（UTC；统计区间口径以此为准，PRD 4.3.1）';
COMMENT ON COLUMN task.updated_at IS '更新时间（每次状态或字段变更刷新；完成时长与按时率以此计）';
CREATE INDEX idx_task_creator ON task (creator_id);
CREATE INDEX idx_task_assignee ON task (assignee_id);
CREATE INDEX idx_task_parent ON task (parent_id);
CREATE INDEX idx_task_created ON task (created_at DESC);
CREATE INDEX idx_task_due_unfinished ON task (due_at)
    WHERE status IN ('new', 'doing', 'wait');
CREATE INDEX idx_task_title_trgm ON task USING GIN (title gin_trgm_ops);
CREATE INDEX idx_task_desc_trgm ON task USING GIN (description gin_trgm_ops);

-- 子任务仅一级嵌套：跨行约束触发器
CREATE OR REPLACE FUNCTION check_parent_is_top_level() RETURNS trigger AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        IF NEW.parent_id = NEW.id THEN
            RAISE EXCEPTION '任务不能以自身为父任务';
        END IF;
        IF EXISTS (SELECT 1 FROM task WHERE id = NEW.parent_id AND parent_id IS NOT NULL) THEN
            RAISE EXCEPTION '子任务仅允许一级嵌套（父任务必须是顶层任务）';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_parent_top_level
    BEFORE INSERT OR UPDATE ON task
    FOR EACH ROW EXECUTE FUNCTION check_parent_is_top_level();

CREATE TABLE task_timeline (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id      BIGINT NOT NULL REFERENCES task(id),
    operator_id  BIGINT NOT NULL,
    action       TEXT NOT NULL,
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  task_timeline IS '操作时间线（只增不改，PRD 4.1.4；按时间倒序展示）';
COMMENT ON COLUMN task_timeline.task_id IS '所属任务';
COMMENT ON COLUMN task_timeline.operator_id IS '操作人（逻辑引用 app_user.id；自动归档记系统操作）';
COMMENT ON COLUMN task_timeline.action IS '动作：创建/受理/更新进度/转派/调整优先级/调整到期/提交验收/验收通过/验收驳回/手动归档/自动归档';
COMMENT ON COLUMN task_timeline.note IS '备注：进展说明 / 驳回原因(≤500) / 转派说明(≤200) 等';
COMMENT ON COLUMN task_timeline.created_at IS '操作时间（UTC）';
CREATE INDEX idx_timeline_task ON task_timeline (task_id, created_at DESC);
-- 只增不改的 REVOKE 约束由运维账号在部署时执行（见库表设计文档 7.2）

CREATE TABLE task_comment (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id       BIGINT NOT NULL REFERENCES task(id),
    commenter_id  BIGINT NOT NULL,
    content       TEXT NOT NULL CHECK (LENGTH(content) BETWEEN 1 AND 500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  task_comment IS '任务评论（PRD 4.1.5；可删不可改，不写入操作时间线）';
COMMENT ON COLUMN task_comment.task_id IS '所属任务（子任务同表支持）';
COMMENT ON COLUMN task_comment.commenter_id IS '评论人（逻辑引用 app_user.id）';
COMMENT ON COLUMN task_comment.content IS '评论内容，纯文本 ≤ 500 字符';
COMMENT ON COLUMN task_comment.created_at IS '评论时间（UTC；按时间正序展示）';
CREATE INDEX idx_comment_task ON task_comment (task_id, created_at);

CREATE TABLE task_attachment (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id        BIGINT NOT NULL REFERENCES task(id),
    original_name  TEXT NOT NULL,
    stored_name    TEXT NOT NULL UNIQUE,
    size_bytes     BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 20971520),
    uploader_id    BIGINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  task_attachment IS '任务附件元数据（PRD 4.1.6；文件本体存服务端本地文件系统，见架构文档 2.4）';
COMMENT ON COLUMN task_attachment.task_id IS '所属任务（附件归属所在任务，独立计数 ≤ 10 个）';
COMMENT ON COLUMN task_attachment.original_name IS '原始文件名（下载按此名返回）';
COMMENT ON COLUMN task_attachment.stored_name IS '落盘文件名（UUID，防路径穿越）';
COMMENT ON COLUMN task_attachment.size_bytes IS '文件大小（字节，单文件 ≤ 20MB）';
COMMENT ON COLUMN task_attachment.uploader_id IS '上传人（逻辑引用 app_user.id；删除限本人或 admin）';
COMMENT ON COLUMN task_attachment.created_at IS '上传时间（UTC）';
CREATE INDEX idx_attachment_task ON task_attachment (task_id);

CREATE TABLE import_batch (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id    BIGINT NOT NULL,
    file_name      TEXT NOT NULL,
    total_rows     INT NOT NULL CHECK (total_rows BETWEEN 0 AND 500),
    success_count  INT NOT NULL DEFAULT 0,
    fail_count     INT NOT NULL DEFAULT 0,
    error_report   JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  import_batch IS 'Excel 导入批次记录（PRD 4.7；保留 1 年）';
COMMENT ON COLUMN import_batch.operator_id IS '导入操作人（逻辑引用 app_user.id；导入任务的创建人）';
COMMENT ON COLUMN import_batch.file_name IS '导入文件名';
COMMENT ON COLUMN import_batch.total_rows IS '文件总行数（单次上限 500）';
COMMENT ON COLUMN import_batch.success_count IS '成功行数（整批校验，任一行失败则全批不入库）';
COMMENT ON COLUMN import_batch.fail_count IS '失败行数';
COMMENT ON COLUMN import_batch.error_report IS '逐行错误报告：[{"row": 3, "reason": "处理人账号不存在"}]';
COMMENT ON COLUMN import_batch.created_at IS '导入时间（UTC）';
CREATE INDEX idx_import_batch_created ON import_batch (created_at DESC);

CREATE TABLE event_outbox (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    event_type    TEXT NOT NULL,
    payload       JSONB NOT NULL,
    delivered     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at  TIMESTAMPTZ
);
COMMENT ON TABLE  event_outbox IS '本地消息表（架构文档 4.2/第 5 章；事件与业务同事务，后台线程轮询投递 RabbitMQ）';
COMMENT ON COLUMN event_outbox.event_id IS '事件全局唯一 ID（消费端幂等去重键）';
COMMENT ON COLUMN event_outbox.event_type IS '事件类型：task.assigned / task.status.changed / task.due.soon / task.overdue 等（架构文档 3.3）';
COMMENT ON COLUMN event_outbox.payload IS '事件载荷（变更前后状态与关键字段；schema 在 common 模块，字段只增不改）';
COMMENT ON COLUMN event_outbox.delivered IS '投递标记：FALSE 待投递 / TRUE 已投递（失败重投）';
COMMENT ON COLUMN event_outbox.created_at IS '事件产生时间（UTC）';
COMMENT ON COLUMN event_outbox.delivered_at IS '投递成功时间（UTC）';
CREATE INDEX idx_outbox_undelivered ON event_outbox (created_at) WHERE NOT delivered;
