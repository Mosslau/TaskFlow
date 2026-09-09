package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 14 · 评论：可见即可评论；仅评论人本人与 admin 可删除；新评论通知创建人与处理人（评论者除外）。
 */
@Order(14)
@DisplayName("ACC-14 评论：可见即可评/删除限本人与 admin/通知创建人+处理人")
public class Acceptance14CommentTest extends AccBase {

    public Acceptance14CommentTest() {
        this.cls = "cm";
    }

    private TUser creator;
    private TUser assignee;
    private TUser stranger; // user 无关
    private String cToken;
    private String aToken;
    private String sToken;
    private long taskId;

    @BeforeAll
    void setup() {
        creator = newUser("评论创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        assignee = newUser("评论处理人" + uniq(), Conf.ROLE_USER);
        stranger = newUser("评论无关人" + uniq(), Conf.ROLE_USER);
        cToken = Api.login(creator.account(), creator.password()).token();
        aToken = Api.login(assignee.account(), assignee.password()).token();
        sToken = Api.login(stranger.account(), stranger.password()).token();
        JsonNode t = createTask(cToken, title("CM"), assignee.id(), null, null,
                dueAtInDays(7, "18:00"), null);
        taskId = t.path("id").asLong();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("可见即可评论：处理人/创建人可评；无关用户 2001；内容超限 1001")
    void t01_visibleCanComment() {
        // 处理人评论
        JsonNode c1 = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + taskId + "/comments",
                aToken, null, Map.of("content", "处理人第一条-" + uniq())));
        long c1id = c1.path("id").asLong();
        assertTrue(c1id > 0);
        assertEquals(assignee.id(), c1.path("commenterId").asLong());
        // 创建人评论
        JsonNode c2 = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + taskId + "/comments",
                cToken, null, Map.of("content", "创建人第二条-" + uniq())));
        long c2id = c2.path("id").asLong();
        // 内容空 / 超 500
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + taskId + "/comments",
                aToken, null, Map.of("content", "")), 400, 1001);
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + taskId + "/comments",
                aToken, null, Map.of("content", "长".repeat(501))), 400, 1001);
        // 无关用户：2001（不可见）
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + taskId + "/comments",
                sToken, null, Map.of("content", "越权评论")), 403, 2001);
        // 列表正序含两条（评论人姓名解析）
        JsonNode list = Api.getData("/task/api/v1/tasks/" + taskId + "/comments", cToken, null);
        assertEquals(2, list.size());
        assertEquals(c1.path("id").asLong(), list.get(0).path("id").asLong(), "评论应按时间正序");
        assertTrue(list.get(0).path("commenterName").asText().contains("评论处理人")
                || list.get(0).path("commenterName").asText().equals(assignee.name()));
    }

    @Test
    @Order(2)
    @DisplayName("删除限评论人本人或 admin：他人 3001；admin/本人可删")
    void t02_deleteRules() {
        clearComments();
        JsonNode c1 = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + taskId + "/comments",
                aToken, null, Map.of("content", "待删除-" + uniq())));
        long c1id = c1.path("id").asLong();
        JsonNode c2 = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + taskId + "/comments",
                cToken, null, Map.of("content", "admin删我-" + uniq())));
        long c2id = c2.path("id").asLong();
        // 处理人删除创建人的评论 → 3001
        Api.expectErr(Api.raw("DELETE", "/task/api/v1/comments/" + c2id, aToken, null, null), 403, 3001);
        // 创建人删除处理人的评论 → 3001
        Api.expectErr(Api.raw("DELETE", "/task/api/v1/comments/" + c1id, cToken, null, null), 403, 3001);
        // 本人删除成功
        Api.expectOk(Api.raw("DELETE", "/task/api/v1/comments/" + c1id, aToken, null, null));
        // admin 删除任何评论
        Api.expectOk(Api.raw("DELETE", "/task/api/v1/comments/" + c2id, adminToken(), null, null));
        assertEquals(0, Api.getData("/task/api/v1/tasks/" + taskId + "/comments", aToken, null).size());
    }

    /** 清空该任务评论（admin），保证各用例独立 */
    private void clearComments() {
        JsonNode list = Api.getData("/task/api/v1/tasks/" + taskId + "/comments", cToken, null);
        for (JsonNode c : list) {
            try {
                Api.raw("DELETE", "/task/api/v1/comments/" + c.path("id").asLong(),
                        adminToken(), null, null);
            } catch (Throwable ignore) {
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("新评论通知创建人与处理人（评论者除外，仅站内）")
    void t03_notifyCreatorAndAssignee() {
        // 全新任务避免历史评论事件干扰
        JsonNode t = createTask(cToken, title("CM3"), assignee.id(), null, null,
                dueAtInDays(7, "18:00"), null);
        long id = t.path("id").asLong();
        // 创建人评论 → 处理人收到；创建人（评论者）不收
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/comments",
                cToken, null, Map.of("content", "触发通知-" + uniq())));
        awaitNotifications(id, assignee.id(), "task.commented", 1);
        assertEquals(0, Db.scalarInt(Conf.DB_NOTIFICATION,
                "SELECT count(*) FROM notification WHERE task_id=? AND recipient_id=? AND event_type=?",
                id, creator.id(), "task.commented"), "评论者本人不应收到通知");
        // 处理人评论 → 创建人收到；处理人（评论者）不收（仍只有前一条）
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/comments",
                aToken, null, Map.of("content", "处理人评论通知-" + uniq())));
        awaitNotifications(id, creator.id(), "task.commented", 1);
        assertEquals(1, Db.scalarInt(Conf.DB_NOTIFICATION,
                "SELECT count(*) FROM notification WHERE task_id=? AND recipient_id=? AND event_type=?",
                id, assignee.id(), "task.commented"), "处理人评论自己不应新增通知");
    }
}
