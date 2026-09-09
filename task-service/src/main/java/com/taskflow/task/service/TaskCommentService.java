package com.taskflow.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.event.TaskEvents;
import com.taskflow.task.client.UserClient;
import com.taskflow.task.config.AuthContext;
import com.taskflow.task.entity.Task;
import com.taskflow.task.entity.TaskComment;
import com.taskflow.task.mapper.TaskCommentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务评论服务（PRD 4.1.5，接口 #33-35）。
 *
 * <p>权限口径："任务可见即可"评论/查看（复用 TaskService 可见性规则）；
 * 删除仅限评论人本人或 admin（3001）。评论可删不可改，不写操作时间线；
 * 新评论经 outbox 发 task.commented 事件，由 notification-service 通知创建人与处理人。</p>
 */
@Service
public class TaskCommentService {

    private static final Logger log = LoggerFactory.getLogger(TaskCommentService.class);

    private final TaskCommentMapper commentMapper;
    private final TaskService taskService;
    private final UserClient userClient;

    public TaskCommentService(TaskCommentMapper commentMapper, TaskService taskService, UserClient userClient) {
        this.commentMapper = commentMapper;
        this.taskService = taskService;
        this.userClient = userClient;
    }

    /**
     * 评论列表（接口 #33）：任务可见即可，按时间正序，评论人姓名经 Feign 解析。
     */
    public List<Map<String, Object>> list(Long taskId) {
        taskService.requireVisible(taskId);
        List<TaskComment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<TaskComment>()
                        .eq(TaskComment::getTaskId, taskId)
                        .orderByAsc(TaskComment::getCreatedAt)
                        .orderByAsc(TaskComment::getId));
        Map<Long, String> names = userNames();
        return comments.stream().map(c -> toItem(c, names)).collect(Collectors.toList());
    }

    /**
     * 发表评论（接口 #34）：内容 1-500 字符；已归档任务只读（2005）。
     * 同一事务内写 task.commented 事件（通知创建人与处理人，评论者除外）。
     *
     * @return 评论对象 {id, commenterId, commenterName, content, createdAt}
     */
    @Transactional
    public Map<String, Object> add(Long taskId, String content) {
        if (!StringUtils.hasText(content) || content.length() > 500) {
            throw new BizException(ErrorCode.PARAM_INVALID, "评论内容必填且不超过 500 字符");
        }
        Task task = taskService.requireVisible(taskId);
        if (TaskService.ST_CLOSE.equals(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_ARCHIVED_READONLY);
        }
        Long me = AuthContext.getUserId();

        TaskComment comment = new TaskComment();
        comment.setTaskId(taskId);
        comment.setCommenterId(me);
        comment.setContent(content);
        commentMapper.insert(comment);
        // created_at 由 DB 默认值生成，回查补齐响应字段
        comment = commentMapper.selectById(comment.getId());

        // 事件载荷契约（notification-service 消费）：taskId/taskNo/title/creatorId/assigneeId/commenterId
        taskService.emitEvent(TaskEvents.TASK_COMMENTED, Map.of(
                "taskId", task.getId(), "taskNo", task.getTaskNo(), "title", task.getTitle(),
                "creatorId", task.getCreatorId(), "assigneeId", task.getAssigneeId(),
                "commenterId", me));
        log.info("任务评论: taskNo={}, commenter={}", task.getTaskNo(), me);
        return toItem(comment, userNames());
    }

    /**
     * 删除评论（接口 #35）：仅评论人本人或 admin（越权 3001）；物理删除。
     */
    @Transactional
    public void delete(Long commentId) {
        TaskComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "评论不存在");
        }
        // 任务不可见者连评论是否存在都不可探测
        taskService.requireVisible(comment.getTaskId());
        boolean isOwner = AuthContext.getUserId().equals(comment.getCommenterId());
        boolean isAdmin = "admin".equals(AuthContext.getRoleKey());
        if (!isOwner && !isAdmin) {
            throw new BizException(ErrorCode.PERMISSION_DENIED, "仅评论人本人或 admin 可删除评论",
                    Map.of("required", "commenter|admin", "roleKey", AuthContext.getRoleKey()));
        }
        commentMapper.deleteById(commentId);
        log.info("评论删除: commentId={}, operator={}", commentId, AuthContext.getUserId());
    }

    /** 评论实体 → 响应项（含评论人姓名） */
    private Map<String, Object> toItem(TaskComment c, Map<Long, String> names) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", c.getId());
        item.put("commenterId", c.getCommenterId());
        item.put("commenterName", names.getOrDefault(c.getCommenterId(), ""));
        item.put("content", c.getContent());
        item.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        return item;
    }

    /** Feign 反查用户姓名表（失败降级为空表，姓名留空） */
    @SuppressWarnings("unchecked")
    private Map<Long, String> userNames() {
        try {
            Map<String, Object> envelope = userClient.lookup(null, null);
            List<Map<String, Object>> users = (List<Map<String, Object>>) envelope.get("data");
            if (users == null) {
                return Map.of();
            }
            Map<Long, String> names = new HashMap<>();
            users.forEach(u -> names.put(Long.valueOf(String.valueOf(u.get("id"))),
                    String.valueOf(u.get("name"))));
            return names;
        } catch (Exception e) {
            log.warn("用户 lookup 调用失败，姓名解析降级为空: {}", e.getMessage());
            return Map.of();
        }
    }
}
