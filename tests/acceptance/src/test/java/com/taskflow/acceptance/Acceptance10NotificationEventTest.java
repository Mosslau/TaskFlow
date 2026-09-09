package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 10 · 通知（事件 1-5、8：任务指派/转派/提交验收/验收通过/验收驳回/新评论）：
 * 站内消息 + 邮件记录双通道（事件 8 仅站内）；到期/逾期提醒事件 6/7 见
 * {@link Acceptance10ReminderJobTest}（依赖定时扫描，需临时 cron）。
 */
@Order(10)
@DisplayName("ACC-10 通知：指派/转派/提交验收/通过/驳回/评论 → 站内+邮件（评论仅站内）")
public class Acceptance10NotificationEventTest extends AccBase {

    public Acceptance10NotificationEventTest() {
        this.cls = "nt";
    }

    private TUser creator;  // taskAdmin（创建人/验收人）
    private TUser assignee; // user（处理人 1）
    private TUser user2;    // user（处理人 2：转派目标）
    private String cToken;
    private String aToken;
    private String u2Token;

    @BeforeAll
    void setup() {
        creator = newUser("通知创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        assignee = newUser("通知处理人" + uniq(), Conf.ROLE_USER);
        user2 = newUser("通知转派人" + uniq(), Conf.ROLE_USER);
        cToken = Api.login(creator.account(), creator.password()).token();
        aToken = Api.login(assignee.account(), assignee.password()).token();
        u2Token = Api.login(user2.account(), user2.password()).token();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("事件1 任务创建并指派：处理人收站内 + 邮件（任务指派）")
    void t01_assigned() {
        JsonNode t = createTask(cToken, title("NT1"), assignee.id(), null, null,
                dueAtInDays(6, "18:00"), null);
        long id = t.path("id").asLong();
        awaitNotifications(id, assignee.id(), "task.assigned", 1);
        awaitMail(id, assignee.email(), "任务指派", t.path("taskNo").asText());
        // 摘要含标题
        JsonNode list = Api.getData("/notification/api/v1/notifications", aToken,
                Map.of("page", 1, "size", 50));
        boolean found = false;
        for (JsonNode n : list.path("list")) {
            if ("task.assigned".equals(n.path("eventType").asText())
                    && n.path("taskNo").asText().equals(t.path("taskNo").asText())) {
                found = true;
            }
        }
        assertTrue(found, "处理人站内应有指派消息");
    }

    @Test
    @Order(2)
    @DisplayName("事件2 转派：新处理人收站内 + 邮件（任务转派）")
    void t02_transferred() {
        JsonNode t = createTask(cToken, title("NT2"), assignee.id(), null, null,
                dueAtInDays(6, "18:00"), null);
        long id = t.path("id").asLong();
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/transfer", cToken, null,
                Map.of("newAssigneeId", user2.id(), "note", "转派通知验证")));
        awaitNotifications(id, user2.id(), "task.transferred", 1);
        awaitMail(id, user2.email(), "任务转派", t.path("taskNo").asText());
    }

    @Test
    @Order(3)
    @DisplayName("事件3/4 提交验收→创建人；验收通过→处理人（均含邮件）")
    void t03_submitAndApprove() {
        JsonNode t = createTask(cToken, title("NT3"), assignee.id(), null, null,
                dueAtInDays(6, "18:00"), null);
        long id = t.path("id").asLong();
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", aToken, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/submit-acceptance", aToken, null, null));
        // 事件3 → 创建人
        awaitNotifications(id, creator.id(), "task.acceptance.submitted", 1);
        awaitMail(id, creator.email(), "提交验收", t.path("taskNo").asText());
        // 事件4 → 处理人
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/approve", cToken, null, null));
        awaitNotifications(id, assignee.id(), "task.approved", 1);
        awaitMail(id, assignee.email(), "验收通过", t.path("taskNo").asText());
    }

    @Test
    @Order(4)
    @DisplayName("事件5 验收驳回：处理人站内含原因 + 邮件（验收驳回）")
    void t04_rejected() {
        JsonNode t = createTask(cToken, title("NT4"), assignee.id(), null, null,
                dueAtInDays(6, "18:00"), null);
        long id = t.path("id").asLong();
        String reason = "通知驳回原因" + uniq();
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", aToken, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/submit-acceptance", aToken, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/reject", cToken, null,
                Map.of("reason", reason)));
        awaitNotifications(id, assignee.id(), "task.rejected", 1);
        awaitMail(id, assignee.email(), "验收驳回", t.path("taskNo").asText());
        // 站内摘要含原因
        JsonNode list = Api.getData("/notification/api/v1/notifications", aToken,
                Map.of("page", 1, "size", 50));
        boolean found = false;
        for (JsonNode n : list.path("list")) {
            if ("task.rejected".equals(n.path("eventType").asText())
                    && n.path("taskNo").asText().equals(t.path("taskNo").asText())
                    && n.path("summary").asText().contains(reason)) {
                found = true;
            }
        }
        assertTrue(found, "驳回站内消息应含原因");
    }

    @Test
    @Order(5)
    @DisplayName("事件8 新评论：创建人与处理人收站内（评论者除外），不发邮件")
    void t05_commented() {
        JsonNode t = createTask(cToken, title("NT5"), assignee.id(), null, null,
                dueAtInDays(6, "18:00"), null);
        long id = t.path("id").asLong();
        // 创建人（评论者）评论 → 处理人接收
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/comments", cToken, null,
                Map.of("content", "创建人评论-" + uniq())));
        awaitNotifications(id, assignee.id(), "task.commented", 1);
        // 评论者（创建人本人）不应收到自己的评论消息
        int before = countNotif(id, creator.id(), "task.commented");
        assertEquals(0, before, "评论者本人不应收到评论通知");
        // 处理人评论 → 创建人接收
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/comments", aToken, null,
                Map.of("content", "处理人评论-" + uniq())));
        awaitNotifications(id, creator.id(), "task.commented", 1);
        // 事件 8 仅站内：无对应邮件记录（等待一小段确认无新评论邮件）
        int mailBefore = Db.scalarInt(Conf.DB_NOTIFICATION,
                "SELECT count(*) FROM mail_record WHERE task_id=? AND subject LIKE ?", id, "%新评论%");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int mailAfter = Db.scalarInt(Conf.DB_NOTIFICATION,
                "SELECT count(*) FROM mail_record WHERE task_id=? AND subject LIKE ?", id, "%新评论%");
        assertEquals(mailBefore, mailAfter, "评论事件不应产生邮件记录");
    }

    // ---------- 工具 ----------

    private void awaitMail(long taskId, String recipient, String subjectContain, String taskNo) {
        Api.await(Conf.EVENT_WAIT.toMillis(), 1000, () -> !Db.rows(Conf.DB_NOTIFICATION,
                "SELECT 1 FROM mail_record WHERE task_id=? AND recipient=? AND subject LIKE ? LIMIT 1",
                taskId, recipient, "%" + subjectContain + "%" + taskNo + "%").isEmpty(),
                "等待邮件记录 task=" + taskId + " subject=" + subjectContain);
    }

    private int countNotif(long taskId, long recipient, String type) {
        return Db.scalarInt(Conf.DB_NOTIFICATION,
                "SELECT count(*) FROM notification WHERE task_id=? AND recipient_id=? AND event_type=?",
                taskId, recipient, type);
    }
}
