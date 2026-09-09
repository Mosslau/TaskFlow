package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 1 · 任务管理 —— 状态机。
 *
 * <p>PRD 4.1.2 的 8 条流转（创建/受理/提交验收/验收通过/验收驳回/手动归档/自动归档/删除）：
 * 每条只允许指定操作人执行；非法流转服务端拒绝 2002；每次操作在时间线留痕。
 * 自动归档单独落在 {@link Acceptance4AutoArchiveTest}（依赖定时任务触发）。</p>
 */
@Order(1)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ACC-1 状态机：8 条流转 + 操作人约束 + 非法流转 2002 + 时间线留痕")
public class Acceptance1StateMachineTest extends AccBase {

    public Acceptance1StateMachineTest() {
        this.cls = "sm";
    }

    private TUser creator;      // taskAdmin，任务创建人
    private TUser assignee;     // user，处理人
    private TUser stranger;     // taskAdmin，与任务无关（但能创建任务）
    private String cToken;
    private String aToken;
    private String sToken;

    @BeforeAll
    void setup() {
        creator = newUser("状态机创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        assignee = newUser("状态机处理人" + uniq(), Conf.ROLE_USER);
        stranger = newUser("状态机无关人" + uniq(), Conf.ROLE_TASK_ADMIN);
        cToken = Api.login(creator.account(), creator.password()).token();
        aToken = Api.login(assignee.account(), assignee.password()).token();
        sToken = Api.login(stranger.account(), stranger.password()).token();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    // ---------- 流转 1：创建（[*]→new），仅具 create 权限（admin/taskAdmin）可执行 ----------

    @Test
    @Order(1)
    @DisplayName("创建：taskAdmin/admin 成功；普通 user 3001；时间线[创建任务]")
    void t01_createPerm() {
        // user 无 create 权限
        JsonNode d = Api.expectErr(Api.raw("POST", "/task/api/v1/tasks", aToken, null,
                Map.of("title", title("C1-user"), "assigneeId", assignee.id(),
                        "dueAt", dueAtInDays(5, "18:00"))), 403, 3001);
        assertTrue(d.path("required").asText().contains("create"), "details 应含缺少权限点");

        // taskAdmin 创建成功
        JsonNode t1 = createTask(cToken, title("C1-ok"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        assertEquals("new", t1.path("status").asText());
        assertTrue(t1.path("taskNo").asText().startsWith("TSK-"));
        List<String> acts = timelineActions(cToken, t1.path("id").asLong());
        assertTrue(acts.contains("创建任务"), "时间线应含创建任务: " + acts);

        // admin 创建成功
        JsonNode t2 = createTask(adminToken(), title("C1-admin"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        assertEquals("new", t2.path("status").asText());
    }

    // ---------- 流转 2：受理（new→doing），仅处理人 ----------

    @Test
    @Order(2)
    @DisplayName("受理：处理人成功；非处理人 2003/无关 2001；重复受理 2002；时间线[受理]")
    void t02_accept() {
        JsonNode t = createTask(cToken, title("C2"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();

        // 无关 taskAdmin 操作 → 2001（任务不可见）
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", sToken, null, null),
                403, 2001);
        // 创建人（非处理人）受理 → 2003
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", cToken, null, null),
                400, 2003);
        // 处理人受理成功
        JsonNode ok = actionOk(aToken, id, "/accept", null);
        assertEquals("doing", ok.path("status").asText());
        // 重复受理 → 2002（前置状态必须为待办）
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", aToken, null, null),
                400, 2002);
        assertTrue(timelineActions(aToken, id).contains("受理"), "时间线应含受理");
    }

    // ---------- 流转 3：提交验收（doing→wait），仅处理人 ----------

    @Test
    @Order(3)
    @DisplayName("提交验收：处理人成功；非处理人 2003；非进行中 2002；时间线[提交验收]")
    void t03_submitAcceptance() {
        JsonNode t = createTask(cToken, title("C3"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();
        // 待办直接提交验收 → 2002（须先受理）
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/submit-acceptance", aToken, null, null),
                400, 2002);
        actionOk(aToken, id, "/accept", null);
        // 创建人（非处理人）提交 → 2003
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/submit-acceptance", cToken, null, null),
                400, 2003);
        // 处理人提交成功
        JsonNode ok = actionOk(aToken, id, "/submit-acceptance", null);
        assertEquals("wait", ok.path("status").asText());
        assertTrue(timelineActions(aToken, id).contains("提交验收"));
    }

    // ---------- 流转 4：更新进度（含待办自动受理），仅处理人 ----------

    @Test
    @Order(4)
    @DisplayName("更新进度：处理人成功（待办自动受理→doing）；进度值非法 2009；非处理人 2003")
    void t04_progress() {
        JsonNode t = createTask(cToken, title("C4"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();
        // 待办直接更新进度 → 自动受理为 doing
        JsonNode ok = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/progress",
                aToken, null, Map.of("progress", 30, "note", "进展A")));
        assertEquals("doing", ok.path("status").asText());
        assertEquals(30, ok.path("progress").asInt());
        // 非法进度
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/progress",
                aToken, null, Map.of("progress", 33, "note", "")), 400, 2009);
        // 创建人非处理人更新 → 2003
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/progress",
                cToken, null, Map.of("progress", 40, "note", "")), 400, 2003);
        // 时间线：自动受理以"更新进度"留痕（PRD 4.1.4 必录动作为更新进度；状态自动转进行中）
        List<String> acts = timelineActions(aToken, id);
        assertTrue(acts.contains("更新进度"), "时间线应含更新进度");
        assertEquals("doing", detail(aToken, id).path("task").path("status").asText(),
                "待办更新进度应自动受理为进行中");
    }

    // ---------- 流转 5：验收通过（wait→done），仅创建人（admin 可代执行） ----------

    @Test
    @Order(5)
    @DisplayName("验收通过：创建人成功；处理人 3001；admin 代执行成功；非法 2002；时间线[验收通过]")
    void t05_approve() {
        // 创建人验收通过
        JsonNode t1 = createTask(cToken, title("C5a"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        actionOk(aToken, t1.path("id").asLong(), "/accept", null);
        actionOk(aToken, t1.path("id").asLong(), "/submit-acceptance", null);
        JsonNode ok = actionOk(cToken, t1.path("id").asLong(), "/approve", null);
        assertEquals("done", ok.path("status").asText());
        assertTrue(timelineActions(cToken, t1.path("id").asLong()).contains("验收通过"));

        // 处理人（无 editOwn）→ 3001
        JsonNode t2 = createTask(cToken, title("C5b"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id2 = t2.path("id").asLong();
        actionOk(aToken, id2, "/accept", null);
        actionOk(aToken, id2, "/submit-acceptance", null);
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id2 + "/approve", aToken, null, null),
                403, 3001);

        // admin 代创建人验收通过
        actionOk(adminToken(), id2, "/approve", null);
        assertEquals("done", detail(cToken, id2).path("task").path("status").asText());

        // 已完成任务再验收 → 2002
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id2 + "/approve", adminToken(), null, null),
                400, 2002);
    }

    // ---------- 流转 6：验收驳回（wait→doing），仅创建人（admin 可代执行），驳回原因必填 ----------

    @Test
    @Order(6)
    @DisplayName("验收驳回：创建人成功并回 doing；原因必填 1001；admin 代执行；非法 2002；时间线[验收驳回]含原因")
    void t06_reject() {
        JsonNode t = createTask(cToken, title("C6"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();
        actionOk(aToken, id, "/accept", null);
        actionOk(aToken, id, "/submit-acceptance", null);
        // 缺原因 → 1001（参数校验）
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/reject", cToken, null,
                Map.of("reason", "")), 400, 1001);
        // 处理人（assignee）驳回 → 3001（无 editOwn）
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/reject", aToken, null,
                Map.of("reason", "原因")), 403, 3001);
        // 创建人驳回成功
        String reason = "验收标准不符-ACC1";
        JsonNode ok = actionOk(cToken, id, "/reject", Map.of("reason", reason));
        assertEquals("doing", ok.path("status").asText());
        // 时间线驳回留痕且含原因
        JsonNode tl = detail(cToken, id).path("timeline");
        boolean found = false;
        for (JsonNode n : tl) {
            if ("验收驳回".equals(n.path("action").asText()) && reason.equals(n.path("note").asText())) {
                found = true;
            }
        }
        assertTrue(found, "时间线应含驳回原因");

