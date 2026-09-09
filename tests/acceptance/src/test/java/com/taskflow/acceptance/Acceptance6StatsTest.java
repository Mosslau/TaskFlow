package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验收条件 6 · 统计口径：6 项 KPI 与 4 个图表数值，与按 PRD 4.3 口径对同一数据集手工计算一致。
 *
 * <p>做法：独立数据集（仅本类任务，created_at 统一 SQL 拨到 2026-02-20，避开并行脏数据），
 * admin 先调 /stats/api/v1/rebuild 全量重算，再比对 /overview?range=custom 与手工 SQL/常量。</p>
 */
@Order(6)
@DisplayName("ACC-6 统计口径：KPI+四图 vs 手工计算（rebuild 后同数据集比对）")
public class Acceptance6StatsTest extends AccBase {

    public Acceptance6StatsTest() {
        this.cls = "st";
    }

    /** 统一统计日（东八区日期）；避免与现有数据/并行数据冲突 */
    private static final String D = "2026-02-20";

    private TUser uA;
    private TUser uB;
    private String admin;
    private final List<Long> ids = new ArrayList<>();

    // 计划中的任务与最终状态/优先级（见 setup 注释，先 SQL 后重建）
    private JsonNode s1; // new   P0 uA
    private JsonNode s2; // doing P0 uA
    private JsonNode s3; // wait  P1 uB
    private JsonNode s4; // new   P1 uA
    private JsonNode s5; // new   P2 uA
    private JsonNode s6; // new   P3 uB
    private JsonNode s7; // new   P2 uB
    private JsonNode c1; // done  P1 uB
    private JsonNode c2; // done  P2 uA
    private JsonNode c3; // close P3 uB

    @BeforeAll
    void setup() {
        admin = adminToken();
        uA = newUser("统计处理A" + uniq(), Conf.ROLE_USER);
        uB = newUser("统计处理B" + uniq(), Conf.ROLE_USER);
        String ta = Api.login(uA.account(), uA.password()).token();
        String tb = Api.login(uB.account(), uB.password()).token();

        s1 = createTask(admin, title("S1"), uA.id(), "日常事务", "P0", "2027-01-01T10:00:00+08:00", null);
        s2 = createTask(admin, title("S2"), uA.id(), "数据报表", "P0", "2027-01-01T10:00:00+08:00", null);
        s3 = createTask(admin, title("S3"), uB.id(), "流程审批", "P1", "2027-01-01T10:00:00+08:00", null);
        s4 = createTask(admin, title("S4"), uA.id(), "调研分析", "P1", "2027-01-01T10:00:00+08:00", null);
        s5 = createTask(admin, title("S5"), uA.id(), "项目开发", "P2", "2027-01-01T10:00:00+08:00", null);
        s6 = createTask(admin, title("S6"), uB.id(), "会议事项", "P3", "2027-01-01T10:00:00+08:00", null);
        s7 = createTask(admin, title("S7"), uB.id(), "日常事务", "P2", "2027-01-01T10:00:00+08:00", null);
        c1 = createTask(admin, title("C1"), uB.id(), "数据报表", "P1", "2027-01-01T10:00:00+08:00", null);
        c2 = createTask(admin, title("C2"), uA.id(), "项目开发", "P2", "2027-01-01T10:00:00+08:00", null);
        c3 = createTask(admin, title("C3"), uB.id(), "调研分析", "P3", "2027-01-01T10:00:00+08:00", null);
        for (JsonNode t : List.of(s1, s2, s3, s4, s5, s6, s7, c1, c2, c3)) {
            ids.add(t.path("id").asLong());
        }

        // 状态驱动：s2/s3/c1/c2/c3 流转
        api("/" + id(s2) + "/accept", ta);
        api("/" + id(s3) + "/accept", tb);
        api("/" + id(s3) + "/submit-acceptance", tb);
        driveToDone(c1, tb);
        driveToDone(c2, ta);
        driveToDone(c3, tb);
        api("/" + id(c3) + "/archive", admin); // c3 → close

        // 统一创建日 2026-02-20（东八区 09:00）；完成任务的 updated/due 拨到确定值
        // （timestamptz 用显式 +08 文本，避免 JDBC 本地时区歧义）
        long[] un = new long[]{id(s1), id(s2), id(s3), id(s4), id(s5), id(s6), id(s7)};
        Object[] args = new Object[un.length];
        for (int i = 0; i < un.length; i++) {
            args[i] = un[i];
        }
        String in = String.join(",", java.util.Collections.nCopies(un.length, "?"));
        Db.update(Conf.DB_TASK,
                "UPDATE task SET created_at=TIMESTAMPTZ '2026-02-20 09:00:00+08',"
                        + " due_at=TIMESTAMPTZ '2027-01-01 10:00:00+08' WHERE id IN (" + in + ")",
                args);
        // s1/s3/s7 拨逾期：due_at 2026-02-21（已过期）
        Db.update(Conf.DB_TASK,
                "UPDATE task SET due_at=TIMESTAMPTZ '2026-02-21 18:00:00+08' WHERE id IN (?,?,?)",
                id(s1), id(s3), id(s7));
        // 完成任务：created 09:00；c1 按时完成(updated 13:00/due 18:00,4h)；c2 超时(updated 13:00/due 12:00)；
        // c3 超时(updated 15:00/due 12:00,6h)
        Db.update(Conf.DB_TASK,
                "UPDATE task SET created_at=TIMESTAMPTZ '2026-02-20 09:00:00+08',"
                        + " updated_at=TIMESTAMPTZ '2026-02-20 13:00:00+08',"
                        + " due_at=TIMESTAMPTZ '2026-02-20 18:00:00+08' WHERE id=?", id(c1));
        Db.update(Conf.DB_TASK,
                "UPDATE task SET created_at=TIMESTAMPTZ '2026-02-20 09:00:00+08',"
                        + " updated_at=TIMESTAMPTZ '2026-02-20 13:00:00+08',"
                        + " due_at=TIMESTAMPTZ '2026-02-20 12:00:00+08' WHERE id=?", id(c2));
        Db.update(Conf.DB_TASK,
                "UPDATE task SET created_at=TIMESTAMPTZ '2026-02-20 09:00:00+08',"
                        + " updated_at=TIMESTAMPTZ '2026-02-20 15:00:00+08',"
                        + " due_at=TIMESTAMPTZ '2026-02-20 12:00:00+08' WHERE id=?", id(c3));

        // 等待事件链路排空后重建聚合（避免重建后被迟到事件污染）
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Api.expectOk(Api.raw("POST", "/stats/api/v1/rebuild", admin, null, null));
    }

