package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 7 · 日程视图：月历每日聚合与按到期日筛选的列表一致；普通用户日历不含无权限任务。
 */
@Order(7)
@DisplayName("ACC-7 日程：月历聚合 vs 按到期日列表(SQL 核对)；普通用户日历无越权任务")
public class Acceptance7CalendarTest extends AccBase {

    public Acceptance7CalendarTest() {
        this.cls = "cal";
    }

    /** 到期日所在月（未来月份，避免与既有数据日期冲突带来的噪音） */
    private static final String MONTH = "2026-12";
    private static final String D1 = "2026-12-15";
    private static final String D2 = "2026-12-16";
    private static final String D3 = "2026-12-17";

    private TUser ta;     // 创建人（taskAdmin）
    private TUser u1;     // 处理人
    private TUser u2;     // 处理人
    private TUser stranger; // 普通用户：与全部数据无关
    private String ta_t;
    private String u1_t;
    private String u2_t;
    private String stranger_t;

    private JsonNode tA1; // D1 assignee u1
    private JsonNode tA2; // D1 assignee u2
    private JsonNode tB1; // D2 assignee u1
    private JsonNode tC1; // D3 assignee u1（含子任务 tC1c 到期 D3，验证含子任务聚合）
    private JsonNode child;

    @BeforeAll
    void setup() {
        restoreDefaultMatrix();
        ta = newUser("日程创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        u1 = newUser("日程处理一" + uniq(), Conf.ROLE_USER);
        u2 = newUser("日程处理二" + uniq(), Conf.ROLE_USER);
        stranger = newUser("日程无关人" + uniq(), Conf.ROLE_USER);
        ta_t = Api.login(ta.account(), ta.password()).token();
        u1_t = Api.login(u1.account(), u1.password()).token();
        u2_t = Api.login(u2.account(), u2.password()).token();
        stranger_t = Api.login(stranger.account(), stranger.password()).token();

        tA1 = createTask(ta_t, title("CA1"), u1.id(), null, null, D1 + "T10:00:00+08:00", null);
        tA2 = createTask(ta_t, title("CA2"), u2.id(), null, null, D1 + "T15:00:00+08:00", null);
        tB1 = createTask(ta_t, title("CB1"), u1.id(), null, null, D2 + "T10:00:00+08:00", null);
        tC1 = createTask(ta_t, title("CC1"), u1.id(), null, null, D3 + "T10:00:00+08:00", null);
        // 子任务（到期 D3，与父同一天）：日程含子任务
        child = createTask(ta_t, title("CC1C"), u1.id(), null, null, D3 + "T12:00:00+08:00", tC1.path("id").asLong());
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    /** admin 视角：某天到期任务 = SQL 按到期日查询结果（列表口径基准） */
    @Test
    @Order(1)
    @DisplayName("admin 月历：每日聚合与按到期日（SQL）结果一致，且含子任务")
    void t01_adminCalendarMatchesDueDateList() {
        JsonNode cal = Api.getData("/task/api/v1/tasks/calendar", adminToken(), Map.of("month", MONTH));
        // 把月历拍平成 date → taskNo 集合
        Map<String, Set<String>> byDay = new java.util.HashMap<>();
        for (JsonNode day : cal) {
            String date = day.path("date").asText();
            Set<String> nos = byDay.computeIfAbsent(date, k -> new LinkedHashSet<>());
            day.path("tasks").forEach(t -> nos.add(t.path("taskNo").asText()));
        }
        for (String d : List.of(D1, D2, D3)) {
            Set<String> expect = sqlDueDayNos(d, null);
            assertEquals(expect, byDay.getOrDefault(d, Set.of()), "admin 月历 " + d + " 聚合与到期日列表不一致");
        }
        // 子任务出现在月历中
        Set<String> d3 = byDay.getOrDefault(D3, Set.of());
        assertTrue(d3.contains(child.path("taskNo").asText()), "月历应含子任务");
        assertTrue(d3.contains(tC1.path("taskNo").asText()), "月历应含父任务");
    }

    /** 普通用户视角：日历仅含指派给自己的任务，无越权 */
    @Test
    @Order(2)
    @DisplayName("普通用户日历只含自己处理的任务；无关用户日历为空")
    void t02_userCalendarOnlyVisible() {
        // u1（user）：可见 = 指派给自己（默认矩阵下 user 仅 viewAssigned）
        Set<String> u1expect = new LinkedHashSet<>();
        u1expect.addAll(sqlDueDayNos(D1, u1.id()));
        u1expect.addAll(sqlDueDayNos(D2, u1.id()));
        u1expect.addAll(sqlDueDayNos(D3, u1.id()));
        Map<String, Set<String>> u1cal = flatten(calendar(u1_t));
        Set<String> got = new LinkedHashSet<>();
        for (String d : List.of(D1, D2, D3)) {
            got.addAll(u1cal.getOrDefault(d, Set.of()));
        }
        assertEquals(u1expect, got, "u1 日历 D1-D3 应等于其可见任务");
        // u1 日历不含无关 u2 任务
        JsonNode u1CalNode = calendar(u1_t);
        for (JsonNode day : u1CalNode) {
            for (JsonNode t : day.path("tasks")) {
                if (t.path("taskNo").asText().equals(tA2.path("taskNo").asText())) {
                    throw new AssertionError("u1 日历不应含无关任务 " + tA2.path("taskNo").asText());
                }
            }
        }
        // 无关用户（stranger）：日历无任何越权任务（其可见集合为空）
        for (JsonNode day : calendar(stranger_t)) {
            assertEquals(0, day.path("tasks").size(), "无关用户日历应为空: " + day);
        }
        // u2 可见 D1 的任务 tA2，不含 tA1/tB1/tC1
        Set<String> u2day1 = flatten(calendar(u2_t)).getOrDefault(D1, Set.of());
        assertTrue(u2day1.contains(tA2.path("taskNo").asText()), "u2 应看到 D1 自己的任务");
        assertEquals(false, u2day1.contains(tA1.path("taskNo").asText()), "u2 不应看到他人任务");
    }

    // ---------- 工具 ----------

    private JsonNode calendar(String token) {
        return Api.getData("/task/api/v1/tasks/calendar", token, Map.of("month", MONTH));
    }

    private Map<String, Set<String>> flatten(JsonNode cal) {
        Map<String, Set<String>> m = new java.util.HashMap<>();
        for (JsonNode day : cal) {
            Set<String> nos = m.computeIfAbsent(day.path("date").asText(), k -> new LinkedHashSet<>());
            day.path("tasks").forEach(t -> nos.add(t.path("taskNo").asText()));
        }
        return m;
    }

    /** SQL 按到期日查询某天可见任务集合（visibility: admin 或 userId 可见规则） */
    private Set<String> sqlDueDayNos(String date, Long userId) {
        StringBuilder sql = new StringBuilder("SELECT task_no FROM task WHERE due_at >= ?::timestamptz AND due_at < ?::timestamptz");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(date + "T00:00:00+08:00");
        String next = java.time.LocalDate.parse(date).plusDays(1).toString();
        args.add(next + "T00:00:00+08:00");
        if (userId != null) {
            // user 角色默认矩阵：仅 viewAssigned → 可见 = assignee=me
            sql.append(" AND assignee_id = ?");
            args.add(userId);
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> row : Db.rows(Conf.DB_TASK, sql.toString(), args.toArray())) {
            out.add(String.valueOf(row.get("task_no")));
        }
        return out;
    }
}
