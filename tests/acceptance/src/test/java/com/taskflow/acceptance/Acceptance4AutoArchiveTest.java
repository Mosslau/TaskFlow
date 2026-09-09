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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 验收条件 4 · 自动归档：done 满 7 天 → 定时任务扫描后 close + 时间线"自动归档"。
 *
 * <p>触发方式（白盒证据）：本类需在 task-service 三个 job cron 临时缩短为
 * 15 秒一轮的前提下执行（见模块 README / 阶段二脚本）。默认跳过。
 * SQL 预造：done 任务 updated_at 拨到 8 天前，等扫描把任务归档。</p>
 */
@Order(4)
@DisplayName("ACC-4 自动归档：done 满 7 天 → 定时扫描归档 + 时间线[自动归档]")
public class Acceptance4AutoArchiveTest extends AccBase {

    public Acceptance4AutoArchiveTest() {
        this.cls = "arc";
    }

    private TUser creator;
    private TUser assignee;
    private String cToken;
    private String aToken;

    @BeforeAll
    void setup() {
        assumeTrue(Boolean.getBoolean("tf.scheduled.tests"),
                "自动归档需定时任务 cron 已临时缩短（阶段二）");
        creator = newUser("归档创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        assignee = newUser("归档处理人" + uniq(), Conf.ROLE_USER);
        cToken = Api.login(creator.account(), creator.password()).token();
        aToken = Api.login(assignee.account(), assignee.password()).token();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("done 满 7 天被自动归档：状态 close + 时间线自动归档（operator=系统）")
    void t01_autoArchive() {
        JsonNode t = createTask(cToken, title("ARC1"), assignee.id(), null, null,
                dueAtInDays(5, "18:00"), null);
        long id = t.path("id").asLong();
        // 驱动到 done
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", aToken, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/submit-acceptance", aToken, null, null));
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/approve", cToken, null, null));
        assertEquals("done", detail(cToken, id).path("task").path("status").asText());

        // SQL 预造：完成时间拨到 8 天前
        Db.update(Conf.DB_TASK, "UPDATE task SET updated_at = now() - INTERVAL '8 days' WHERE id=?", id);

        // 释放自动归档扫描的分布式锁，让下一轮(15s)真正执行；再等扫描结果（≤90s）
        deleteJobLocks("taskflow:job:auto-archive");
        long[] status = new long[1];
        Api.await(90_000, 3000, () -> {
            String s = Db.scalar(Conf.DB_TASK, "SELECT status FROM task WHERE id=?", id);
            status[0] = s == null ? -1 : (s.equals("close") ? 1 : 0);
            return status[0] == 1;
        }, "等待自动归档扫描把任务归档 task=" + id);

        // 时间线含"自动归档"，系统操作人(0)
        JsonNode tl = detail(adminToken(), id).path("timeline");
        boolean has = false;
        for (JsonNode n : tl) {
            if ("自动归档".equals(n.path("action").asText())
                    && n.path("note").asText().contains("满 7 天")) {
                has = true;
            }
        }
        assertTrue(has, "时间线应含自动归档动作: " + tl);
        List<Map<String, Object>> rows = Db.rows(Conf.DB_TASK,
                "SELECT action, operator_id FROM task_timeline WHERE task_id=? AND action='自动归档'", id);
        assertEquals(1, rows.size());
        assertEquals(0L, Long.valueOf(String.valueOf(rows.get(0).get("operator_id"))), "自动归档操作人应为系统(0)");
    }
}
