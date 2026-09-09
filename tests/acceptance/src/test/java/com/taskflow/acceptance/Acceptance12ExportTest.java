package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 12 · CSV 导出：导出内容与当前筛选结果一致（BOM/表头/状态中文化）；无 exportData → 403/3001。
 */
@Order(12)
@DisplayName("ACC-12 导出：内容与筛选一致 + BOM；无 exportData 权限 3001")
public class Acceptance12ExportTest extends AccBase {

    public Acceptance12ExportTest() {
        this.cls = "exp";
    }

    private TUser ta;
    private TUser u;
    private TUser userNoPerm; // 普通用户（无 exportData）
    private String taToken;
    private String kw;

    @BeforeAll
    void setup() {
        restoreDefaultMatrix();
        ta = newUser("导出创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        u = newUser("导出处理人" + uniq(), Conf.ROLE_USER);
        userNoPerm = newUser("导出无权限" + uniq(), Conf.ROLE_USER);
        taToken = Api.login(ta.account(), ta.password()).token();
        kw = "ACCexp" + uniq();
        // 三个任务：状态/优先级不同
        createTask(taToken, kw + "-X1", u.id(), "日常事务", "P0", dueAtInDays(9, "18:00"), null);
        createTask(taToken, kw + "-X2", u.id(), "数据报表", "P2", dueAtInDays(9, "18:00"), null);
        JsonNode t3 = createTask(taToken, kw + "-X3", u.id(), "流程审批", "P3", dueAtInDays(9, "18:00"), null);
        // X2 置为进行中，X3 置为已完成
        String uToken = Api.login(u.account(), u.password()).token();
        long x2 = myId(kw + "-X2");
        long x3 = t3.path("id").asLong();
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + x2 + "/accept", uToken, null, null));
        drive(x3, uToken);
    }

    private long myId(String title) {
        return Db.scalarLong(Conf.DB_TASK, "SELECT id FROM task WHERE title=?", title);
    }

    private void drive(long id, String uToken) {
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", uToken, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/submit-acceptance", uToken, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/approve", taToken, null, null));
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("导出与筛选一致：关键字+状态+优先级 组合下 CSV 行 == 列表行")
    void t01_exportMatchesFilter() {
        // 组合1：keyword=前缀
        Response csv1 = export(taToken, Map.of("keyword", kw));
        assertEquals(200, csv1.getStatusCode());
        byte[] body = csv1.getBody().asByteArray();
        // BOM
        assertEquals(0xEF, body[0] & 0xFF);
        assertEquals(0xBB, body[1] & 0xFF);
        assertEquals(0xBF, body[2] & 0xFF);
        // Content-Disposition 文件名 任务导出-*.csv
        String cd = csv1.getHeader("Content-Disposition");
        assertTrue(cd != null && cd.contains("csv"), "导出文件名应含 csv: " + cd);

        String csv = new String(body, 3, body.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        Set<String> nos = parseTaskNos(csv);
        assertEquals(3, nos.size(), "keyword=前缀 应导出 3 行");

        // 组合2：keyword + status=done → 仅 X3
        String csvDone = new String(export(taToken, Map.of("keyword", kw, "status", "done"))
                .getBody().asByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        Set<String> doneNos = parseTaskNos(csvDone);
        assertEquals(1, doneNos.size());
        assertEquals(myIdNo(kw + "-X3"), doneNos.iterator().next(), "导出应仅含已完成任务");

        // 组合3：keyword + priority=P0 → X1；状态列中文（待办）
        String csvP0 = new String(export(taToken, Map.of("keyword", kw, "priority", "P0"))
                .getBody().asByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csvP0.contains(kw + "-X1"), "导出应含 X1");
        assertTrue(!csvP0.contains(kw + "-X2") && !csvP0.contains(kw + "-X3"), "导出不应含 X2/X3");
        assertTrue(csvP0.contains("待办"), "待办状态应中文化");

        // 与列表接口一致性（同条件集合比较）
        Set<String> listNos = apiNos(Map.of("keyword", kw));
        assertEquals(nos, listNos, "导出集合应与列表一致");
    }

    @Test
    @Order(2)
    @DisplayName("无 exportData 权限（user）→ 403/3001；taskAdmin 有权限可导出")
    void t02_noExportDataPermission() {
        String ut = Api.login(userNoPerm.account(), userNoPerm.password()).token();
        Api.expectErr(Api.raw("GET", "/task/api/v1/tasks/export", ut, Map.of("keyword", kw), null),
                403, 3001);
        // taskAdmin（exportData）可导出
        assertEquals(200, export(taToken, Map.of("keyword", kw)).getStatusCode());
    }

    // ---------- 工具 ----------

    private Response export(String token, Map<String, Object> q) {
        return Api.raw("GET", "/task/api/v1/tasks/export", token, q, null);
    }

    private Set<String> apiNos(Map<String, Object> q) {
        Map<String, Object> m = new java.util.HashMap<>(q);
        m.put("size", 50);
        JsonNode d = Api.getData("/task/api/v1/tasks", taToken, m);
        Set<String> out = new LinkedHashSet<>();
        d.path("list").forEach(n -> out.add(n.path("taskNo").asText()));
        return out;
    }

    private String myIdNo(String title) {
        return Db.scalar(Conf.DB_TASK, "SELECT task_no FROM task WHERE title=?", title);
    }

    /** 解析 CSV（简单实现：任务编号在第 1 列，标题第 2 列，本类标题无逗号） */
    private Set<String> parseTaskNos(String csv) {
        Set<String> out = new LinkedHashSet<>();
        String[] lines = csv.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = line.split(",", -1);
            if (cols.length >= 2) {
                out.add(cols[0].replace("\"", ""));
            }
        }
        return out;
    }
}
