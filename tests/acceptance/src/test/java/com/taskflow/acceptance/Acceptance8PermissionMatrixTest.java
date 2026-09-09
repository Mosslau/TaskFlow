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
 * 验收条件 8 · 权限矩阵：修改矩阵后目标用户下一次请求即生效；每次修改在审计日志可查；
 * admin 的 manageUser / setPerm 不可关闭（3009）。
 */
@Order(8)
@DisplayName("ACC-8 权限矩阵：改后下一请求生效 + 审计日志 + admin 锁项 3009")
public class Acceptance8PermissionMatrixTest extends AccBase {

    public Acceptance8PermissionMatrixTest() {
        this.cls = "pm";
    }

    private TUser u;       // 普通用户（user 角色默认无 create）
    private TUser assignee;
    private String uToken;
    private String assigneeToken;

    @BeforeAll
    void setup() {
        restoreDefaultMatrix();
        u = newUser("矩阵普通用户" + uniq(), Conf.ROLE_USER);
        assignee = newUser("矩阵处理人" + uniq(), Conf.ROLE_USER);
        uToken = Api.login(u.account(), u.password()).token();
        assigneeToken = Api.login(assignee.account(), assignee.password()).token();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("admin 的 manageUser / setPerm 不可关闭 → 3009")
    void t01_adminLockedPerms() {
        Api.expectErr(Api.raw("PUT", "/auth/api/v1/permissions/matrix", adminToken(), null,
                Map.of("roleKey", "admin", "permissionKey", "manageUser", "enabled", false)), 400, 3009);
        Api.expectErr(Api.raw("PUT", "/auth/api/v1/permissions/matrix", adminToken(), null,
                Map.of("roleKey", "admin", "permissionKey", "setPerm", "enabled", false)), 400, 3009);
        // 置回 true 无副作用
        Api.expectOk(Api.raw("PUT", "/auth/api/v1/permissions/matrix", adminToken(), null,
                Map.of("roleKey", "admin", "permissionKey", "manageUser", "enabled", true)));
        assertTrue(matrixCell("admin", "manageUser"), "admin.manageUser 应保持开启");
        assertTrue(matrixCell("admin", "setPerm"), "admin.setPerm 应保持开启");
    }

    @Test
    @Order(2)
    @DisplayName("改矩阵后目标用户下一次请求即生效（user.create 开→建任务成功，关→3001）")
    void t02_matrixChangeEffectiveImmediately() {
        // 默认 user 无 create：先确认创建被拒
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks", uToken, null,
                Map.of("title", title("PM1"), "assigneeId", assignee.id(), "dueAt", dueAtInDays(7, "18:00"))),
                403, 3001);
        // 授予 user.create
        setMatrixCell("user", "create", true);
        JsonNode pm2 = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks", uToken, null,
                Map.of("title", title("PM2"), "assigneeId", assignee.id(), "dueAt", dueAtInDays(7, "18:00"))));
        registerTask(pm2.path("id").asLong());
        // 收回 user.create → 下一次请求即失效
        setMatrixCell("user", "create", false);
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks", uToken, null,
                Map.of("title", title("PM3"), "assigneeId", assignee.id(), "dueAt", dueAtInDays(7, "18:00"))),
                403, 3001);
        // 再授予并收回另一权限验证即时性：user.exportData 开 → 导出 200
        setMatrixCell("user", "exportData", true);
        io.restassured.response.Response exp = Api.raw("GET", "/task/api/v1/tasks/export", uToken,
                Map.of("keyword", "PM-none"), null);
        assertEquals(200, exp.getStatusCode(), "授予 exportData 后应可导出: " + exp.getBody().asString());
        setMatrixCell("user", "exportData", false);
        Api.expectErr(Api.raw("GET", "/task/api/v1/tasks/export", uToken, Map.of("keyword", "PM-none"), null),
                403, 3001);
    }

    @Test
    @Order(3)
    @DisplayName("每次矩阵修改写入审计日志（操作人=admin，可查变更前后值）")
    void t03_auditLogRecordsMatrixChange() {
        // 保证产生一次真实变更（无论当前值如何都翻转到对侧），变更前后值可预期
        boolean cur = matrixCell("user", "viewStats");
        boolean flip = !cur;
        setMatrixCell("user", "viewStats", flip);
        String trans = "[" + cur + "," + flip + "]";
        Api.await(20000, 500, () -> auditHas("user", "viewStats", trans, cur, flip),
                "审计日志应含矩阵变更 user.viewStats " + trans);
        // 翻回原值并留痕
        setMatrixCell("user", "viewStats", cur);
        Api.await(20000, 500, () -> auditHas("user", "viewStats", "[" + flip + "," + cur + "]", flip, cur),
                "审计日志应含矩阵变更回退 user.viewStats");
    }

    private boolean auditHas(String roleKey, String perm, String transition, boolean oldVal, boolean newVal) {
        // 翻前若干页（并发套件会同时写入审计日志，放宽到最近 200 条）
        String want = "{\"" + perm + "\":{\"" + roleKey + "\":[" + oldVal + "," + newVal + "]}}";
        for (int page = 1; page <= 4; page++) {
            JsonNode d = Api.getData("/auth/api/v1/audit-logs", adminToken(),
                    Map.of("operatorId", 1L, "page", page, "size", 50));
            for (JsonNode n : d.path("list")) {
                if (!"permission.matrix.update".equals(n.path("action").asText())) {
                    continue;
                }
                String cd = n.path("changeDetail").asText();
                if (cd.replaceAll("\\s", "").contains(want)) {
                    return true;
                }
            }
        }
        return false;
    }
}
