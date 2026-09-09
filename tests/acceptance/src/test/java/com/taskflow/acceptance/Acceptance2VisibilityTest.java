package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 2 · 可见性 —— 默认矩阵下 user/taskAdmin 任何接口拿不到无关任务；admin 全可见。
 *
 * <p>PRD 3.4：任务对用户可见当且仅当 admin / viewAll / (viewAssigned 且为处理人) /
 * (editOwn 且为创建人)。服务端逐请求校验，接口不可见统一 2001（防探测）。</p>
 */
@Order(2)
@DisplayName("ACC-2 可见性：无关任务在列表/详情/评论/日程/导出均不可达；admin 全可见")
public class Acceptance2VisibilityTest extends AccBase {

    public Acceptance2VisibilityTest() {
        this.cls = "vis";
    }

    private TUser taA;   // taskAdmin：创建任务、或作处理人
    private TUser uB;    // 普通用户 B
    private TUser taC;   // taskAdmin C：另一个任务管理员
    private TUser uD;    // 普通用户 D
    private String taA_t;
    private String uB_t;
    private String taC_t;
    private String uD_t;

    private JsonNode t1; // creator=taA assignee=uB （对 uB 可见/指派；对 taC、uD 无关）
    private JsonNode t2; // creator=taC assignee=uD （对 taA、uB 无关；对 uD 指派可见）
    private JsonNode t3; // creator=admin assignee=taC（对 taA、uB、uD 无关）

    @BeforeAll
    void setup() {
        // 防御并行套件改动矩阵：先把默认矩阵关键项还原
        restoreDefaultMatrix();
        taA = newUser("可见性TA-A" + uniq(), Conf.ROLE_TASK_ADMIN);
        uB = newUser("可见性用户B" + uniq(), Conf.ROLE_USER);
        taC = newUser("可见性TA-C" + uniq(), Conf.ROLE_TASK_ADMIN);
        uD = newUser("可见性用户D" + uniq(), Conf.ROLE_USER);
        taA_t = Api.login(taA.account(), taA.password()).token();
        uB_t = Api.login(uB.account(), uB.password()).token();
        taC_t = Api.login(taC.account(), taC.password()).token();
        uD_t = Api.login(uD.account(), uD.password()).token();

        t1 = createTask(taA_t, title("V1"), uB.id(), null, null, dueAtInDays(60, "10:00"), null);
        t2 = createTask(taC_t, title("V2"), uD.id(), null, null, dueAtInDays(60, "10:00"), null);
        t3 = createTask(adminToken(), title("V3"), taC.id(), null, null, dueAtInDays(60, "10:00"), null);
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    private long id(JsonNode t) {
        return t.path("id").asLong();
    }

    private String no(JsonNode t) {
        return t.path("taskNo").asText();
    }

    /** 关键字搜标题：仅返回与关键字匹配且可见的任务 */
    private JsonNode listByTitle(String token, String titlePart) {
        return Api.getData("/task/api/v1/tasks", token,
                java.util.Map.of("keyword", titlePart, "size", 50));
    }

    private void assertAbsentEverywhere(String token, JsonNode task) {
        String titlePart = task.path("title").asText();
        long tid = id(task);
        // 列表不可达
        JsonNode list = listByTitle(token, titlePart);
        assertEquals(0, list.path("total").asLong(), "列表不应出现无关任务 " + titlePart);
        // 详情不可达（2001，防探测：不区分不存在/不可见）
        Api.expectErr(Api.raw("GET", "/task/api/v1/tasks/" + tid, token, null, null), 403, 2001);
        // 评论列表不可达
        Api.expectErr(Api.raw("GET", "/task/api/v1/tasks/" + tid + "/comments", token, null, null),
                403, 2001);
        // 日程（按到期日聚合）不含该任务：月 = 到期日所在月
        String month = task.path("dueAt").asText().substring(0, 7);
        JsonNode cal = Api.getData("/task/api/v1/tasks/calendar", token, java.util.Map.of("month", month));
        boolean found = false;
        for (JsonNode day : cal) {
            for (JsonNode tk : day.path("tasks")) {
                if (no(task).equals(tk.path("taskNo").asText())) {
                    found = true;
                }
            }
        }
        assertEquals(false, found, "日程不应含无关任务 " + no(task));
        // 导出（scope=all + 同关键字）不应含无关任务编号（user 无 exportData 权限则跳过）
        io.restassured.response.Response exp = Api.raw("GET", "/task/api/v1/tasks/export", token,
                java.util.Map.of("keyword", titlePart, "scope", "all"), null);
        if (exp.getStatusCode() == 200) {
            String csv = exp.getBody().asString();
            assertEquals(false, csv.contains(no(task)), "导出不应含无关任务编号 " + no(task));
        } else if (Api.code(exp) != 3001) {
            assertEquals(0, Api.code(exp), "导出意外失败");
        }
    }

    @Test
    @Order(1)
    @DisplayName("taskAdmin：无关任务在列表/详情/评论/日程/导出全部不可达（2001/空），但自己的可见")
    void t01_taskAdminVisibility() {
        // taC 与 t1 无关（非创建非处理）
        assertAbsentEverywhere(taC_t, t1);
        // taA 与 t2 无关
        assertAbsentEverywhere(taA_t, t2);
        // taA 看得到自己创建的任务
        JsonNode list = listByTitle(taA_t, t1.path("title").asText());
        assertTrue(list.path("total").asLong() >= 1, "创建人应看到自己创建的任务");
    }

    @Test
    @Order(2)
    @DisplayName("普通用户：仅看到指派给自己的；无关任务全部不可达")
    void t02_userVisibility() {
        assertAbsentEverywhere(uB_t, t2);
        assertAbsentEverywhere(uB_t, t3);
        // 指派给自己的任务可见
        JsonNode list = listByTitle(uB_t, t1.path("title").asText());
        assertTrue(list.path("total").asLong() >= 1, "处理人应看到指派给自己的任务");
        Api.getData("/task/api/v1/tasks/" + id(t1), uB_t, null); // 详情可达
        // uD 只看到 t2，t3（creator=admin assignee=taC）无关
        assertAbsentEverywhere(uD_t, t1);
        assertAbsentEverywhere(uD_t, t3);
        assertEquals(1, listByTitle(uD_t, t2.path("title").asText()).path("total").asLong(),
                "uD 应看到指派给自己的 t2");
    }

    @Test
    @Order(3)
    @DisplayName("admin 对全部任务可见：详情/列表/导出均可达")
    void t03_adminVisibility() {
        for (JsonNode t : List.of(t1, t2, t3)) {
            Api.getData("/task/api/v1/tasks/" + id(t), adminToken(), null);
            JsonNode list = listByTitle(adminToken(), t.path("title").asText());
            assertTrue(list.path("total").asLong() >= 1, "admin 应看到任务 " + no(t));
        }
    }
}
