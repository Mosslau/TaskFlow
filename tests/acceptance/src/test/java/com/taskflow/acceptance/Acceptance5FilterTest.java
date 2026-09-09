package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 5 · 列表筛选：6 个筛选项与范围切换任意叠加且与手工核对一致；
 * 关键字同时命中创建人/处理人姓名。
 */
@Order(5)
@DisplayName("ACC-5 列表筛选：6 筛选项 + 范围任意叠加与手工核对；关键字命中创建/处理人姓名")
public class Acceptance5FilterTest extends AccBase {

    public Acceptance5FilterTest() {
        this.cls = "fl";
    }

    /** 本类任务定义（手工核对基准） */
    private static final class Def {
        final String id;       // 自命名 A1..B4
        final long creator;    // userId
        final long assignee;
        final String type;
        final String prio;
        final String status;   // new/doing/wait
        final boolean overdue; // 是否已逾期（用于 scope=overdue 手工核对）

        Def(String id, long creator, long assignee, String type, String prio, String status, boolean overdue) {
            this.id = id;
            this.creator = creator;
            this.assignee = assignee;
            this.type = type;
            this.prio = prio;
            this.status = status;
            this.overdue = overdue;
        }
    }

    private final List<Def> defs = new ArrayList<>();
    private final Map<String, JsonNode> tasks = new LinkedHashMap<>(); // id -> created task data
    private final String kw = "ACCfl" + uniq(); // 标题统一前缀（含唯一串）

    private TUser taA;
    private TUser taB;
    private TUser uX;
    private TUser uY;
    private String taA_t;
    private String taB_t;
    private String uX_t;

