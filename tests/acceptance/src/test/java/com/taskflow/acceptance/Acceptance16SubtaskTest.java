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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 验收条件 16 · 子任务：二级嵌套被拒(2011)；父任务存在未完成子任务提交验收 2004；
 * 列表中子任务带父任务编号（PRD 4.1.7 / 接口文档列表项含 parentTaskNo）；
 * KPI 只计顶层任务。
 */
@Order(16)
@DisplayName("ACC-16 子任务：嵌套拒绝/未完成子任务阻塞验收/父编号展示/KPI 不含子任务")
public class Acceptance16SubtaskTest extends AccBase {

    public Acceptance16SubtaskTest() {
        this.cls = "sub";
    }

    private TUser creator;  // taskAdmin 父任务创建人
    private TUser assignee; // user 处理人
    private String cToken;
    private String aToken;

    @BeforeAll
    void setup() {
        creator = newUser("子任务创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        assignee = newUser("子任务处理人" + uniq(), Conf.ROLE_USER);
        cToken = Api.login(creator.account(), creator.password()).token();
        aToken = Api.login(assignee.account(), assignee.password()).token();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("二级嵌套被拒 2011（API），DB 触发器兜底")
    void t01_noSecondLevel() {
        JsonNode p = createTask(cToken, title("P1"), assignee.id(), "日常事务", "P1",
                dueAtInDays(5, "18:00"), null);
        JsonNode c1 = createTask(cToken, title("P1-C1"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), p.path("id").asLong());
        // 应用层拒绝：子任务下再建子任务
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks", cToken, null,
                Map.of("title", title("P1-C1-C1"), "assigneeId", assignee.id(),
                        "dueAt", dueAtInDays(5, "18:00"), "parentId", c1.path("id").asLong())),
                400, 2011);
        // DB 触发器兜底：直插二级嵌套应失败
        String sql = "INSERT INTO task (task_no,title,priority,status,creator_id,assignee_id,due_at,progress,parent_id)"
                + " VALUES ('TSK-PROBE-DB','db', 'P2','new', ?,?,now(),0,?)";
        try {
            Db.update(Conf.DB_TASK, sql, creator.id(), assignee.id(), c1.path("id").asLong());
            fail("DB 触发器应拒绝二级嵌套插入");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("SQL"),
                    "应得到 SQL 异常（触发器 RAISE）: " + expected.getMessage());
        }
    }

    @Test
    @Order(2)
    @DisplayName("父任务有未完成子任务时提交验收 → 2004（含 unfinishedCount）；子任务完成后解除阻塞")
    void t02_unfinishedChildBlocksAcceptance() {
        JsonNode p = createTask(cToken, title("P2"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long pid = p.path("id").asLong();
        createTask(cToken, title("P2-C1"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), pid);
        // 父任务受理 → 提交验收被 2004 阻塞
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + pid + "/accept", aToken, null, null));
        JsonNode details = Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + pid + "/submit-acceptance",
                aToken, null, null), 400, 2004);
        assertTrue(details.path("unfinishedCount").asInt() >= 1, "2004 details 应含 unfinishedCount");
        // 删除未完成子任务后，父任务可提交验收
        long cid = myId(title("P2-C1"));
        Api.expectOk(Api.raw("DELETE", "/task/api/v1/tasks/" + cid, cToken, null, null));
        JsonNode ok = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + pid + "/submit-acceptance",
                aToken, null, null));
        assertEquals("wait", ok.path("status").asText(), "删除子任务后父任务应可提交验收");
    }

    @Test
    @Order(3)
    @DisplayName("子任务属性：独立编号/类型继承/来源网页；列表项应带父任务编号（PRD 契约）")
    void t03_childFieldsAndParentTaskNo() {
        JsonNode p = createTask(cToken, title("P3"), assignee.id(), "会议事项", "P0",
                dueAtInDays(5, "18:00"), null);
        long pid = p.path("id").asLong();
        JsonNode c = createTask(cToken, title("P3-C1"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), pid);
        assertTrue(!c.path("taskNo").asText().equals(p.path("taskNo").asText()), "子任务编号独立");
        assertEquals("会议事项", c.path("taskType").asText(), "子任务类型应继承父任务");
        assertEquals("网页", c.path("source").asText(), "子任务来源固定网页");
        assertEquals(pid, c.path("parentId").asLong(), "子任务应带父任务 id");
        // 列表/详情项带父任务编号（PRD 4.1.7 + 接口文档 list 项 parentTaskNo）
        JsonNode list = Api.getData("/task/api/v1/tasks", adminToken(),
                Map.of("keyword", title("P3-C1"), "size", 10));
        assertEquals(1, list.path("total").asLong());
        JsonNode item = list.path("list").get(0);
        boolean hasParentTaskNo = item.hasNonNull("parentTaskNo")
                && p.path("taskNo").asText().equals(item.path("parentTaskNo").asText());
        if (!hasParentTaskNo) {
            fail("列表项缺少父任务编号 parentTaskNo（item keys=" + item.fieldNames() + ", 接口文档要求 parentTaskNo=父任务编号）");
        }
    }

    @Test
    @Order(4)
    @DisplayName("KPI 不含子任务：父+子同日创建，统计仅计顶层")
    void t04_kpiExcludesSubtasks() {
        // 独立日期，避开其他数据
        String day = "2026-02-19";
        JsonNode p = createTask(cToken, title("P4"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long pid = p.path("id").asLong();
        JsonNode c = createTask(cToken, title("P4-C1"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), pid);
        Db.update(Conf.DB_TASK,
                "UPDATE task SET created_at=TIMESTAMPTZ '" + day + " 10:00:00+08' WHERE id IN (?,?)", pid,
                c.path("id").asLong());
        Api.expectOk(Api.raw("POST", "/stats/api/v1/rebuild", adminToken(), null, null));
        JsonNode kpi = Api.getData("/stats/api/v1/overview", adminToken(),
                Map.of("range", "custom", "start", day, "end", day)).path("kpi");
        assertEquals(1, kpi.path("total").asInt(), "KPI 任务总数只计顶层（父），子任务不计入: " + kpi);
    }

    private long myId(String title) {
        return Db.scalarLong(Conf.DB_TASK, "SELECT id FROM task WHERE title=?", title);
    }
}