    private void driveToDone(JsonNode t, String assigneeToken) {
        api("/" + id(t) + "/accept", assigneeToken);
        api("/" + id(t) + "/submit-acceptance", assigneeToken);
        api("/" + id(t) + "/approve", admin); // admin 代执行验收通过
    }

    private long id(JsonNode t) {
        return t.path("id").asLong();
    }

    private void api(String suffix, String token) {
        Api.expectOk(Api.raw("POST", "/task/api/v1/tasks" + suffix, token, null, null));
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("KPI 六项与 PRD 4.3 手工计算一致")
    void t01_kpi() {
        JsonNode kpi = overview().path("kpi");
        // 本数据集独立于其他数据（创建日 D 只有本类任务）
        assertEquals(10, kpi.path("total").asInt(), "任务总数");
        assertEquals(3, kpi.path("done").asInt(), "已完成数（done+close）");
        assertEquals(7, kpi.path("unfinished").asInt(), "未完成数");
        assertEquals(5, kpi.path("todo").asInt(), "待办");
        assertEquals(1, kpi.path("doing").asInt(), "进行中");
        assertEquals(1, kpi.path("waiting").asInt(), "待验收");
        // 逾期：D 日创建的未完成任务中到期早于当前（s1/s3/s7）
        assertEquals(3, kpi.path("overdue").asInt(), "已逾期");
        // P0：口径 = 区间创建且当前 P0（本数据集 P0 均未完成，与 PRD"P0 且未完成"一致）
        assertEquals(2, kpi.path("p0").asInt(), "P0");
        assertEquals(20.0, kpi.path("p0Percent").asDouble(), 0.001, "P0 占比");
        // 平均完成时长 = (4+4+6)/3 = 4.7h；按时完成率 = 1/3 → 33%
        assertEquals(4.7, kpi.path("avgCompleteHours").asDouble(), 0.001, "平均完成时长");
        assertEquals(33, kpi.path("onTimeRate").asInt(), "按时完成率");
    }

    @Test
    @Order(2)
    @DisplayName("状态分布 / 优先级分布图与手工口径一致")
    void t02_distributions() {
        JsonNode d = overview();
        Map<String, Integer> status = new java.util.HashMap<>();
        d.path("statusDistribution").forEach(n -> status.put(n.path("status").asText(), n.path("count").asInt()));
        assertEquals(Map.of("new", 5, "doing", 1, "wait", 1, "done", 2, "close", 1), status, "状态分布");
        Map<String, Integer> prio = new java.util.HashMap<>();
        d.path("priorityDistribution").forEach(n -> prio.put(n.path("priority").asText(), n.path("count").asInt()));
        assertEquals(Map.of("P0", 2, "P1", 3, "P2", 3, "P3", 2), prio, "优先级分布");
    }

    @Test
    @Order(3)
    @DisplayName("趋势图：D 日新建 10 / 完成 3")
    void t03_trend() {
        JsonNode trend = overview().path("trend");
        assertEquals(1, trend.size(), "单日区间趋势行数");
        assertEquals(D, trend.get(0).path("date").asText());
        assertEquals(10, trend.get(0).path("created").asInt(), "当日新建");
        assertEquals(3, trend.get(0).path("completed").asInt(), "当日完成");
    }

    @Test
    @Order(4)
    @DisplayName("人员负载图：两名处理人未完成任务数与 DB 一致（不含 admin）")
    void t04_assigneeLoad() {
        JsonNode load = overview().path("assigneeLoad");
        int a = -1;
        int b = -1;
        for (JsonNode n : load) {
            long aid = n.path("assigneeId").asLong();
            if (aid == uA.id()) {
                a = n.path("unfinished").asInt();
            } else if (aid == uB.id()) {
                b = n.path("unfinished").asInt();
            }
            assertEquals(false, "admin".equals(n.path("assigneeName").asText()), "负载不应含 admin 用户");
        }
        // uA 未完成：s1,s2,s4,s5 = 4；uB 未完成：s3,s6,s7 = 3
        assertEquals(4, a, "uA 未完成数");
        assertEquals(3, b, "uB 未完成数");
        // 与 DB 手工 SQL 一致
        assertEquals(4, Db.scalarInt(Conf.DB_TASK,
                "SELECT count(*) FROM task WHERE assignee_id=? AND status IN ('new','doing','wait')",
                uA.id()), "uA DB 口径");
        assertEquals(3, Db.scalarInt(Conf.DB_TASK,
                "SELECT count(*) FROM task WHERE assignee_id=? AND status IN ('new','doing','wait')",
                uB.id()), "uB DB 口径");
    }

    private JsonNode overview() {
        return Api.getData("/stats/api/v1/overview", admin,
                Map.of("range", "custom", "start", D, "end", D));
    }
}
