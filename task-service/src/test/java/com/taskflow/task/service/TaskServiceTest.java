package com.taskflow.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.RedisUtils;
import com.taskflow.task.client.UserClient;
import com.taskflow.task.config.AuthContext;
import com.taskflow.task.entity.Task;
import com.taskflow.task.mapper.EventOutboxMapper;
import com.taskflow.task.mapper.TaskMapper;
import com.taskflow.task.mapper.TaskTimelineMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * TaskService 状态机与权限规则单元测试（Mockito 全 Mock，不依赖 DB/MQ）。
 *
 * <p>注意：AuthContext 是 ThreadLocal，每个用例设置身份、tearDown 清理。</p>
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskTimelineMapper timelineMapper;
    @Mock
    private EventOutboxMapper outboxMapper;
    @Mock
    private UserClient userClient;
    @Mock
    private RedisUtils redis;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(taskMapper, timelineMapper, outboxMapper, userClient, redis);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /**
     * 以指定身份进入上下文，并让权限缓存返回 taskAdmin 默认权限集。
     *
     * @param userId 用户 id
     * @param perms  Redis 缓存的权限点串
     */
    private void asUser(long userId, String roleKey, String perms) {
        AuthContext.set(userId, roleKey);
        lenient().when(redis.get("auth:perms:" + roleKey)).thenReturn(perms);
    }

    /** taskAdmin 默认权限（PRD 3.3：无 viewAll/manageUser/setPerm） */
    private static final String TASK_ADMIN_PERMS =
            "create,editOwn,deleteOwn,transferOwn,prioOwn,dueOwn,viewAssigned,updateAssigned,transferAssigned,viewStats,exportData";

    /** 造一个任务 */
    private Task task(long id, String status, long creatorId, long assigneeId) {
        Task t = new Task();
        t.setId(id);
        t.setTaskNo("TSK-" + (100000 + id));
        t.setTitle("测试任务");
        t.setStatus(status);
        t.setCreatorId(creatorId);
        t.setAssigneeId(assigneeId);
        t.setPriority("P2");
        t.setDueAt(OffsetDateTime.now().plusDays(3));
        t.setProgress(0);
        return t;
    }

    @Test
    @DisplayName("受理：可见但非处理人被拒（2003）；完全无关用户先被可见性拦截（2001）")
    void acceptByNonAssignee() {
        // 用例 1：创建人（99）可见但不是处理人 → 2003
        asUser(99L, "taskAdmin", TASK_ADMIN_PERMS);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "new", 99L, 3L));
        BizException e = assertThrows(BizException.class, () -> taskService.accept(1L));
        assertEquals(ErrorCode.OPERATOR_MISMATCH, e.getErrorCode());

        // 用例 2：与任务完全无关的用户 → 2001（不区分不存在与不可见，防探测）
        AuthContext.clear();
        asUser(98L, "taskAdmin", TASK_ADMIN_PERMS);
        BizException e2 = assertThrows(BizException.class, () -> taskService.accept(1L));
        assertEquals(ErrorCode.TASK_NOT_FOUND_OR_INVISIBLE, e2.getErrorCode());
    }

    @Test
    @DisplayName("提交验收：存在未完成子任务被拒（2004，含未完成数）")
    void submitWithUnfinishedSubtasks() {
        asUser(3L, "taskAdmin", TASK_ADMIN_PERMS);
        Task t = task(2L, "doing", 1L, 3L);
        when(taskMapper.selectById(2L)).thenReturn(t);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        BizException e = assertThrows(BizException.class, () -> taskService.submitAcceptance(2L));
        assertEquals(ErrorCode.UNFINISHED_SUBTASKS, e.getErrorCode());
        assertEquals(Map.of("unfinishedCount", 2L), e.getDetails());
    }

    @Test
    @DisplayName("验收通过：非创建人被拒（2003）")
    void approveByNonCreator() {
        asUser(3L, "taskAdmin", TASK_ADMIN_PERMS);
        when(taskMapper.selectById(2L)).thenReturn(task(2L, "wait", 1L, 3L));

        BizException e = assertThrows(BizException.class, () -> taskService.approve(2L));
        assertEquals(ErrorCode.OPERATOR_MISMATCH, e.getErrorCode());
    }

    @Test
    @DisplayName("删除：非待办状态被拒（2006）")
    void deleteNonTodo() {
        asUser(1L, "taskAdmin", TASK_ADMIN_PERMS);
        when(taskMapper.selectById(2L)).thenReturn(task(2L, "doing", 1L, 3L));

        BizException e = assertThrows(BizException.class, () -> taskService.delete(2L));
        assertEquals(ErrorCode.DELETE_ONLY_TODO, e.getErrorCode());
    }

    @Test
    @DisplayName("进度值非法：非步进 5 被拒（2009）")
    void invalidProgress() {
        asUser(3L, "taskAdmin", TASK_ADMIN_PERMS);
        BizException e = assertThrows(BizException.class,
                () -> taskService.updateProgress(2L, 47, null));
        assertEquals(ErrorCode.INVALID_PROGRESS, e.getErrorCode());
    }

    @Test
    @DisplayName("可见性：无关用户的任务返回 2001（不区分不存在与不可见）")
    void invisibleTask() {
        asUser(99L, "taskAdmin", TASK_ADMIN_PERMS);
        when(taskMapper.selectById(2L)).thenReturn(task(2L, "doing", 1L, 3L));

        BizException e = assertThrows(BizException.class, () -> taskService.detail(2L));
        assertEquals(ErrorCode.TASK_NOT_FOUND_OR_INVISIBLE, e.getErrorCode());
    }

    @Test
    @DisplayName("admin 对任意任务可行使'自己'类权限（PRD 3.3 超管语义）")
    void adminBypassOwnCheck() {
        asUser(1L, "admin", TASK_ADMIN_PERMS + ",viewAll");
        Task t = task(2L, "wait", 8L, 3L); // 创建人是 8，不是 admin
        when(taskMapper.selectById(2L)).thenReturn(t);

        // admin 代创建人验收通过：不抛异常
        taskService.approve(2L);
        assertEquals("done", t.getStatus());
    }

    @Test
    @DisplayName("待办状态更新进度自动受理（new → doing，PRD 4.1.2 补充规则 2）")
    void progressAutoAccept() {
        asUser(3L, "taskAdmin", TASK_ADMIN_PERMS);
        Task t = task(2L, "new", 1L, 3L);
        when(taskMapper.selectById(2L)).thenReturn(t);

        taskService.updateProgress(2L, 30, "启动");
        assertEquals("doing", t.getStatus());
        assertEquals(30, t.getProgress());
    }
}
