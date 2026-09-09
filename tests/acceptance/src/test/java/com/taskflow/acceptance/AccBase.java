package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收测试基类：每类一个实例（PER_CLASS），负责唯一数据、账号创建、矩阵还原与清理。
 *
 * <p>环境共享且可能有多套验收并行，故本类所有命名（账号/任务标题）带唯一后缀；
 * 清理覆盖任务/评论/附件/通知/邮件/导入批次/附件文件；用户不可物理删除，统一停用。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AccBase {

    /** 测试用户 */
    public record TUser(long id, String account, String password, String name, String email, String roleKey) {
    }

    /** 任务引用 */
    public record TaskRef(long id, String taskNo, String title) {
    }

    private final String uidSuffix = String.valueOf(System.nanoTime() % 1_000_000_000L) + "-"
            + ThreadLocalRandom.current().nextInt(1000, 9999);

    /** 类标记（子类赋值，用于账号/标题前缀） */
    protected String cls = "acc";

    /** 唯一后缀（同一进程内唯一；跨进程碰撞概率可忽略） */
    public String uniq() {
        return uidSuffix;
    }

    private String adminToken;
    private final List<TUser> users = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private final Set<String> touchedMatrix = new LinkedHashSet<>();
    private final Set<Long> batchIds = new HashSet<>();
    private final Set<String> importFileNames = new HashSet<>();
    private int userSeq = 0;

    // ==================== admin ====================

    protected String adminToken() {
        if (adminToken == null) {
            adminToken = Api.adminLogin().token();
        }
        return adminToken;
    }

    // ==================== 用户 ====================

    /** 创建用户（admin 调 POST /users，roleId: 2=taskAdmin 3=user），注册清理 */
    protected TUser newUser(String name, long roleId) {
        String roleTag = roleId == Conf.ROLE_TASK_ADMIN ? "a" : "u";
        String account = "m61" + cls + "_" + (++userSeq) + roleTag + "_" + uidSuffix;
        account = account.replaceAll("[^a-zA-Z0-9_]", "_");
        if (account.length() > 32) {
            account = account.substring(0, 32);
        }
        String email = account + "@tf.local";
        Response r = Api.raw("POST", "/auth/api/v1/users", adminToken(), null,
                Map.of("name", name, "account", account, "departmentId", Conf.DEFAULT_DEPT_ID,
                        "roleId", roleId, "email", email));
        JsonNode d = Api.expectOk(r);
        long id = d.path("id").asLong();
        String pwd = d.path("initialPassword").asText();
        String roleKey = roleId == Conf.ROLE_TASK_ADMIN ? "taskAdmin" : "user";
        TUser u = new TUser(id, account, pwd, name, email, roleKey);
        users.add(u);
        return u;
    }

    /** 停用已建用户（清理时调用） */
    protected void disableUser(long id) {
        try {
            Response r = Api.raw("PUT", "/auth/api/v1/users/" + id + "/status", adminToken(), null,
                    Map.of("status", "disabled"));
            assertEquals(0, Api.code(r), "停用用户失败: " + r.getBody().asString());
        } catch (Throwable t) {
            System.err.println("[清理] 停用用户 " + id + " 失败: " + t.getMessage());
        }
    }

    // ==================== 任务 ====================

    protected JsonNode createTask(String token, String title, long assigneeId, String type,
                                  String priority, String dueAt, Long parentId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", title);
        if (type != null) {
            body.put("taskType", type);
        }
        if (priority != null) {
            body.put("priority", priority);
        }
        body.put("assigneeId", assigneeId);
        if (dueAt != null) {
            body.put("dueAt", dueAt);
        }
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        JsonNode d = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks", token, null, body));
        registerTask(d.path("id").asLong());
        return d;
    }

    protected TaskRef newTaskRef(JsonNode d) {
        return new TaskRef(d.path("id").asLong(), d.path("taskNo").asText(), d.path("title").asText());
    }

    protected void registerTask(long id) {
        taskIds.add(id);
    }

    // ==================== 矩阵 ====================

    protected boolean matrixCell(String roleKey, String perm) {
        JsonNode m = Api.getData("/auth/api/v1/permissions/matrix", adminToken(), null);
        for (JsonNode role : m.path("matrix")) {
            if (roleKey.equals(role.path("roleKey").asText())) {
                return role.path("permissions").path(perm).asBoolean(false);
            }
        }
        throw new IllegalStateException("角色不存在 " + roleKey);
    }

    /** 设置矩阵单格并记录待还原；与目标一致时不发请求（幂等） */
    protected void setMatrixCell(String roleKey, String perm, boolean enabled) {
        if (matrixCell(roleKey, perm) == enabled) {
            return;
        }
        Response r = Api.raw("PUT", "/auth/api/v1/permissions/matrix", adminToken(), null,
                Map.of("roleKey", roleKey, "permissionKey", perm, "enabled", enabled));
        assertEquals(0, Api.code(r), "矩阵设置失败: " + r.getBody().asString());
        touchedMatrix.add(roleKey + "/" + perm);
    }

    /** 把默认矩阵（PRD 3.3）中涉及 user/taskAdmin/admin 的关键项全部还原为默认（幂等） */
    protected void restoreDefaultMatrix() {
        for (String[] row : Conf.DEFAULT_MATRIX) {
            try {
                setMatrixCell(row[0], row[1], Boolean.parseBoolean(row[2]));
            } catch (Throwable t) {
                // admin 锁项不可关（3009）时忽略
                System.err.println("[矩阵还原] " + row[0] + "/" + row[1] + " 跳过: " + t.getMessage());
            }
        }
    }

    // ==================== 工具 ====================

    protected static String taskTitle(String prefix, String uniq) {
        return prefix + "-" + uniq;
    }

    /** 标题前缀（含唯一后缀）造任务标题 */
    protected String title(String tag) {
        return "ACC-" + cls + "-" + tag + "-" + uidSuffix;
    }

    /** 到期时间：now+days 天 +08:00，HH:mm */
    protected static String dueAtInDays(int days, String time) {
        java.time.OffsetDateTime t = java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8))
                .plusDays(days);
        return t.toLocalDate() + "T" + time + ":00+08:00";
    }

    /** 删除 Redis 分布式锁键（定时任务每轮用 setIfAbsent 抢锁，锁 TTL 10-30 分钟；
     *  阶段二把 cron 缩短为 15 秒后，须在等待前删锁，让下一个 tick 真正执行扫描） */
    protected void deleteJobLocks(String... keys) {
        try (redis.clients.jedis.Jedis jedis = new redis.clients.jedis.Jedis(Conf.REDIS_HOST, Conf.REDIS_PORT)) {
            jedis.auth(Conf.REDIS_PASSWORD);
            jedis.del(keys);
        } catch (Exception e) {
            throw new IllegalStateException("删除定时任务锁失败: " + String.join(",", keys), e);
        }
    }

    /** 通过 DB 查我的任务里标题匹配的行（task 表主键在唯一账号数据下足够精确） */
    protected List<Map<String, Object>> myTasksByTitleLike(String like) {
        return Db.rows(Conf.DB_TASK, "SELECT * FROM task WHERE title LIKE ? ORDER BY id", "%" + like + "%");
    }

    // ==================== 等待 ====================

    protected void awaitNotifications(long taskId, long recipientId, String eventType, int minCount) {
        Api.await(Conf.EVENT_WAIT.toMillis(), 1000, () ->
                        Db.scalarInt(Conf.DB_NOTIFICATION,
                                "SELECT count(*) FROM notification WHERE task_id=? AND recipient_id=? AND event_type=?",
                                taskId, recipientId, eventType) >= minCount,
                "等待站内消息 task=" + taskId + " to=" + recipientId + " type=" + eventType);
    }

    /** 任务详情 data */
    protected JsonNode detail(String token, long taskId) {
        return Api.getData("/task/api/v1/tasks/" + taskId, token, null);
    }

    /** 详情时间线 action 列表（倒序） */
    protected List<String> timelineActions(String token, long taskId) {
        JsonNode d = detail(token, taskId);
        List<String> out = new ArrayList<>();
        d.path("timeline").forEach(n -> out.add(n.path("action").asText()));
        return out;
    }

    /** 状态流转便捷封装：POST /tasks/{id}/accept 等 */
    protected JsonNode actionOk(String token, long taskId, String action, Map<String, Object> body) {
        JsonNode d = Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + taskId + action, token, null, body));
        return d;
    }

    /** 断言通用业务错误（400 前缀 HTTP 状态按码表） */
    protected void expectBizErr(String token, String method, String path, Map<String, Object> query,
                                Object body, int http, int bizCode) {
        Api.expectErr(Api.raw(method, path, token, query, body), http, bizCode);
    }

    // ==================== 清理 ====================

    protected void registerBatch(long batchId, String fileName) {
        if (batchId > 0) {
            batchIds.add(batchId);
        }
        if (fileName != null) {
            importFileNames.add(fileName);
        }
    }

    /** 清理本类数据：附件文件→评论/时间线/通知/邮件/导入批次→任务→用户停用→矩阵还原 */
    protected void cleanupAll() {
        try {
            cleanupAttachments();
            cleanupTasks();
            cleanupNotificationsAndMails();
            cleanupImportBatches();
        } finally {
            restoreDefaultMatrix();
            for (TUser u : users) {
                disableUser(u.id());
            }
        }
    }

    private void cleanupAttachments() {
        if (taskIds.isEmpty()) {
            return;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < taskIds.size(); i++) {
            in.append(i == 0 ? "" : ",").append("?");
        }
        String sql = "SELECT stored_name FROM task_attachment WHERE task_id IN (" + in + ")";
        try {
            for (Map<String, Object> row : Db.rows(Conf.DB_TASK, sql, taskIds.toArray())) {
                String stored = String.valueOf(row.get("stored_name"));
                try {
                    Files.deleteIfExists(Path.of(Conf.ATTACHMENT_ROOT, stored));
                } catch (Exception ignore) {
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private void cleanupTasks() {
        if (taskIds.isEmpty()) {
            return;
        }
        String in = inClause();
        Object[] args = taskIds.toArray();
        Db.update(Conf.DB_TASK, "DELETE FROM task_timeline WHERE task_id IN (" + in + ")", args);
        Db.update(Conf.DB_TASK, "DELETE FROM task_comment WHERE task_id IN (" + in + ")", args);
        Db.update(Conf.DB_TASK, "DELETE FROM task_attachment WHERE task_id IN (" + in + ")", args);
        // 含子任务：按 id 直接删（子任务同表）
        Db.update(Conf.DB_TASK, "DELETE FROM task WHERE id IN (" + in + ")", args);
    }

    private void cleanupNotificationsAndMails() {
        if (taskIds.isEmpty()) {
            return;
        }
        String in = inClause();
        Object[] args = taskIds.toArray();
        Db.update(Conf.DB_NOTIFICATION, "DELETE FROM notification WHERE task_id IN (" + in + ")", args);
        Db.update(Conf.DB_NOTIFICATION, "DELETE FROM mail_record WHERE task_id IN (" + in + ")", args);
    }

    private void cleanupImportBatches() {
        for (Long b : batchIds) {
            Db.update(Conf.DB_TASK, "DELETE FROM import_batch WHERE id=?", b);
        }
        for (String f : importFileNames) {
            Db.update(Conf.DB_TASK, "DELETE FROM import_batch WHERE file_name=?", f);
        }
    }

    private String inClause() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < taskIds.size(); i++) {
            sb.append(i == 0 ? "" : ",").append("?");
        }
        return sb.toString();
    }
}