    @BeforeAll
    void setup() {
        restoreDefaultMatrix();
        taA = newUser("筛选TA甲" + uniq(), Conf.ROLE_TASK_ADMIN);
        taB = newUser("筛选TA乙" + uniq(), Conf.ROLE_TASK_ADMIN);
        uX = newUser("筛选处理X" + uniq(), Conf.ROLE_USER);
        uY = newUser("筛选处理Y" + uniq(), Conf.ROLE_USER);
        taA_t = Api.login(taA.account(), taA.password()).token();
        taB_t = Api.login(taB.account(), taB.password()).token();
        uX_t = Api.login(uX.account(), uX.password()).token();

        defs.add(new Def("A1", taA.id(), uX.id(), "日常事务", "P0", "doing", false));
        defs.add(new Def("A2", taA.id(), uX.id(), "数据报表", "P1", "new", false));
        defs.add(new Def("A3", taA.id(), uY.id(), "调研分析", "P2", "doing", false));
        defs.add(new Def("A4", taA.id(), uY.id(), "流程审批", "P3", "wait", false));
        defs.add(new Def("B1", taB.id(), uX.id(), "会议事项", "P0", "new", true));  // 逾期待办
        defs.add(new Def("B2", taB.id(), uX.id(), "项目开发", "P2", "new", false));
        defs.add(new Def("B3", taB.id(), uY.id(), "日常事务", "P1", "new", false));
        defs.add(new Def("B4", taB.id(), uY.id(), "数据报表", "P2", "doing", false));

        for (Def d : defs) {
            String title = kw + "-" + d.id;
            JsonNode t = createTask(d.creator == taA.id() ? taA_t : taB_t, title,
                    d.assignee, d.type, d.prio,
                    d.overdue ? dueAtInDays(-2, "08:00") : dueAtInDays(15, "18:00"), null);
            tasks.put(d.id, t);
            long id = t.path("id").asLong();
            long assigneeId = d.assignee;
            String aToken = assigneeId == uX.id() ? uX_t
                    : Api.login(uY.account(), uY.password()).token();
            if ("doing".equals(d.status) || "wait".equals(d.status)) {
                Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/accept", aToken, null, null));
            }
            if ("wait".equals(d.status)) {
                Api.expectOk(Api.raw("POST", "/task/api/v1/tasks/" + id + "/submit-acceptance",
                        aToken, null, null));
            }
        }
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    /** 手工核对：按条件过滤 defs */
    private Set<String> expected(Map<String, Object> cond) {
        Set<String> out = new LinkedHashSet<>();
        for (Def d : defs) {
            boolean ok = true;
            if (cond.containsKey("keyword")) {
                String k = (String) cond.get("keyword");
                if (k.equals(kw)) {
                    // 通用前缀：全类任务
                } else if (k.startsWith(kw + "-")) {
                    ok = k.equals(kw + "-" + d.id);
                } else if (k.contains(taA.name())) {
                    ok = d.creator == taA.id();
                } else if (k.contains(taB.name())) {
                    ok = d.creator == taB.id();
                } else if (k.contains(uX.name())) {
                    ok = d.assignee == uX.id();
                } else if (k.contains(uY.name())) {
                    ok = d.assignee == uY.id();
                } else {
                    ok = false;
                }
            }
            if (cond.containsKey("status") && !cond.get("status").equals(d.status)) {
                ok = false;
            }
            if (cond.containsKey("priority") && !cond.get("priority").equals(d.prio)) {
                ok = false;
            }
            if (cond.containsKey("taskType") && !cond.get("taskType").equals(d.type)) {
                ok = false;
            }
            if (cond.containsKey("creatorId")
                    && ((Number) cond.get("creatorId")).longValue() != d.creator) {
                ok = false;
            }
            if (cond.containsKey("assigneeId")
                    && ((Number) cond.get("assigneeId")).longValue() != d.assignee) {
                ok = false;
            }
            if (ok) {
                out.add(tasks.get(d.id).path("taskNo").asText());
            }
        }
        return out;
    }

    /** 调列表接口（带分页循环直到取完）并返回 taskNo 集合 */
    private Set<String> apiTaskNos(String token, Map<String, Object> query) {
        Map<String, Object> q = new LinkedHashMap<>(query);
        q.putIfAbsent("size", 50);
        Set<String> out = new LinkedHashSet<>();
        int page = 1;
        long total;
        do {
            q.put("page", page);
            JsonNode d = Api.getData("/task/api/v1/tasks", token, q);
            total = d.path("total").asLong();
            for (JsonNode n : d.path("list")) {
                out.add(n.path("taskNo").asText());
            }
            page++;
            if (page > 20) {
                throw new IllegalStateException("翻页过深，用例设计问题 total=" + total);
            }
        } while (out.size() < total);
        return out;
    }

    private void assertMatch(String token, Map<String, Object> cond, String desc) {
        // 关键字前缀只命中本类任务时，API 全集 = 手工核对
        Set<String> expect = expected(cond);
        Set<String> got = apiTaskNos(token, cond);
        assertEquals(expect, got, "筛选与手工核对不一致: " + desc);
    }

    @Test
    @Order(1)
    @DisplayName("6 个筛选项各自生效并与手工核对一致")
    void t01_singleFilters() {
        // 关键字精确命中标题
        assertMatch(adminToken(), Map.of("keyword", kw + "-A1"), "keyword=精确标题");
        // 状态
        assertMatch(adminToken(), Map.of("keyword", kw, "status", "doing"), "status=doing");
        assertMatch(adminToken(), Map.of("keyword", kw, "status", "wait"), "status=wait");
        // 优先级
        assertMatch(adminToken(), Map.of("keyword", kw, "priority", "P0"), "priority=P0");
        assertMatch(adminToken(), Map.of("keyword", kw, "priority", "P3"), "priority=P3");
        // 任务类型
        assertMatch(adminToken(), Map.of("keyword", kw, "taskType", "数据报表"), "taskType=数据报表");
        assertMatch(adminToken(), Map.of("keyword", kw, "taskType", "调研分析"), "taskType=调研分析");
        // 创建人
        assertMatch(adminToken(), Map.of("keyword", kw, "creatorId", taA.id()), "creatorId=taA");
        assertMatch(adminToken(), Map.of("keyword", kw, "creatorId", taB.id()), "creatorId=taB");
        // 处理人
        assertMatch(adminToken(), Map.of("keyword", kw, "assigneeId", uX.id()), "assigneeId=uX");
        assertMatch(adminToken(), Map.of("keyword", kw, "assigneeId", uY.id()), "assigneeId=uY");
    }

    @Test
    @Order(2)
    @DisplayName("筛选项任意叠加：AND 语义与手工核对一致")
    void t02_combinedFilters() {
        assertMatch(adminToken(), Map.of("keyword", kw, "creatorId", taA.id(), "assigneeId", uY.id()),
                "creator=taA + assignee=uY");
        assertMatch(adminToken(), Map.of("keyword", kw, "priority", "P0", "assigneeId", uX.id()),
                "P0 + assignee=uX");
        assertMatch(adminToken(), Map.of("keyword", kw, "taskType", "日常事务", "creatorId", taA.id()),
                "日常事务 + creator=taA");
        assertMatch(adminToken(), Map.of("keyword", kw, "status", "new", "creatorId", taB.id()),
                "new + creator=taB");
        assertMatch(adminToken(), Map.of("keyword", kw, "priority", "P2", "taskType", "数据报表",
                "creatorId", taB.id(), "assigneeId", uY.id()), "P2+数据报表+taB+uY");
        assertMatch(adminToken(), Map.of("keyword", kw + "-B1"), "精确标题B1（叠加在单一条件后仍正确）");
    }

    @Test
    @Order(3)
    @DisplayName("关键字命中创建人姓名与处理人姓名")
    void t03_keywordHitsNames() {
        // 关键字 = 创建人姓名：命中其创建的任务（A 组）
        JsonNode a1 = Api.getData("/task/api/v1/tasks", adminToken(),
                Map.of("keyword", taA.name(), "size", 50));
        Set<String> a1nos = new LinkedHashSet<>();
        a1.path("list").forEach(n -> a1nos.add(n.path("taskNo").asText()));
        for (String id : new String[]{"A1", "A2", "A3", "A4"}) {
            assertTrue(a1nos.contains(tasks.get(id).path("taskNo").asText()),
                    "关键字=创建人姓名应命中任务 " + id);
        }
        // 关键字 = 处理人姓名：命中指派给 uX 的任务（A1,A2,B1,B2）
        JsonNode ux = Api.getData("/task/api/v1/tasks", adminToken(),
                Map.of("keyword", uX.name(), "size", 50));
        Set<String> uxnos = new LinkedHashSet<>();
        ux.path("list").forEach(n -> uxnos.add(n.path("taskNo").asText()));
        for (String id : new String[]{"A1", "A2", "B1", "B2"}) {
            assertTrue(uxnos.contains(tasks.get(id).path("taskNo").asText()),
                    "关键字=处理人姓名应命中任务 " + id);
        }
        // 姓名关键字也应按可见性过滤（普通用户 uX 只看到自己相关的）
        JsonNode uxSelf = Api.getData("/task/api/v1/tasks", uX_t,
                Map.of("keyword", uX.name(), "size", 50));
        for (JsonNode n : uxSelf.path("list")) {
            String no = n.path("taskNo").asText();
            assertTrue(uxnos.contains(no), "普通用户关键字结果不应出现无关任务 " + no);
        }
    }

    @Test
    @Order(4)
    @DisplayName("范围切换（全部/我创建的/指派给我的/已逾期）叠加筛选")
    void t04_scopes() {
        // scope=mine（taA 创建）：A1..A4
        Set<String> mine = apiTaskNos(taA_t, Map.of("keyword", kw, "scope", "mine", "size", 50));
        assertEquals(Set.of(tasks.get("A1").path("taskNo").asText(), tasks.get("A2").path("taskNo").asText(),
                tasks.get("A3").path("taskNo").asText(), tasks.get("A4").path("taskNo").asText()), mine,
                "scope=mine");
        // scope=assigned（uX 处理）：A1,A2,B1,B2
        Set<String> assigned = apiTaskNos(uX_t, Map.of("keyword", kw, "scope", "assigned", "size", 50));
        assertEquals(Set.of(tasks.get("A1").path("taskNo").asText(), tasks.get("A2").path("taskNo").asText(),
                tasks.get("B1").path("taskNo").asText(), tasks.get("B2").path("taskNo").asText()), assigned,
                "scope=assigned");
        // scope=overdue + keyword 前缀（仅 B1 逾期）：结果须含 B1 且不含其余
        Set<String> overdue = apiTaskNos(adminToken(), Map.of("keyword", kw, "scope", "overdue", "size", 50));
        assertEquals(Set.of(tasks.get("B1").path("taskNo").asText()), overdue, "scope=overdue");
        // scope=overdue 与状态叠加：B1 是待办
        Set<String> overdueNew = apiTaskNos(adminToken(),
                Map.of("keyword", kw, "scope", "overdue", "status", "new", "size", 50));
        assertEquals(Set.of(tasks.get("B1").path("taskNo").asText()), overdueNew, "overdue+new");
        // scope=assigned + creator（uX 处理 + taB 创建）→ B1,B2
        Set<String> assignedB = apiTaskNos(uX_t,
                Map.of("keyword", kw, "scope", "assigned", "creatorId", taB.id(), "size", 50));
        assertEquals(Set.of(tasks.get("B1").path("taskNo").asText(), tasks.get("B2").path("taskNo").asText()),
                assignedB, "assigned+creator");
    }
}
