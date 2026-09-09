package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 3 · 验收驳回：驳回必填原因；驳回后回到进行中；处理人收到站内消息与邮件且内容含驳回原因。
 */
@Order(3)
@DisplayName("ACC-3 验收驳回：原因必填/回进行中/处理人通知含原因（站内+邮件）")
public class Acceptance3RejectTest extends AccBase {

    public Acceptance3RejectTest() {
        this.cls = "rj";
    }

    private TUser creator;   // taskAdmin 创建人（验收人）
    private TUser assignee;  // user 处理人
    private String cToken;
    private String aToken;

    @BeforeAll
    void setup() {
        creator = newUser("驳回创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        assignee = newUser("驳回处理人" + uniq(), Conf.ROLE_USER);
        cToken = Api.login(creator.account(), creator.password()).token();
        aToken = Api.login(assignee.account(), assignee.password()).token();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("驳回必填原因（1001 参数校验）且驳回后回到进行中")
    void t01_rejectRequiresReasonAndGoesBack() {
        JsonNode t = createTask(cToken, title("RJ1"), assignee.id(), null, null,
                dueAtInDays(10, "18:00"), null);
        long id = t.path("id").asLong();
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", aToken, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/submit-acceptance", aToken, null, null));
        // 驳回原因为空/缺失 → 1001（参数校验失败）
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/reject", cToken, null,
                Map.of("reason", "")), 400, 1001);
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/reject", cToken, null,
                Map.of()), 400, 1001);
        // 原因超 500 字符 → 1001
        String tooLong = "原".repeat(501);
        Api.expectErr(Api.raw("POST", "/task/api/v1/tasks/" + id + "/reject", cToken, null,
                Map.of("reason", tooLong)), 400, 1001);

        // 正常驳回 → 回到进行中
        String reason = "验收不合格-缺测试证据-" + uniq();
        JsonNode ok = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/reject",
                cToken, null, Map.of("reason", reason)));
        assertEquals("doing", ok.path("status").asText(), "驳回后应回到进行中");

        // 处理人站内消息（task.rejected）摘要含驳回原因
        awaitNotifications(id, assignee.id(), "task.rejected", 1);
        JsonNode notifs = Api.getData("/notification/api/v1/notifications", aToken,
                Map.of("page", 1, "size", 50));
        boolean found = false;
        for (JsonNode n : notifs.path("list")) {
            if ("task.rejected".equals(n.path("eventType").asText())
                    && n.path("taskNo").asText().contains(t.path("taskNo").asText())
                    && n.path("summary").asText().contains(reason)) {
                found = true;
            }
        }
        assertTrue(found, "处理人站内消息应含驳回原因与任务编号");

        // 邮件记录：收件人 = 处理人邮箱，主题含「验收驳回」与任务编号；正文含原因（SMTP 落盘证据）
        String email = assignee.email();
        String taskNo = t.path("taskNo").asText();
        List<Map<String, Object>> mails = awaitRejectMail(taskId(taskNo), email, taskNo);
        Map<String, Object> m = mails.get(0);
        assertTrue(String.valueOf(m.get("subject")).contains(taskNo), "邮件主题应含任务编号");
        awaitSinkEvidence(email, taskNo, reason, "验收被驳回");
    }

    private long taskId(String taskNo) {
        return Db.scalarLong(Conf.DB_TASK, "SELECT id FROM task WHERE task_no=?", taskNo);
    }

    /** 轮询 mail_record 直到出现主题含「验收驳回」+ taskNo 的记录 */
    private List<Map<String, Object>> awaitRejectMail(long taskId, String recipientEmail, String taskNo) {
        List<Map<String, Object>>[] holder = new List[]{List.of()};
        Api.await(Conf.EVENT_WAIT.toMillis(), 1000, () -> {
            List<Map<String, Object>> rows = Db.rows(Conf.DB_NOTIFICATION,
                    "SELECT * FROM mail_record WHERE task_id=? AND recipient=? AND subject LIKE ? ORDER BY id",
                    taskId, recipientEmail, "%验收驳回%" + taskNo + "%");
            holder[0] = rows;
            return !rows.isEmpty();
        }, "等待验收驳回邮件记录 task=" + taskId);
        return holder[0];
    }

    /** SMTP sink 落盘：找到含 taskNo/reason/邮箱正文的 .eml（正文可能 base64，需解码） */
    private void awaitSinkEvidence(String recipientPart, String taskNo, String reason, String eventWord) {
        Api.await(Conf.EVENT_WAIT.toMillis(), 1000, () -> {
            try (Stream<Path> s = Files.list(Path.of("/tmp/tfsmtp/inbox"))) {
                List<Path> files = s.filter(p -> p.toString().endsWith(".eml"))
                        .sorted((a, b) -> b.toString().compareTo(a.toString()))
                        .toList();
                for (Path f : files) {
                    String content;
                    try {
                        content = Files.readString(f);
                    } catch (IOException e) {
                        continue;
                    }
                    String decoded = content;
                    int mark = content.indexOf("Content-Transfer-Encoding: base64");
                    if (mark >= 0) {
                        int nl = content.indexOf("\r\n\r\n", mark);
                        if (nl < 0) {
                            nl = content.indexOf("\n\n", mark);
                        }
                        if (nl >= 0) {
                            try {
                                byte[] raw = java.util.Base64.getMimeDecoder()
                                        .decode(content.substring(nl).replaceAll("\\s", ""));
                                decoded = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                            } catch (IllegalArgumentException e) {
                                // 解码失败则退回原样扫描
                            }
                        }
                    }
                    String haystack = content + "\n" + decoded;
                    if (haystack.contains(recipientPart) && haystack.contains(taskNo)
                            && haystack.contains(reason) && haystack.contains(eventWord)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                return false;
            }
            return false;
        }, "等待 SMTP 落盘正文证据 recipient=" + recipientPart);
    }
}
