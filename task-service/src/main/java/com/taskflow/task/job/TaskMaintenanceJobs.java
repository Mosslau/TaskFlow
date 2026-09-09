package com.taskflow.task.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.common.RedisUtils;
import com.taskflow.common.event.TaskEvents;
import com.taskflow.task.entity.ImportBatch;
import com.taskflow.task.entity.Task;
import com.taskflow.task.entity.TaskAttachment;
import com.taskflow.task.entity.TaskTimeline;
import com.taskflow.task.mapper.ImportBatchMapper;
import com.taskflow.task.mapper.TaskAttachmentMapper;
import com.taskflow.task.mapper.TaskMapper;
import com.taskflow.task.mapper.TaskTimelineMapper;
import com.taskflow.task.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M5 定时任务（PRD 4.6.1 / 11.2）：到期提醒、逾期提醒、自动归档、孤儿附件清理、导入批次保留清理。
 *
 * <p>全部为增量新增，不改现有业务逻辑。每个 job 先用 {@link RedisUtils#setIfAbsent}
 * 抢分布式锁（键前缀 taskflow:job:），抢不到直接跳过本轮（多实例/重入防护）；
 * 锁 TTL 覆盖正常执行时长，兜底防持锁者宕机死锁。每轮记 INFO 日志（扫描数/动作数）。</p>
 */
@Component
public class TaskMaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(TaskMaintenanceJobs.class);

    /** 进行中的状态（到期/逾期扫描只盯这些；close 已归档天然排除） */
    private static final List<String> ACTIVE_STATUSES =
            List.of(TaskService.ST_NEW, TaskService.ST_DOING, TaskService.ST_WAIT);

    /** 系统操作在 timeline.operator_id 的占位（库表设计：自动归档记系统操作 0） */
    private static final long SYSTEM_OPERATOR_ID = 0L;

    /** 孤儿附件判定宽限：最后修改时间早于 10 分钟前才删（防删到正在上传的临时文件） */
    private static final Duration ORPHAN_GRACE = Duration.ofMinutes(10);

    private final TaskMapper taskMapper;
    private final TaskTimelineMapper timelineMapper;
    private final TaskAttachmentMapper attachmentMapper;
    private final ImportBatchMapper importBatchMapper;
    private final TaskService taskService;
    private final RedisUtils redis;
    private final Path attachmentRoot;

    public TaskMaintenanceJobs(TaskMapper taskMapper, TaskTimelineMapper timelineMapper,
                               TaskAttachmentMapper attachmentMapper, ImportBatchMapper importBatchMapper,
                               TaskService taskService, RedisUtils redis,
                               @Value("${taskflow.attachment.root:/tmp/taskflow/attachments}") String attachmentRoot) {
        this.taskMapper = taskMapper;
        this.timelineMapper = timelineMapper;
        this.attachmentMapper = attachmentMapper;
        this.importBatchMapper = importBatchMapper;
        this.taskService = taskService;
        this.redis = redis;
        this.attachmentRoot = Paths.get(attachmentRoot).toAbsolutePath().normalize();
    }

    // ==================== 1. 到期前 24h 提醒（每小时；每任务仅一次，PRD 4.6.1） ====================

    /**
     * 扫描 status ∈ (new,doing,wait) 且 now &lt; due_at ≤ now+24h 且 due_reminded=false 的任务
     * （含子任务），置 due_reminded=true 并发 task.due.soon（通知处理人）。
     *
     * <p>#7：只提醒"即将到期"——已逾期任务（due_at ≤ now）不在到期提醒范围，
     * 统一交给 overdueScan 管，避免用户收到"即将到期"的过时提醒。</p>
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Shanghai")
    @Transactional
    public void dueSoonScan() {
        if (!redis.setIfAbsent("taskflow:job:due-scan", "1", Duration.ofMinutes(10))) {
            log.info("到期提醒扫描：未抢到锁，跳过本轮");
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<Task> due = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .in(Task::getStatus, ACTIVE_STATUSES)
                .isNotNull(Task::getDueAt)
                .gt(Task::getDueAt, now)
                .le(Task::getDueAt, now.plusHours(24))
                .and(w -> w.eq(Task::getDueReminded, false).or().isNull(Task::getDueReminded)));
        int reminded = 0;
        for (Task task : due) {
            task.setDueReminded(true);
            taskMapper.updateById(task);
            taskService.emitEvent(TaskEvents.TASK_DUE_SOON, reminderPayload(task));
            reminded++;
        }
        log.info("到期提醒扫描完成: scanned={}, reminded={}", due.size(), reminded);
    }

    // ==================== 2. 逾期提醒（每日 09:00 Asia/Shanghai；每日一次天然满足） ====================

    /**
     * 扫描 status ∈ (new,doing,wait) 且 due_at &lt; now 的任务（含子任务），
     * 发 task.overdue（通知处理人 + 创建人；stats 用 createdAt 做逾期日快照）。
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void overdueScan() {
        if (!redis.setIfAbsent("taskflow:job:overdue-scan", "1", Duration.ofMinutes(30))) {
            log.info("逾期扫描：未抢到锁，跳过本轮");
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<Task> overdue = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .in(Task::getStatus, ACTIVE_STATUSES)
                .isNotNull(Task::getDueAt)
                .lt(Task::getDueAt, now));
        for (Task task : overdue) {
            taskService.emitEvent(TaskEvents.TASK_OVERDUE, reminderPayload(task));
        }
        log.info("逾期扫描完成: scanned={}, notified={}", overdue.size(), overdue.size());
    }

    // ==================== 3. 自动归档（每日 03:30；完成满 7 天，PRD 11.2） ====================

    /**
     * 扫描 status=done 且 updated_at ≤ now-7天 的任务：置 close + 时间线"自动归档"
     * + task.status.changed 事件（fromStatus=done/toStatus=close 完整载荷，stats 增量聚合）。
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void autoArchiveScan() {
        if (!redis.setIfAbsent("taskflow:job:auto-archive", "1", Duration.ofMinutes(30))) {
            log.info("自动归档扫描：未抢到锁，跳过本轮");
            return;
        }
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(7);
        List<Task> done = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getStatus, TaskService.ST_DONE)
                .le(Task::getUpdatedAt, threshold));
        int archived = 0;
        for (Task task : done) {
            task.setStatus(TaskService.ST_CLOSE);
            task.setUpdatedAt(OffsetDateTime.now());
            taskMapper.updateById(task);
            timeline(task.getId(), "自动归档", "完成满 7 天自动归档");
            taskService.emitEvent(TaskEvents.TASK_STATUS_CHANGED, statusPayload(task, TaskService.ST_DONE));
            archived++;
        }
        log.info("自动归档扫描完成: scanned={}, archived={}", done.size(), archived);
    }

    // ==================== 4. 孤儿附件清理（每日 04:00） ====================

    /**
     * 遍历附件根目录：DB 无对应 stored_name 且最后修改时间早于 10 分钟前的文件删除并记日志。
     * 只处理根目录下普通文件（附件服务平铺落盘，文件名即 stored_name）。
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Shanghai")
    public void orphanAttachmentCleanup() {
        if (!redis.setIfAbsent("taskflow:job:attachment-cleanup", "1", Duration.ofMinutes(30))) {
            log.info("孤儿附件清理：未抢到锁，跳过本轮");
            return;
        }
        if (!Files.isDirectory(attachmentRoot)) {
            log.info("孤儿附件清理完成: 附件根目录不存在({})，scanned=0, deleted=0", attachmentRoot);
            return;
        }
        // DB 现存 stored_name 全集（附件量受任务数*10 限制，全量载入可接受）
        Set<String> known = new HashSet<>();
        attachmentMapper.selectObjs(new LambdaQueryWrapper<TaskAttachment>()
                        .select(TaskAttachment::getStoredName))
                .forEach(o -> known.add(String.valueOf(o)));
        Instant cutoff = Instant.now().minus(ORPHAN_GRACE);
        int scanned = 0;
        int deleted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(attachmentRoot)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                scanned++;
                String name = file.getFileName().toString();
                if (known.contains(name)) {
                    continue;
                }
                FileTime modified = Files.getLastModifiedTime(file);
                if (modified.toInstant().isAfter(cutoff)) {
                    // 太新：可能是上传途中/刚失败，留到下轮
                    continue;
                }
                Files.deleteIfExists(file);
                deleted++;
                log.info("孤儿附件已删除: file={}, lastModified={}", name, modified);
            }
        } catch (IOException e) {
            log.warn("孤儿附件清理异常: root={}, err={}", attachmentRoot, e.getMessage());
        }
        log.info("孤儿附件清理完成: scanned={}, deleted={}", scanned, deleted);
    }

    // ==================== 5. import_batch 保留 1 年清理（每日 04:10） ====================

    /** 删除 created_at &lt; now-1年 的导入批次记录（PRD 4.7：保留 1 年）。 */
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void importBatchRetentionCleanup() {
        if (!redis.setIfAbsent("taskflow:job:import-batch-retention", "1", Duration.ofMinutes(10))) {
            log.info("导入批次保留清理：未抢到锁，跳过本轮");
            return;
        }
        OffsetDateTime threshold = OffsetDateTime.now().minusYears(1);
        int deleted = importBatchMapper.delete(new LambdaQueryWrapper<ImportBatch>()
                .lt(ImportBatch::getCreatedAt, threshold));
        log.info("导入批次保留清理完成: threshold={}, deleted={}", threshold, deleted);
    }

    // ==================== 内部辅助 ====================

    /** 到期/逾期提醒事件载荷（通知处理人/创建人 + stats 逾期快照用 createdAt） */
    private Map<String, Object> reminderPayload(Task task) {
        Map<String, Object> p = new HashMap<>();
        p.put("taskId", task.getId());
        p.put("taskNo", task.getTaskNo());
        p.put("title", task.getTitle());
        p.put("assigneeId", task.getAssigneeId());
        p.put("creatorId", task.getCreatorId());
        p.put("dueAt", task.getDueAt() == null ? "" : task.getDueAt().toString());
        p.put("createdAt", task.getCreatedAt() == null ? "" : task.getCreatedAt().toString());
        return p;
    }

    /** 状态变更事件载荷（与 TaskService.statusPayload 同构，stats 增量聚合契约） */
    private Map<String, Object> statusPayload(Task task, String fromStatus) {
        Map<String, Object> p = new HashMap<>();
        p.put("taskId", task.getId());
        p.put("taskNo", task.getTaskNo());
        p.put("fromStatus", fromStatus);
        p.put("toStatus", task.getStatus());
        p.put("priority", task.getPriority());
        p.put("assigneeId", task.getAssigneeId());
        p.put("creatorId", task.getCreatorId());
        p.put("parentId", task.getParentId() == null ? "" : task.getParentId());
        p.put("dueAt", task.getDueAt() == null ? "" : task.getDueAt().toString());
        p.put("createdAt", task.getCreatedAt() == null ? "" : task.getCreatedAt().toString());
        return p;
    }

    /** 写时间线（系统操作 operatorId=0；TaskService.timeline 为私有，此处复刻只增语义） */
    private void timeline(Long taskId, String action, String note) {
        TaskTimeline tl = new TaskTimeline();
        tl.setTaskId(taskId);
        tl.setOperatorId(SYSTEM_OPERATOR_ID);
        tl.setAction(action);
        tl.setNote(note);
        timelineMapper.insert(tl);
    }
}
