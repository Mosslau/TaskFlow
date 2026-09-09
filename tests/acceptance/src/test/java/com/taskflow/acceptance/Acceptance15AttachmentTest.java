package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 15 · 附件：超限（大小/数量/类型）被拒并给出原因（2008）；
 * 下载字节一致；删除限上传人本人或 admin（越权 403）。
 */
@Order(15)
@DisplayName("ACC-15 附件：超限拒绝含原因/下载字节一致/删除权限")
public class Acceptance15AttachmentTest extends AccBase {

    public Acceptance15AttachmentTest() {
        this.cls = "at";
    }

    private TUser creator;
    private TUser assignee;
    private TUser stranger;
    private String aToken;
    private String sToken;
    private long taskId;
    private final byte[] payload = "ACC15-附件内容-0123456789-abcdefghijklmnopqrstuvwxyz".repeat(8).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    /** 上传文件名（ASCII：RestAssured multipart 默认 ISO-8859-1 文件名会破坏中文，中文名由 RFC5987 下载头单独断言） */
    private static final String FN = "acc15-upload.txt";

    @BeforeAll
    void setup() {
        creator = newUser("附件创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        assignee = newUser("附件处理人" + uniq(), Conf.ROLE_USER);
        stranger = newUser("附件无关人" + uniq(), Conf.ROLE_USER);
        String cToken = Api.login(creator.account(), creator.password()).token();
        aToken = Api.login(assignee.account(), assignee.password()).token();
        sToken = Api.login(stranger.account(), stranger.password()).token();
        JsonNode t = createTask(cToken, title("AT1"), assignee.id(), null, null,
                dueAtInDays(9, "18:00"), null);
        taskId = t.path("id").asLong();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    private Response upload(String token, String filename, byte[] bytes) {
        return RestAssured.given().baseUri(Conf.BASE)
                .header("Authorization", "Bearer " + token)
                .multiPart("file", filename, bytes)
                .post("/task/api/v1/tasks/" + taskId + "/attachments");
    }

    @Test
    @Order(1)
    @DisplayName("上传成功并落库：响应含 id/originalName/sizeBytes；附件列表可见")
    void t01_uploadOk() {
        resetAttachments();
        Response r = upload(aToken, FN, payload);
        JsonNode d = Api.expectOk(r);
        long attId = d.path("id").asLong();
        assertEquals(FN, d.path("originalName").asText());
        assertEquals(payload.length, d.path("sizeBytes").asInt());
        // 详情附件列表
        JsonNode detail = detail(aToken, taskId);
        boolean found = false;
        for (JsonNode a : detail.path("attachments")) {
            if (a.path("id").asLong() == attId) {
                found = true;
            }
        }
        assertTrue(found, "附件应出现在任务详情附件列表");
        // 清理：本人可删
        Api.expectOk(Api.raw("DELETE", "/task/api/v1/attachments/" + attId, aToken, null, null));
    }

    @Test
    @Order(2)
    @DisplayName("类型/大小/数量超限 → 2008 且 details.limit 指明原因")
    void t02_limits() {
        // 类型不在白名单
        Response badType = upload(aToken, "acc15-virus.exe", payload);
        JsonNode dt = Api.expectErr(badType, 400, 2008);
        assertEquals("type", dt.path("limit").asText(), "类型超限应指明 limit=type");
        // 大小超过 20MB
        byte[] big = new byte[21 * 1024 * 1024 + 13];
        Response bigR = upload(aToken, "acc15-big.txt", big);
        JsonNode db = Api.expectErr(bigR, 400, 2008);
        assertEquals("size", db.path("limit").asText(), "大小超限应指明 limit=size");
        // 数量超 10：上传 10 个后第 11 个 → count
        for (int i = 0; i < 10; i++) {
            assertEquals(0, Api.code(upload(aToken, "acc15-f" + i + ".txt", payload)), "第 " + (i + 1) + " 个上传失败");
        }
        Response eleventh = upload(aToken, "acc15-eleven.txt", payload);
        JsonNode dc = Api.expectErr(eleventh, 400, 2008);
        assertEquals("count", dc.path("limit").asText(), "数量超限应指明 limit=count");
    }

    @Test
    @Order(3)
    @DisplayName("下载字节与原始一致；无关用户下载 2001")
    void t03_downloadByteIdentity() {
        resetAttachments();
        Response up = upload(aToken, "acc15-bytes.txt", payload);
        long attId = Api.expectOk(up).path("id").asLong();
        Response dl = RestAssured.given().baseUri(Conf.BASE)
                .header("Authorization", "Bearer " + aToken)
                .get("/task/api/v1/attachments/" + attId + "/download");
        assertEquals(200, dl.getStatusCode());
        byte[] got = dl.getBody().asByteArray();
        assertEquals(md5(payload), md5(got), "下载字节应与上传一致");
        String cd = dl.getHeader("Content-Disposition");
        assertTrue(cd != null && cd.contains("acc15-bytes.txt"), "下载应按原始文件名: " + cd);
        // 无关用户下载 → 2001
        Response strangerDl = RestAssured.given().baseUri(Conf.BASE)
                .header("Authorization", "Bearer " + sToken)
                .get("/task/api/v1/attachments/" + attId + "/download");
        Api.expectErr(strangerDl, 403, 2001);
        // 上传人删除
        Api.expectOk(Api.raw("DELETE", "/task/api/v1/attachments/" + attId, aToken, null, null));
    }

    @Test
    @Order(4)
    @DisplayName("删除附件：非上传人且非 admin → 403/3001；admin 可删")
    void t04_deleteRules() {
        resetAttachments();
        Response up = upload(assigneeToken(), "acc15-del.txt", payload);
        long attId = Api.expectOk(up).path("id").asLong();
        // 创建人（可见但非上传人）删除 → 3001
        String cToken = Api.login(creator.account(), creator.password()).token();
        Api.expectErr(Api.raw("DELETE", "/task/api/v1/attachments/" + attId, cToken, null, null), 403, 3001);
        // 无关用户删除 → 2001（不可见优先）
        Api.expectErr(Api.raw("DELETE", "/task/api/v1/attachments/" + attId, sToken, null, null), 403, 2001);
        // admin 可删除
        Api.expectOk(Api.raw("DELETE", "/task/api/v1/attachments/" + attId, adminToken(), null, null));
    }

    private String assigneeToken() {
        return aToken;
    }

    /** 清空该任务已有附件（admin 删除），保证用例间数量上限互不干扰 */
    private void resetAttachments() {
        JsonNode detail = detail(adminToken(), taskId);
        for (JsonNode a : detail.path("attachments")) {
            try {
                Api.raw("DELETE", "/task/api/v1/attachments/" + a.path("id").asLong(),
                        adminToken(), null, null);
            } catch (Throwable ignore) {
            }
        }
    }

    private String md5(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