        // admin 代驳回
        JsonNode t2 = createTask(cToken, title("C6b"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id2 = t2.path("id").asLong();
        actionOk(aToken, id2, "/accept", null);
        actionOk(aToken, id2, "/submit-acceptance", null);
        actionOk(adminToken(), id2, "/reject", Map.of("reason", "admin代驳回"));
        assertEquals("doing", detail(cToken, id2).path("task").path("status").asText());

        // 待办任务驳回 → 2002
        JsonNode t3 = createTask(cToken, title("C6c"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + t3.path("id").asLong() + "/reject",
                cToken, null, Map.of("reason", "x")), 400, 2002);
    }

    // ---------- 流转 7：手动归档（done→close），仅拥有 viewAll（默认 admin） ----------

    @Test
    @Order(7)
    @DisplayName("手动归档：admin 成功；无 viewAll 的 taskAdmin 3001；非完成态 2002；时间线[手动归档]")
    void t07_manualArchive() {
        JsonNode t = createTask(cToken, title("C7"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();
        actionOk(aToken, id, "/accept", null);
        actionOk(aToken, id, "/submit-acceptance", null);
        actionOk(cToken, id, "/approve", null);

        // taskAdmin（无 viewAll）归档 → 3001
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/archive", cToken, null, null),
                403, 3001);
        // admin 归档成功
        JsonNode ok = actionOk(adminToken(), id, "/archive", null);
        assertEquals("close", ok.path("status").asText());
        assertTrue(timelineActions(adminToken(), id).contains("手动归档"));

        // 进行中任务归档 → 2002
        JsonNode t2 = createTask(cToken, title("C7b"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        actionOk(aToken, t2.path("id").asLong(), "/accept", null);
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + t2.path("id").asLong() + "/archive",
                adminToken(), null, null), 400, 2002);
    }

    // ---------- 流转 8：删除（new→物理删除），仅创建人（admin 可代执行）；非待办 2006 ----------

    @Test
    @Order(8)
    @DisplayName("删除：创建人成功；可见但非创建人 2003；无关 2001；admin 代执行成功；非待办删除 2006")
    void t08_delete() {
        // 无关 taskAdmin 删除 → 2001（任务不可见，防探测优先）
        JsonNode t0 = createTask(cToken, title("C8-x"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        Api.expectErr(Api.raw("DELETE", "/task/api/v1/tasks/" + t0.path("id").asLong(),
                sToken, null, null), 403, 2001);

        // 可见但非创建人（任务管理员角色=处理人，具 deleteOwn）删除 → 2003
        JsonNode t0b = createTask(cToken, title("C8-xb"), stranger.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        Api.expectErr(Api.raw("DELETE", "/task/api/v1/tasks/" + t0b.path("id").asLong(),
                sToken, null, null), 400, 2003);

        // 创建人删除待办成功（物理删除，详情变 2001）
        JsonNode t1 = createTask(cToken, title("C8-del"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id1 = t1.path("id").asLong();
        Api.expectOk(Api.raw("DELETE", "/task/api/v1/tasks/" + id1, cToken, null, null));
        Api.expectErr(Api.raw("GET", "/task/api/v1/tasks/" + id1, adminToken(), null, null),
                403, 2001);

        // admin 代删除他人待办成功
        JsonNode t2 = createTask(cToken, title("C8-adm"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id2 = t2.path("id").asLong();
        Api.expectOk(Api.raw("DELETE", "/task/api/v1/tasks/" + id2, adminToken(), null, null));
        Api.expectErr(Api.raw("GET", "/task/api/v1/tasks/" + id2, adminToken(), null, null),
                403, 2001);

        // 非待办删除 → 2006（进行中），创建人与 admin 均不例外
        JsonNode t3 = createTask(cToken, title("C8-doing"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id3 = t3.path("id").asLong();
        actionOk(aToken, id3, "/accept", null);
        Api.expectErr(Api.raw("DELETE", "/task/api/v1/tasks/" + id3, cToken, null, null),
                400, 2006);
        Api.expectErr(Api.raw("DELETE", "/task/api/v1/tasks/" + id3, adminToken(), null, null),
                400, 2006);

        // 非待办任务的时间线仍在（删除只作用于待办；进行中任务留痕验证）
        assertTrue(timelineActions(aToken, id3).contains("受理"));
    }

    // ---------- 时间线只增不改的语义兜底 ----------

    @Test
    @Order(9)
    @DisplayName("完整链路时间线：受理→进度→提交→驳回→再提交→通过 全部留痕且倒序")
    void t09_fullTimeline() {
        JsonNode t = createTask(cToken, title("C9"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();
        actionOk(aToken, id, "/accept", null);
        actionOk(aToken, id, "/progress", Map.of("progress", 60, "note", "过半"));
        actionOk(aToken, id, "/submit-acceptance", null);
        actionOk(cToken, id, "/reject", Map.of("reason", "补验收说明"));
        actionOk(aToken, id, "/submit-acceptance", null);
        actionOk(cToken, id, "/approve", null);

        JsonNode tl = detail(cToken, id).path("timeline");
        java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        tl.forEach(n -> actions.add(n.path("action").asText()));
        assertTrue(actions.contains("创建任务") && actions.contains("受理")
                        && actions.contains("更新进度") && actions.contains("提交验收")
                        && actions.contains("验收驳回") && actions.contains("验收通过"),
                "时间线应包含全部动作: " + actions);
        // 倒序：首条应为最后动作（验收通过）
        assertEquals("验收通过", actions.get(0), "时间线应按时间倒序: " + actions);
        // 时间线总数 = 6 动作 + 创建
        assertEquals(7, actions.size(), "时间线条目数不符: " + actions);
        assertFalse(actions.contains("自动归档"), "本任务不应有自动归档");
    }
}
