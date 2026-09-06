package com.taskflow.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.RedisUtils;
import com.taskflow.common.event.TaskEvents;
import com.taskflow.task.client.UserClient;
import com.taskflow.task.config.AuthContext;
import com.taskflow.task.entity.EventOutbox;
import com.taskflow.task.entity.Task;
import com.taskflow.task.entity.TaskTimeline;
import com.taskflow.task.mapper.EventOutboxMapper;
import com.taskflow.task.mapper.TaskMapper;
import com.taskflow.task.mapper.TaskTimelineMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务核心服务（PRD 4.1 + 接口文档第 4 章）。
 *
 * <p>职责：任务 CRUD、状态机流转、可见性过滤、操作时间线、事件 outbox。
 * 所有状态变更遵循同一事务模式（架构 4.2）：更新任务 + 写时间线 + 写 outbox，同生共死。</p>
 *
 * <p>可见性规则（PRD 3.4）：admin 或拥有 viewAll 可见全部；否则
 * （有 viewAssigned 且是处理人）或（有 editOwn 且是创建人）。</p>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** 状态键（PRD 4.1.2） */
    public static final String ST_NEW = "new";
    public static final String ST_DOING = "doing";
    public static final String ST_WAIT = "wait";
    public static final String ST_DONE = "done";
    public static final String ST_CLOSE = "close";

    /** 任务类型枚举（PRD 4.1.1） */
    private static final Set<String> TASK_TYPES = Set.of(
            "项目开发", "日常事务", "会议事项", "调研分析", "数据报表", "流程审批");
    /** 优先级枚举 */
    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2", "P3");

    private final TaskMapper taskMapper;
    private final TaskTimelineMapper timelineMapper;
    private final EventOutboxMapper outboxMapper;
    private final UserClient userClient;
    private final RedisUtils redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TaskService(TaskMapper taskMapper, TaskTimelineMapper timelineMapper,
                       EventOutboxMapper outboxMapper, UserClient userClient, RedisUtils redis) {
        this.taskMapper = taskMapper;
        this.timelineMapper = timelineMapper;
        this.outboxMapper = outboxMapper;
        this.userClient = userClient;
        this.redis = redis;
    }

    // ==================== 可见性与权限辅助 ====================

    /** 当前角色已开启的权限点集合（读共享 Redis 缓存） */
    private Set<String> currentPerms() {
        String cached = redis.get("auth:perms:" + AuthContext.getRoleKey());
        return (cached == null || cached.isEmpty()) ? Set.of() : Set.of(cached.split(","));
    }

    /** 是否拥有全部任务可见性（admin 或 viewAll，PRD 3.4 规则 1/2） */
    private boolean canViewAll() {
        return "admin".equals(AuthContext.getRoleKey()) || currentPerms().contains("viewAll");
    }

    /**
     * 可见性校验：不可见统一抛 2001（不区分不存在/不可见，防探测）。
     */
    private Task mustVisible(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND_OR_INVISIBLE);
        }
        if (canViewAll()) {
            return task;
        }
        Long me = AuthContext.getUserId();
        Set<String> perms = currentPerms();
        boolean visible = (perms.contains("viewAssigned") && me.equals(task.getAssigneeId()))
                || (perms.contains("editOwn") && me.equals(task.getCreatorId()));
        if (!visible) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND_OR_INVISIBLE);
        }
        return task;
    }

    /**
     * "自己"类权限点的归属判定（PRD 3.5.3）：admin 对任意任务生效；其余角色仅限本人创建。
     */
    private void checkOwnPermission(Task task, String permKey) {
        if ("admin".equals(AuthContext.getRoleKey())) {
            return;
        }
        if (!currentPerms().contains(permKey)) {
            throw new BizException(ErrorCode.PERMISSION_DENIED,
                    "缺少权限点 " + permKey,
                    Map.of("required", permKey, "roleKey", AuthContext.getRoleKey()));
        }
        if (!AuthContext.getUserId().equals(task.getCreatorId())) {
            throw new BizException(ErrorCode.OPERATOR_MISMATCH, "仅创建人可执行该操作");
        }
    }

    /** 已归档任务只读（PRD 4.1.2 补充规则 3） */
    private void checkNotArchived(Task task) {
        if (ST_CLOSE.equals(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_ARCHIVED_READONLY);
        }
    }

    /** 处理人合法性：Feign 实时校验（taskAdmin/user 角色的在职用户，admin 不可担任） */
    @SuppressWarnings("unchecked")
    private void checkAssignee(Long assigneeId) {
        Map<String, Object> envelope = userClient.getUser(assigneeId);
        Map<String, Object> user = (Map<String, Object>) envelope.get("data");
        if (user == null || "disabled".equals(user.get("status"))
                || "admin".equals(user.get("roleKey"))) {
            throw new BizException(ErrorCode.INVALID_ASSIGNEE);
        }
    }

    // ==================== 创建 ====================

    /**
     * 创建任务（含子任务）。
     *
     * @return 创建后的任务
     */
    @Transactional
    public Task create(String title, String description, String taskType, String priority,
                       Long assigneeId, OffsetDateTime dueAt, Long parentId) {
        Long me = AuthContext.getUserId();

        // 基础校验
        if (!StringUtils.hasText(title) || title.length() > 100) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标题必填且不超过 100 字符");
        }
        if (description != null && description.length() > 2000) {
            throw new BizException(ErrorCode.PARAM_INVALID, "描述不超过 2000 字符");
        }
        if (priority != null && !PRIORITIES.contains(priority)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "优先级非法");
        }
        checkAssignee(assigneeId);

        Task task = new Task();
        // 子任务（PRD 4.1.7）：父任务必须存在且为顶层；类型继承父任务；来源固定"网页"
        if (parentId != null) {
            Task parent = taskMapper.selectById(parentId);
            if (parent == null || parent.getParentId() != null) {
                throw new BizException(ErrorCode.INVALID_PARENT_TASK);
            }
            checkNotArchived(parent);
            task.setParentId(parentId);
            task.setTaskType(parent.getTaskType());
            task.setSource("网页");
        } else {
            if (taskType != null && !TASK_TYPES.contains(taskType)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "任务类型非法");
            }
            task.setTaskType(taskType == null ? "项目开发" : taskType);
            task.setSource("网页");
        }

        task.setTaskNo("TSK-" + taskMapper.nextTaskNo());
        task.setTitle(title);
        task.setDescription(description);
        task.setPriority(priority == null ? "P2" : priority);
        task.setStatus(ST_NEW);
        task.setCreatorId(me);
        task.setAssigneeId(assigneeId);
        // 到期时间默认创建后第 3 天 18:00（东八区，PRD 4.1.1）
        task.setDueAt(dueAt != null ? dueAt : defaultDueAt());
        task.setProgress(0);
        task.setDueReminded(false);
        taskMapper.insert(task);

        timeline(task.getId(), me, "创建任务", null);
        writeOutbox(TaskEvents.TASK_ASSIGNED, Map.of(
                "taskId", task.getId(), "taskNo", task.getTaskNo(), "title", task.getTitle(),
                "assigneeId", assigneeId, "creatorId", me,
                "parentId", parentId == null ? "" : parentId,
                "createdAt", task.getCreatedAt() == null ? OffsetDateTime.now().toString() : task.getCreatedAt().toString()));
        log.info("任务创建: taskNo={}, creator={}, assignee={}", task.getTaskNo(), me, assigneeId);
        return task;
    }

    /** 默认到期时间：第 3 天 18:00（东八区） */
    private static OffsetDateTime defaultDueAt() {
        return LocalDate.now(ZoneOffset.ofHours(8))
                .plusDays(3).atTime(18, 0).atOffset(ZoneOffset.ofHours(8));
    }

    // ==================== 用户快照（姓名/部门解析） ====================

    /**
     * 读 Redis 用户快照（auth-user-service 在启动与用户/部门变更时全量刷新，
     * 键 auth:user:snapshot，架构 3.2 铁律 2）。
     *
     * @return {用户id: {name, account, departmentName, status}}；缓存缺失时返回空表
     */
    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, String>> userSnapshot() {
        String json = redis.get("auth:user:snapshot");
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Map<String, String>> raw = objectMapper.readValue(json, Map.class);
            Map<Long, Map<String, String>> result = new HashMap<>();
            raw.forEach((k, v) -> result.put(Long.valueOf(k), v));
            return result;
        } catch (JsonProcessingException e) {
            log.warn("用户快照解析失败", e);
            return Map.of();
        }
    }

    /** 从快照取用户姓名（取不到回退账号/空） */
    private static String nameOf(Map<Long, Map<String, String>> snapshot, Long userId) {
        Map<String, String> u = snapshot.get(userId);
        return u == null ? "" : u.getOrDefault("name", "");
    }

    /** 从快照取用户部门名 */
    private static String deptOf(Map<Long, Map<String, String>> snapshot, Long userId) {
        Map<String, String> u = snapshot.get(userId);
        return u == null ? "" : u.getOrDefault("departmentName", "");
    }

    /** 任务实体 → 列表/详情响应项（含创建人、处理人姓名与处理人部门） */
    private Map<String, Object> toItem(Task t, Map<Long, Map<String, String>> snapshot) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", t.getId());
        item.put("taskNo", t.getTaskNo());
        item.put("title", t.getTitle());
        item.put("taskType", t.getTaskType());
        item.put("priority", t.getPriority());
        item.put("status", t.getStatus());
        item.put("progress", t.getProgress());
        item.put("creatorId", t.getCreatorId());
        item.put("creatorName", nameOf(snapshot, t.getCreatorId()));
        item.put("assigneeId", t.getAssigneeId());
        item.put("assigneeName", nameOf(snapshot, t.getAssigneeId()));
        item.put("assigneeDepartmentName", deptOf(snapshot, t.getAssigneeId()));
        item.put("dueAt", t.getDueAt() == null ? null : t.getDueAt().toString());
        item.put("source", t.getSource());
        item.put("parentId", t.getParentId());
        item.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        item.put("updatedAt", t.getUpdatedAt() == null ? null : t.getUpdatedAt().toString());
        return item;
    }

    // ==================== 查询 ====================

    /**
     * 任务列表：可见性过滤 + 筛选 + 分页（接口 #19）。
     *
     * @param assigneeDeptId 处理人部门筛选（经 Feign 反查该部门用户 id 集合）
     * @param parentId       父任务 id（查子任务，树形展开用）
     * @param topLevel       true 时只查顶层任务（树形根节点）
     */
    public Page<Map<String, Object>> page(String keyword, String status, String priority, String taskType,
                                          Long creatorId, Long assigneeId, Long assigneeDeptId,
                                          Long parentId, boolean topLevel,
                                          String scope, int page, int size) {
        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<>();

        // ① 可见性过滤（PRD 3.4，始终先生效）
        if (!canViewAll()) {
            Long me = AuthContext.getUserId();
            Set<String> perms = currentPerms();
            boolean canSeeAssigned = perms.contains("viewAssigned");
            boolean canSeeOwn = perms.contains("editOwn");
            if (!canSeeAssigned && !canSeeOwn) {
                qw.eq(Task::getId, -1L); // 两个可见性权限都没有：结果恒空
            } else {
                qw.and(w -> {
                    if (canSeeAssigned) {
                        w.eq(Task::getAssigneeId, me);
                    }
                    if (canSeeOwn) {
                        w.or().eq(Task::getCreatorId, me);
                    }
                });
            }
        }

        // ② 树形参数：子任务展开 / 仅顶层
        qw.eq(parentId != null, Task::getParentId, parentId);
        if (topLevel) {
            qw.isNull(Task::getParentId);
        }

        // ③ 快捷范围（与其他条件 AND 叠加，PRD 4.2.1）
        Long me = AuthContext.getUserId();
        if ("mine".equals(scope)) {
            qw.eq(Task::getCreatorId, me);
        } else if ("assigned".equals(scope)) {
            qw.eq(Task::getAssigneeId, me);
        } else if ("overdue".equals(scope)) {
            qw.in(Task::getStatus, ST_NEW, ST_DOING, ST_WAIT)
                    .lt(Task::getDueAt, OffsetDateTime.now());
        }

        // ④ 处理人部门筛选（Feign 反查部门用户 id 集合；空集合 = 结果为空）
        if (assigneeDeptId != null) {
            List<Long> deptUserIds = lookupUserIds(null, assigneeDeptId);
            if (deptUserIds.isEmpty()) {
                qw.eq(Task::getId, -1L);
            } else {
                qw.in(Task::getAssigneeId, deptUserIds);
            }
        }

        // ⑤ 筛选条件
        qw.eq(StringUtils.hasText(status), Task::getStatus, status)
                .eq(StringUtils.hasText(priority), Task::getPriority, priority)
                .eq(StringUtils.hasText(taskType), Task::getTaskType, taskType)
                .eq(creatorId != null, Task::getCreatorId, creatorId)
                .eq(assigneeId != null, Task::getAssigneeId, assigneeId);
        if (StringUtils.hasText(keyword)) {
            // 关键字匹配编号/标题/描述 + 创建人/处理人姓名（PRD 4.2.1：姓名经 lookup 反查 id）
            List<Long> nameMatchedIds = lookupUserIds(keyword, null);
            qw.and(w -> {
                w.like(Task::getTaskNo, keyword)
                        .or().like(Task::getTitle, keyword)
                        .or().like(Task::getDescription, keyword);
                if (!nameMatchedIds.isEmpty()) {
                    w.or().in(Task::getCreatorId, nameMatchedIds)
                            .or().in(Task::getAssigneeId, nameMatchedIds);
                }
            });
        }
        qw.orderByDesc(Task::getCreatedAt);

        Page<Task> raw = taskMapper.selectPage(new Page<>(page, size), qw);
        Map<Long, Map<String, String>> snapshot = userSnapshot();

        // 批量统计本页任务的子任务数（树形表格的展开箭头精确显示用）
        List<Long> ids = raw.getRecords().stream().map(Task::getId).toList();
        Map<Long, Long> childCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            taskMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task>()
                            .select("parent_id AS parentId", "count(*) AS cnt")
                            .in("parent_id", ids)
                            .groupBy("parent_id"))
                    .forEach(row -> childCounts.put(
                            Long.valueOf(String.valueOf(row.get("parentid"))),
                            Long.valueOf(String.valueOf(row.get("cnt")))));
        }

        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        Map<Long, Long> finalChildCounts = childCounts;
        result.setRecords(raw.getRecords().stream()
                .map(t -> {
                    Map<String, Object> item = toItem(t, snapshot);
                    item.put("hasChildren", finalChildCounts.getOrDefault(t.getId(), 0L) > 0);
                    return item;
                }).collect(java.util.stream.Collectors.toList()));
        return result;
    }

    /** Feign 查用户 id 集合（姓名关键字或部门筛选） */
    @SuppressWarnings("unchecked")
    private List<Long> lookupUserIds(String keyword, Long departmentId) {
        try {
            Map<String, Object> envelope = userClient.lookup(keyword, departmentId);
            List<Map<String, Object>> users = (List<Map<String, Object>>) envelope.get("data");
            if (users == null) {
                return List.of();
            }
            return users.stream().map(u -> Long.valueOf(String.valueOf(u.get("id"))))
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            // 用户服务不可用：姓名/部门筛选降级为不生效（宁缺毋滥地返回空集合会让结果误空，选择跳过该条件）
            log.warn("用户 lookup 调用失败，降级跳过该筛选条件: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 任务详情（接口 #21）：可见性校验 + 时间线倒序 + 姓名解析。
     */
    public Map<String, Object> detail(Long id) {
        Task task = mustVisible(id);
        Map<Long, Map<String, String>> snapshot = userSnapshot();
        List<TaskTimeline> timeline = timelineMapper.selectList(
                new LambdaQueryWrapper<TaskTimeline>()
                        .eq(TaskTimeline::getTaskId, id)
                        .orderByDesc(TaskTimeline::getCreatedAt));
        List<Map<String, Object>> timelineItems = timeline.stream().map(tl -> {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("id", tl.getId());
            item.put("action", tl.getAction());
            item.put("note", tl.getNote());
            item.put("operatorId", tl.getOperatorId());
            item.put("operatorName", nameOf(snapshot, tl.getOperatorId()));
            item.put("createdAt", tl.getCreatedAt() == null ? null : tl.getCreatedAt().toString());
            return item;
        }).collect(java.util.stream.Collectors.toList());
        List<Map<String, Object>> subtaskItems = taskMapper.selectList(
                        new LambdaQueryWrapper<Task>()
                                .eq(Task::getParentId, id)
                                .orderByAsc(Task::getCreatedAt))
                .stream().map(st -> toItem(st, snapshot))
                .collect(java.util.stream.Collectors.toList());
        return Map.of("task", toItem(task, snapshot),
                "timeline", timelineItems, "subtasks", subtaskItems);
    }

    // ==================== 编辑与删除 ====================

    /**
     * 编辑任务（接口 #22，权限点 editOwn）。
     */
    @Transactional
    public Task update(Long id, Map<String, Object> fields) {
        Task task = mustVisible(id);
        checkOwnPermission(task, "editOwn");
        checkNotArchived(task);

        if (fields.get("title") != null) {
            String title = (String) fields.get("title");
            if (!StringUtils.hasText(title) || title.length() > 100) {
                throw new BizException(ErrorCode.PARAM_INVALID, "标题必填且不超过 100 字符");
            }
            task.setTitle(title);
        }
        if (fields.containsKey("description")) {
            String desc = (String) fields.get("description");
            if (desc != null && desc.length() > 2000) {
                throw new BizException(ErrorCode.PARAM_INVALID, "描述不超过 2000 字符");
            }
            task.setDescription(desc);
        }
        if (fields.get("taskType") != null) {
            String type = (String) fields.get("taskType");
            if (!TASK_TYPES.contains(type)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "任务类型非法");
            }
            task.setTaskType(type);
        }
        if (fields.get("priority") != null) {
            changePriority(task, (String) fields.get("priority"));
        }
        if (fields.get("dueAt") != null) {
            changeDueAt(task, OffsetDateTime.parse((String) fields.get("dueAt")));
        }
        if (fields.get("assigneeId") != null) {
            Long newAssignee = Long.valueOf(String.valueOf(fields.get("assigneeId")));
            checkAssignee(newAssignee);
            Long old = task.getAssigneeId();
            task.setAssigneeId(newAssignee);
            timeline(task.getId(), AuthContext.getUserId(), "转派", "编辑变更处理人");
            writeOutbox(TaskEvents.TASK_TRANSFERRED, Map.of(
                    "taskId", task.getId(), "taskNo", task.getTaskNo(),
                    "oldAssigneeId", old, "newAssigneeId", newAssignee,
                    "operatorId", AuthContext.getUserId()));
        }
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    /**
     * 删除任务（接口 #23，权限点 deleteOwn）：仅待办状态，物理删除（PRD 4.1.2）。
     */
    @Transactional
    public void delete(Long id) {
        Task task = mustVisible(id);
        checkOwnPermission(task, "deleteOwn");
        if (!ST_NEW.equals(task.getStatus())) {
            throw new BizException(ErrorCode.DELETE_ONLY_TODO);
        }
        // 物理删除任务与其时间线
        timelineMapper.delete(new LambdaQueryWrapper<TaskTimeline>().eq(TaskTimeline::getTaskId, id));
        taskMapper.deleteById(id);
        log.info("任务删除: taskNo={}, operator={}", task.getTaskNo(), AuthContext.getUserId());
        // 审计日志经 Feign 写 auth-user-service（M2 简化：删除留痕在本服务日志 + 后续里程碑接审计接口）
    }

    // ==================== 状态机动作（PRD 4.1.2）====================

    /**
     * 受理：待办 → 进行中（处理人，updateAssigned）。
     */
    @Transactional
    public Task accept(Long id) {
        Task task = mustVisible(id);
        checkAssigneeOperator(task);
        requireStatus(task, ST_NEW);
        return transit(task, ST_DOING, "受理", null);
    }

    /**
     * 更新进度（处理人，updateAssigned）：不改变状态；待办时自动受理转进行中（PRD 4.1.2 补充 2）。
     */
    @Transactional
    public Task updateProgress(Long id, int progress, String note) {
        if (progress < 0 || progress > 100 || progress % 5 != 0) {
            throw new BizException(ErrorCode.INVALID_PROGRESS);
        }
        Task task = mustVisible(id);
        checkAssigneeOperator(task);
        checkNotArchived(task);
        if (ST_DONE.equals(task.getStatus()) || ST_WAIT.equals(task.getStatus())) {
            throw new BizException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "当前状态不可更新进度", Map.of("currentStatus", task.getStatus(), "requiredStatus", "new/doing"));
        }
        task.setProgress(progress);
        // 待办时更新进度视为受理（PRD 4.1.2 补充规则 2）
        if (ST_NEW.equals(task.getStatus())) {
            task.setStatus(ST_DOING);
        }
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
        timeline(task.getId(), AuthContext.getUserId(), "更新进度",
                "进度 " + progress + "%" + (StringUtils.hasText(note) ? "：" + note : ""));
        writeOutbox(TaskEvents.TASK_STATUS_CHANGED, statusPayload(task, "progress"));
        return task;
    }

    /**
     * 提交验收：进行中 → 待验收（处理人）；有未完成子任务时拒绝（2004，PRD 4.1.7）。
     */
    @Transactional
    public Task submitAcceptance(Long id) {
        Task task = mustVisible(id);
        checkAssigneeOperator(task);
        requireStatus(task, ST_DOING);
        long unfinished = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .eq(Task::getParentId, id)
                .in(Task::getStatus, ST_NEW, ST_DOING));
        if (unfinished > 0) {
            throw new BizException(ErrorCode.UNFINISHED_SUBTASKS,
                    "存在 " + unfinished + " 个未完成子任务，暂不能提交验收",
                    Map.of("unfinishedCount", unfinished));
        }
        return transit(task, ST_WAIT, "提交验收", null);
    }

    /**
     * 验收通过：待验收 → 已完成（创建人，editOwn；admin 可代执行）。
     */
    @Transactional
    public Task approve(Long id) {
        Task task = mustVisible(id);
        checkOwnPermission(task, "editOwn");
        requireStatus(task, ST_WAIT);
        return transit(task, ST_DONE, "验收通过", null);
    }

    /**
     * 验收驳回：待验收 → 进行中（创建人，editOwn；驳回原因必填 ≤500）。
     */
    @Transactional
    public Task reject(Long id, String reason) {
        if (!StringUtils.hasText(reason) || reason.length() > 500) {
            throw new BizException(ErrorCode.PARAM_INVALID, "驳回原因必填且不超过 500 字符");
        }
        Task task = mustVisible(id);
        checkOwnPermission(task, "editOwn");
        requireStatus(task, ST_WAIT);
        return transit(task, ST_DOING, "验收驳回", reason);
    }

    /**
     * 转派（transferOwn / transferAssigned，转派说明必填 ≤200）。
     */
    @Transactional
    public Task transfer(Long id, Long newAssigneeId, String note) {
        if (!StringUtils.hasText(note) || note.length() > 200) {
            throw new BizException(ErrorCode.PARAM_INVALID, "转派说明必填且不超过 200 字符");
        }
        Task task = mustVisible(id);
        checkNotArchived(task);
        // 创建人走 transferOwn，处理人走 transferAssigned（PRD 4.1.3）
        Long me = AuthContext.getUserId();
        boolean isCreator = me.equals(task.getCreatorId());
        boolean isAssignee = me.equals(task.getAssigneeId());
        if (!"admin".equals(AuthContext.getRoleKey())) {
            Set<String> perms = currentPerms();
            boolean allowed = (isCreator && perms.contains("transferOwn"))
                    || (isAssignee && perms.contains("transferAssigned"));
            if (!allowed) {
                throw new BizException(ErrorCode.OPERATOR_MISMATCH, "仅创建人或处理人可转派");
            }
        }
        checkAssignee(newAssigneeId);

        Long old = task.getAssigneeId();
        task.setAssigneeId(newAssigneeId);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
        timeline(task.getId(), me, "转派", note);
        writeOutbox(TaskEvents.TASK_TRANSFERRED, Map.of(
                "taskId", task.getId(), "taskNo", task.getTaskNo(),
                "oldAssigneeId", old, "newAssigneeId", newAssigneeId, "operatorId", me));
        return task;
    }

    /**
     * 调整优先级（prioOwn）。
     */
    @Transactional
    public Task changePriority(Long id, String priority) {
        Task task = mustVisible(id);
        checkOwnPermission(task, "prioOwn");
        checkNotArchived(task);
        changePriority(task, priority);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
        timeline(task.getId(), AuthContext.getUserId(), "调整优先级", "调整为 " + priority);
        return task;
    }

    /**
     * 调整到期时间（dueOwn）。
     */
    @Transactional
    public Task changeDue(Long id, OffsetDateTime dueAt) {
        Task task = mustVisible(id);
        checkOwnPermission(task, "dueOwn");
        checkNotArchived(task);
        changeDueAt(task, dueAt);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
        timeline(task.getId(), AuthContext.getUserId(), "调整到期时间", null);
        return task;
    }

    /**
     * 手动归档：已完成 → 已归档（viewAll 权限，PRD 4.1.2）。
     */
    @Transactional
    public Task archive(Long id) {
        Task task = mustVisible(id);
        requireStatus(task, ST_DONE);
        return transit(task, ST_CLOSE, "手动归档", null);
    }

    // ==================== 内部辅助 ====================

    /** 处理人身份校验（受理/进度/提交验收等处理人动作） */
    private void checkAssigneeOperator(Task task) {
        Set<String> perms = currentPerms();
        if (!perms.contains("updateAssigned")) {
            throw new BizException(ErrorCode.PERMISSION_DENIED, "缺少权限点 updateAssigned",
                    Map.of("required", "updateAssigned", "roleKey", AuthContext.getRoleKey()));
        }
        if (!AuthContext.getUserId().equals(task.getAssigneeId())) {
            throw new BizException(ErrorCode.OPERATOR_MISMATCH, "仅处理人可执行该操作");
        }
    }

    /** 前置状态校验（2002） */
    private void requireStatus(Task task, String required) {
        if (!required.equals(task.getStatus())) {
            throw new BizException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "前置状态不满足", Map.of("currentStatus", task.getStatus(), "requiredStatus", required));
        }
    }

    /** 字段级调整（编辑与快捷操作共用） */
    private void changePriority(Task task, String priority) {
        if (!PRIORITIES.contains(priority)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "优先级非法");
        }
        task.setPriority(priority);
    }

    private void changeDueAt(Task task, OffsetDateTime dueAt) {
        task.setDueAt(dueAt);
    }

    /**
     * 状态流转统一事务模板：更新状态 + 时间线 + outbox 事件（架构 4.2）。
     */
    private Task transit(Task task, String target, String action, String note) {
        String from = task.getStatus();
        task.setStatus(target);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
        timeline(task.getId(), AuthContext.getUserId(), action, note);

        // 语义化通知事件 + 通用状态变更事件（stats 消费）
        String semanticEvent = switch (target) {
            case ST_WAIT -> TaskEvents.TASK_ACCEPTANCE_SUBMITTED;
            case ST_DONE -> TaskEvents.TASK_APPROVED;
            default -> null;
        };
        if ("验收驳回".equals(action)) {
            semanticEvent = TaskEvents.TASK_REJECTED;
        }
        if (semanticEvent != null) {
            Map<String, Object> payload = new HashMap<>(statusPayload(task, from));
            if (note != null) {
                payload.put("reason", note);
            }
            writeOutbox(semanticEvent, payload);
        }
        writeOutbox(TaskEvents.TASK_STATUS_CHANGED, statusPayload(task, from));
        return task;
    }

    /** 状态变更事件载荷（含变更前后状态与关键字段，供 stats 增量聚合） */
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

    /** 写时间线（只增不改） */
    private void timeline(Long taskId, Long operatorId, String action, String note) {
        TaskTimeline tl = new TaskTimeline();
        tl.setTaskId(taskId);
        tl.setOperatorId(operatorId);
        tl.setAction(action);
        tl.setNote(note);
        timelineMapper.insert(tl);
    }

    /** 写本地消息表（与业务同事务；event_id 由 DB 默认 gen_random_uuid() 生成） */
    private void writeOutbox(String eventType, Map<String, Object> payload) {
        EventOutbox outbox = new EventOutbox();
        outbox.setEventType(eventType);
        try {
            outbox.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件载荷序列化失败", e);
        }
        outbox.setDelivered(false);
        outboxMapper.insert(outbox);
    }
}
