package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验收条件 9 · 超管操作：admin 可对任意任务执行编辑、删除（待办）、转派、调整优先级/到期、验收与驳回；
 * admin 不可被指派为处理人（2007）。
 */
@Order(9)
@DisplayName("ACC-9 超管操作：admin 对任意任务全操作；不可被指派 2007")
public class Acceptance9SuperAdminOpsTest extends AccBase {

    public Acceptance9SuperAdminOpsTest() {
        this.cls = "adm";
    }

    private TUser ta;      // 任务创建人
    private TUser u1;      // 处理人 1
    private TUser u2;      // 处理人 2（转派目标）
    private String ta_t;
    private String u1_t;
    private String u2_t;

    @BeforeAll
    void setup() {
        ta = newUser("超管测试TA" + uniq(), Conf.ROLE_TASK_ADMIN);
        u1 = newUser("超管测试U1" + uniq(), Conf.ROLE_USER);
        u2 = newUser("超管测试U2" + uniq(), Conf.ROLE_USER);
        ta_t = Api.login(ta.account(), ta.password()).token();
        u1_t = Api.login(u1.account(), u1.password()).token();
        u2_t = Api.login(u2.account(), u2.password()).token();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("admin 编辑/优先级/到期/转派他人创建的任务")
    void t01_adminEditTransferPriorityDue() {
        JsonNode t = createTask(ta_t, title("A1"), u1.id(), "项目开发", "P2",
                dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();
        // 编辑标题/描述
        JsonNode upd = Api.expectOk(Api.raw("PUT", "/task/api/v1/tasks/" + id, adminToken(), null,
                Map.of("title", title("A1-edit"))));
        assertEquals(title("A1-edit"), upd.path("title").asText());
        // 调整优先级
        JsonNode p1 = Api.expectOk(Api.raw("PATCH", "/task/api/v1/tasks/" + id + "/priority",
                adminToken(), null, Map.of("priority", "P0")));
        assertEquals("P0", p1.path("priority").asText());
        // 调整到期时间
        JsonNode due = Api.expectOk(Api.raw("PATCH", "/task/api/v1/tasks/" + id + "/due",
                adminToken(), null, Map.of("dueAt", "2027-03-01T10:00:00+08:00")));
        assertEquals("2027-03-01T10:00:00+08:00", due.path("dueAt").asText());
        // 转派给 u2（转派说明必填）
        JsonNode tr = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/transfer",
                adminToken(), null, Map.of("newAssigneeId", u2.id(), "note", "admin 转派说明")));
        assertEquals(u2.id(), tr.path("assigneeId").asLong(), "转派后处理人应为 u2");
    }

    @Test
    @Order(2)
    @DisplayName("admin 验收通过/驳回他人任务（代执行）；删除待办；不可指派 admin 2007")
    void t02_adminApproveRejectDeleteNotAssignee() {
        // 验收通过
        JsonNode t = createTask(ta_t, title("A2"), u1.id(), null, null, dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", u1_t, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/submit-acceptance", u1_t, null, null));
        JsonNode app = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/approve",
                adminToken(), null, null));
        assertEquals("done", app.path("status").asText());

        // 验收驳回（admin 代执行）
        JsonNode t2 = createTask(ta_t, title("A3"), u1.id(), null, null, dueAtInDays(5, "18:00"), null);
        long id2 = t2.path("id").asLong();
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id2 + "/accept", u1_t, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id2 + "/submit-acceptance", u1_t, null, null));
        JsonNode rj = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id2 + "/reject",
                adminToken(), null, Map.of("reason", "admin 代驳回")));
        assertEquals("doing", rj.path("status").asText());

        // admin 删除他人待办任务
        JsonNode t3 = createTask(ta_t, title("A4"), u1.id(), null, null, dueAtInDays(5, "18:00"), null);
        long id3 = t3.path("id").asLong();
        Api.expectOk(Api.raw("DELETE", "/task/api/v1/tasks/" + id3, adminToken(), null, null));
        Api.expectErr(Api.raw("GET", "/task/api/v1/tasks/" + id3, adminToken(), null, null), 403, 2001);

        // admin 不可被指派：创建 2007、转派 2007、编辑指派 2007
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks", adminToken(), null,
                Map.of("title", title("A5"), "assigneeId", 1L, "dueAt", dueAtInDays(5, "18:00"))),
                400, 2007);
        JsonNode t5 = createTask(ta_t, title("A6"), u1.id(), null, null, dueAtInDays(5, "18:00"), null);
        long id5 = t5.path("id").asLong();
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id5 + "/transfer", ta_t, null,
                Map.of("newAssigneeId", 1L, "note", "转给admin")), 400, 2007);
        Api.expectErr(Api.raw("PUT", "/task/api/v1/tasks/" + id5, adminToken(), null,
                Map.of("assigneeId", 1L)), 400, 2007);
    }

    @Test
    @Order(3)
    @DisplayName("admin 转派他人任务到 u2 后原任务归属变更（时间线留痕）")
    void t03_transferByAdminTimeline() {
        JsonNode t = createTask(ta_t, title("A7"), u1.id(), null, null, dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/transfer", adminToken(), null,
                Map.of("newAssigneeId", u2.id(), "note", "转派留痕")));
        JsonNode tl = detail(adminToken(), id).path("timeline");
        boolean has = false;
        for (JsonNode n : tl) {
            if ("转派".equals(n.path("action").asText())
                    && "转派留痕".equals(n.path("note").asText())
                    && n.path("operatorName").asText().equals("系统管理员")) {
                has = true;
            }
        }
        if (!has) {
            throw new AssertionError("转派时间线应由系统管理员操作且含说明: " + tl);
        }
    }
}
