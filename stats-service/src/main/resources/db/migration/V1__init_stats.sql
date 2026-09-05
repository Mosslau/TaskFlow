-- ============================================================
-- V1: stats_db 初始 schema（库表设计文档第 6 章）
-- ============================================================

CREATE TABLE stats_task_daily (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stat_date             DATE NOT NULL UNIQUE,
    total_count           BIGINT NOT NULL DEFAULT 0,
    new_count             BIGINT NOT NULL DEFAULT 0,
    doing_count           BIGINT NOT NULL DEFAULT 0,
    wait_count            BIGINT NOT NULL DEFAULT 0,
    done_count            BIGINT NOT NULL DEFAULT 0,
    close_count           BIGINT NOT NULL DEFAULT 0,
    p0_count              BIGINT NOT NULL DEFAULT 0,
    p1_count              BIGINT NOT NULL DEFAULT 0,
    p2_count              BIGINT NOT NULL DEFAULT 0,
    p3_count              BIGINT NOT NULL DEFAULT 0,
    completed_count       BIGINT NOT NULL DEFAULT 0,
    completed_hours_sum   NUMERIC(12,1) NOT NULL DEFAULT 0,
    ontime_count          BIGINT NOT NULL DEFAULT 0
);
COMMENT ON TABLE  stats_task_daily IS '按任务创建日的日粒度聚合（KPI 与图表数据源；只计顶层任务；事件驱动增量 ±1，架构文档 4.4）';
COMMENT ON COLUMN stats_task_daily.stat_date IS '统计日 = 任务创建日（PRD 4.3.1 区间过滤口径）';
COMMENT ON COLUMN stats_task_daily.total_count IS '当日创建任务总数';
COMMENT ON COLUMN stats_task_daily.new_count IS '当日创建任务中当前为待办的数量';
COMMENT ON COLUMN stats_task_daily.doing_count IS '当日创建任务中当前为进行中的数量';
COMMENT ON COLUMN stats_task_daily.wait_count IS '当日创建任务中当前为待验收的数量';
COMMENT ON COLUMN stats_task_daily.done_count IS '当日创建任务中当前为已完成的数量';
COMMENT ON COLUMN stats_task_daily.close_count IS '当日创建任务中当前为已归档的数量';
COMMENT ON COLUMN stats_task_daily.p0_count IS '当日创建的 P0 任务数（P1-P3 同理）';
COMMENT ON COLUMN stats_task_daily.completed_count IS '当日创建且已完成/已归档的任务数';
COMMENT ON COLUMN stats_task_daily.completed_hours_sum IS '已完成任务的完成时长合计（小时）；平均完成时长 = completed_hours_sum / completed_count';
COMMENT ON COLUMN stats_task_daily.ontime_count IS '按时完成数（更新时间 ≤ 到期时间）；按时完成率 = ontime_count / completed_count';

CREATE TABLE stats_overdue_daily (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    snapshot_date      DATE NOT NULL,
    task_created_date  DATE NOT NULL,
    overdue_count      BIGINT NOT NULL DEFAULT 0,
    UNIQUE (snapshot_date, task_created_date)
);
COMMENT ON TABLE  stats_overdue_daily IS '逾期任务日快照（决策基线 #7；由每日 task.overdue 扫描事件累积，误差 ≤ 24 小时）';
COMMENT ON COLUMN stats_overdue_daily.snapshot_date IS '快照日（每日 09:00 扫描产生）';
COMMENT ON COLUMN stats_overdue_daily.task_created_date IS '逾期任务的创建日（支撑 KPI 区间过滤）';
COMMENT ON COLUMN stats_overdue_daily.overdue_count IS '该快照日、该创建日的逾期任务数';
CREATE INDEX idx_overdue_snapshot ON stats_overdue_daily (snapshot_date);

CREATE TABLE stats_assignee_load (
    assignee_id       BIGINT PRIMARY KEY,
    unfinished_count  BIGINT NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  stats_assignee_load IS '人员负载当前值表（PRD 4.3.3 人员负载图；事件驱动 ±1）';
COMMENT ON COLUMN stats_assignee_load.assignee_id IS '处理人（逻辑引用 app_user.id；查询时排除 admin 角色）';
COMMENT ON COLUMN stats_assignee_load.unfinished_count IS '名下未完成（待办+进行中+待验收）任务数';
COMMENT ON COLUMN stats_assignee_load.updated_at IS '最后更新时间（UTC）';

CREATE TABLE processed_event (
    event_id      UUID PRIMARY KEY,
    consumer      TEXT NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  processed_event IS '消费幂等去重表（INSERT 冲突即跳过；架构文档第 5 章）';
COMMENT ON COLUMN processed_event.event_id IS '已消费的事件 ID';
COMMENT ON COLUMN processed_event.consumer IS '消费者标识，如 stats.daily';
COMMENT ON COLUMN processed_event.processed_at IS '消费时间（UTC）';
