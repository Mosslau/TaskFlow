package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 11 · Excel 导入：合法文件整批入库且来源"Excel 导入"；含错误行整批拒绝并定位到行；
 * 批次留痕。
 */
@Order(11)
@DisplayName("ACC-11 Excel 导入：合法整批入库/错误整批拒绝+行定位/来源渠道")
public class Acceptance11ImportTest extends AccBase {

    public Acceptance11ImportTest() {
        this.cls = "imp";
    }

    private TUser u;       // 处理人（导入行引用其账号）
    private String prefix; // 任务标题前缀
    private String fileNameOk;
    private String fileNameBad;

    @BeforeAll
    void setup() {
        u = newUser("导入处理人" + uniq(), Conf.ROLE_USER);
        prefix = "ACCIMP-" + uniq().replace("-", "");
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    /** 下载导入模板 → 用 POI 改写为数据行 */
    private byte[] buildXlsx(String[][] rows, String name) throws Exception {
        Response tpl = Api.raw("GET", "/task/api/v1/tasks/import-template", adminToken(), null, null);
        assertEquals(200, tpl.getStatusCode());
        byte[] tplBytes = tpl.getBody().asByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(tplBytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.getSheetAt(0);
            // 模板第 1 行是示例，直接清掉重建
            if (sheet.getLastRowNum() >= 1) {
                sheet.removeRow(sheet.getRow(1));
            }
            int r = 1;
            for (String[] row : rows) {
                Row rr = sheet.createRow(r++);
                for (int c = 0; c < row.length; c++) {
                    Cell cell = rr.createCell(c);
                    cell.setCellValue(row[c]);
                }
            }
            wb.write(out);
            fileName(name);
            return out.toByteArray();
        }
    }

    private void fileName(String n) {
        if (n.contains("ok")) {
            fileNameOk = n;
        } else {
            fileNameBad = n;
        }
    }

    private String dueDay(int plusDays) {
        return java.time.LocalDate.now(java.time.ZoneOffset.ofHours(8)).plusDays(plusDays).toString();
    }

    @Test
    @Order(2)
    @DisplayName("导入结果与来源渠道校验")
    void t01_importResult() throws Exception {
        String[][] rows = {
                {prefix + "-1", "描述一", "日常事务", "P1", u.account(), dueDay(5), "18:00", "0"},
                {prefix + "-2", "", "", "P3", u.account(), dueDay(6), "09:30", "20"},
        };
        byte[] xlsx = buildXlsx(rows, "acc11-ok-" + uniq() + ".xlsx");
        JsonNode d = doImportOk(xlsx);
        assertEquals(2, d.path("totalRows").asInt(), "totalRows");
        assertEquals(2, d.path("successCount").asInt(), "successCount");
        // DB 校验
        List<Map<String, Object>> dbTasks = Db.rows(Conf.DB_TASK,
                "SELECT id,task_no,title,status,source,assignee_id FROM task WHERE title LIKE ? ORDER BY title",
                prefix + "%");
        assertEquals(2, dbTasks.size(), "应入库 2 条");
        for (Map<String, Object> row : dbTasks) {
            registerTask(Long.parseLong(String.valueOf(row.get("id"))));
        }
        for (Map<String, Object> row : dbTasks) {
            assertEquals("new", String.valueOf(row.get("status")), "导入任务初始应为待办");
            assertEquals(u.id(), Long.valueOf(String.valueOf(row.get("assignee_id"))), "处理人应解析正确");
            assertEquals("Excel 导入", String.valueOf(row.get("source")),
                    "导入任务来源渠道应为 Excel 导入（PRD 4.7）");
        }
        // 列表可见并可检索
        JsonNode list = Api.getData("/task/api/v1/tasks", adminToken(),
                Map.of("keyword", prefix, "size", 50));
        assertEquals(2, list.path("total").asLong(), "列表应检索到导入任务");
    }

    private JsonNode doImportOk(byte[] xlsx) {
        Response r = io.restassured.RestAssured.given()
                .baseUri(Conf.BASE)
                .header("Authorization", "Bearer " + adminToken())
                .multiPart("file", fileNameOk, xlsx,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .post("/task/api/v1/tasks/import");
        JsonNode d = Api.expectOk(r);
        registerBatch(d.path("batchId").asLong(), fileNameOk);
        return d;
    }

    @Test
    @Order(3)
    @DisplayName("含错误行整批拒绝：2012 + 逐行定位，全部不落库，失败批次留痕")
    void t03_invalidWholeBatch() throws Exception {
        String[][] bad = {
                {prefix + "-bad1", "ok行", "项目开发", "P2", u.account(), dueDay(5), "18:00", "0"},
                {prefix + "-bad2", "非法类型", "外星类型", "P2", u.account(), dueDay(5), "18:00", "0"},
                {"", "缺标题", "项目开发", "P2", u.account(), dueDay(5), "18:00", "0"},
                {prefix + "-bad4", "处理人不存在", "项目开发", "P2", "no_such_zz9999", dueDay(5), "18:00", "0"},
                {prefix + "-bad5", "日期非法", "项目开发", "P2", u.account(), "2026-13-40", "18:00", "0"},
                {prefix + "-bad6", "时间非法", "项目开发", "P2", u.account(), dueDay(5), "25:99", "0"},
        };
        byte[] xlsx = buildXlsx(bad, "acc11-bad-" + uniq() + ".xlsx");
        Response r = io.restassured.RestAssured.given()
                .baseUri(Conf.BASE)
                .header("Authorization", "Bearer " + adminToken())
                .multiPart("file", fileNameBad, xlsx,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .post("/task/api/v1/tasks/import");
        JsonNode details = Api.expectErr(r, 400, 2012);
        // 整批拒绝：无任何任务入库
        List<Map<String, Object>> leftovers = Db.rows(Conf.DB_TASK,
                "SELECT id FROM task WHERE title LIKE ?", prefix + "-bad%");
        assertEquals(0, leftovers.size(), "错误批次不应入库任何任务");
        // 逐行错误定位（至少 4 类错误行被报告且行号合理）
        assertTrue(details.isArray() && details.size() >= 4, "details 应含逐行错误: " + details);
        boolean hasRow = false;
        for (JsonNode e : details) {
            int rowNo = e.path("row").asInt(-1);
            String reason = e.path("reason").asText();
            if (rowNo >= 3 && rowNo <= 6 && !reason.isEmpty()) {
                hasRow = true;
            }
            assertTrue(!reason.isEmpty(), "错误行应给出原因");
        }
        assertTrue(hasRow, "错误报告应定位到行号");
        // 失败批次留痕
        List<Map<String, Object>> batches = Db.rows(Conf.DB_TASK,
                "SELECT id,fail_count,error_report FROM import_batch WHERE file_name=?", fileNameBad);
        assertEquals(1, batches.size(), "应有失败批次记录");
        assertTrue(Integer.parseInt(String.valueOf(batches.get(0).get("fail_count"))) > 0);
        Db.update(Conf.DB_TASK, "DELETE FROM import_batch WHERE file_name=?", fileNameBad);
    }
}
