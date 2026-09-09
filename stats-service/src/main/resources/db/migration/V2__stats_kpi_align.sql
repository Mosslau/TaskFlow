-- ============================================================
-- V2: KPI 口径对齐（PRD 4.3.2 紧急任务 P0 + 趋势 completed 完成日口径）
-- 事件消费与 rebuild 两条路径同口径，见 StatsAggregateService / OverviewService 注释。
-- ============================================================

-- 1) 紧急任务 P0（PRD 4.3.2："紧急任务 = 优先级 P0 且未完成"）
--    stats_task_daily 增加 p0_unfinished：当日创建（按创建时间区间过滤）且
--    优先级 = P0 且当前状态 ∈ 未完成(new/doing/wait) 的任务数。
--    增量：task.assigned(P0 新任务 +1) / task.status.changed(未完成→终态 -1, 保守 +1)；
--    存量由 rebuild 全量重算校准。
ALTER TABLE stats_task_daily ADD COLUMN p0_unfinished BIGINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN stats_task_daily.p0_unfinished IS '当日创建且优先级=P0 且未完成(new/doing/wait)的任务数（PRD 4.3.2 紧急任务口径：按创建日过滤）';

-- 2) 完成日聚合表（PRD 趋势 completed 数据源，区别于 stats_task_daily 的"创建日队列"口径）
--    按任务进入终态（done/close）的日期聚合完成量/完成时长/按时完成数；只计顶层任务。
--    增量：task.status.changed 的 from∈未完成 → to∈done/close 那一次 +1
--          （done→close 归档的 from=done 不重复计，避免同一任务计两次）；
--    回填：当前状态 done/close 的任务按其 updated_at（近似完成时刻）归入完成日。
CREATE TABLE stats_completion_daily (
    complete_date DATE PRIMARY KEY,
    completed      BIGINT NOT NULL DEFAULT 0,
    hours_sum      NUMERIC(12,1) NOT NULL DEFAULT 0,
    ontime_count   BIGINT NOT NULL DEFAULT 0
);
COMMENT ON TABLE  stats_completion_daily IS '按任务完成日（进入 done/close 的日期）的日粒度完成聚合（趋势 completed 数据源；只计顶层任务）';
COMMENT ON COLUMN stats_completion_daily.complete_date IS '完成日 = 进入终态(done/close)的日期（增量路径取事件处理时刻；回填路径取 updated_at，误差与存量 updated_at 语义一致）';
COMMENT ON COLUMN stats_completion_daily.completed IS '当日进入终态（done/close）的任务数';
COMMENT ON COLUMN stats_completion_daily.hours_sum IS '当日完成任务完成时长合计（小时，1 位小数）= 完成时刻 - 创建时间';
COMMENT ON COLUMN stats_completion_daily.ontime_count IS '当日完成任务中按时完成数（完成时刻 ≤ 到期时间）；按时完成率 = ontime_count / completed';
