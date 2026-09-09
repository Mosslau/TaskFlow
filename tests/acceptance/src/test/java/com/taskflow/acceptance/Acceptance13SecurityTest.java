package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收条件 13 · 安全：密码 BCrypt 存储；连续 5 次登录失败锁定 15 分钟（Redis auth:lock 键）；
 * 越权访问他人任务返回 403。
 */
@Order(13)
@DisplayName("ACC-13 安全：BCrypt 存储 / 5 次失败锁定 15 分钟 / 越权 403")
public class Acceptance13SecurityTest extends AccBase {

    public Acceptance13SecurityTest() {
        this.cls = "sec";
    }

    private TUser victim;   // 正常用户（其任务被越权尝试访问）
    private TUser attacker; // 越权用户
    private String attackerToken;
    private long victimTaskId;

    @BeforeAll
    void setup() {
        restoreDefaultMatrix();
        victim = newUser("安全受害用户" + uniq(), Conf.ROLE_USER);
        attacker = newUser("安全越权用户" + uniq(), Conf.ROLE_USER);
        attackerToken = Api.login(attacker.account(), attacker.password()).token();
        String vToken = Api.login(victim.account(), victim.password()).token();
        // 用 admin 建一个受害任务
        JsonNode t = createTask(adminToken(), title("SEC1"), victim.id(), null, null,
                dueAtInDays(8, "18:00"), null);
        victimTaskId = t.path("id").asLong();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
        // 清除本类锁定测试账号的 Redis 残留（防止污染后续登录）
        try (redis.clients.jedis.Jedis jedis = new redis.clients.jedis.Jedis(Conf.REDIS_HOST, Conf.REDIS_PORT)) {
            jedis.auth(Conf.REDIS_PASSWORD);
            jedis.del("auth:lock:" + victim.account(), "auth:fail:" + victim.account(),
                    "auth:lock:" + attacker.account(), "auth:fail:" + attacker.account());
        } catch (Exception ignore) {
        }
    }

    @Test
    @Order(1)
    @DisplayName("密码以 BCrypt 存储（$2 前缀，非明文）")
    void t01_bcryptStored() {
        String hash = Db.scalar(Conf.DB_AUTH, "SELECT password_hash FROM app_user WHERE account=?", victim.account());
        assertTrue(hash != null && hash.startsWith("$2"), "密码哈希应以 BCrypt($2) 存储: " + hash);
        assertTrue(!hash.contains(victim.password()), "不得存明文");
    }

    @Test
    @Order(2)
    @DisplayName("连续 5 次失败锁定 15 分钟：3002×4 → 3003；Redis 出现 auth:lock 键（TTL≈900s）")
    void t02_lockAfterFiveFails() {
        String bad = "WrongPass_1";
        for (int i = 1; i <= 4; i++) {
            Response r = Api.raw("POST", "/auth/api/v1/login", null, null,
                    Map.of("account", victim.account(), "password", bad));
            Api.expectErr(r, 401, 3002);
            JsonNode details = Api.detailsOf(r);
            assertEquals(5 - i, details.path("remainingAttempts").asInt(-1), "第 " + i + " 次失败剩余次数");
        }
        // 第 5 次：锁定
        Api.expectErr(Api.raw("POST", "/auth/api/v1/login", null, null,
                Map.of("account", victim.account(), "password", bad)), 401, 3003);
        // 锁定期间再试仍 3003
        Api.expectErr(Api.raw("POST", "/auth/api/v1/login", null, null,
                Map.of("account", victim.account(), "password", bad)), 401, 3003);
        // Redis auth:lock 键存在且 TTL ≈ 15 分钟
        try (redis.clients.jedis.Jedis jedis = new redis.clients.jedis.Jedis(Conf.REDIS_HOST, Conf.REDIS_PORT)) {
            jedis.auth(Conf.REDIS_PASSWORD);
            assertTrue(jedis.exists("auth:lock:" + victim.account()), "Redis 应存在 auth:lock 键");
            long ttl = jedis.ttl("auth:lock:" + victim.account());
            assertTrue(ttl > 800 && ttl <= 900, "auth:lock TTL 应约 900s（15 分钟），实际 " + ttl);
        }
        // 正确密码也被拒（锁定期内）
        Api.expectErr(Api.raw("POST", "/auth/api/v1/login", null, null,
                Map.of("account", victim.account(), "password", victim.password())), 401, 3003);
    }

    @Test
    @Order(3)
    @DisplayName("越权访问他人任务返回 403：详情/评论/附件/编辑/操作")
    void t03_forbiddenOnOthersTask() {
        // 普通用户访问无关任务（详情）→ 403 + 2001
        Response d = Api.raw("GET", "/task/api/v1/tasks/" + victimTaskId, attackerToken, null, null);
        assertEquals(403, d.getStatusCode(), "越权详情应 403");
        assertEquals(2001, Api.code(d));
        // 编辑、删除、受理、验收通过、评论、上传附件全部不可达（2001/3001，HTTP 403/400）
        int[] forbiddenHttp = {403, 403, 403, 403, 403, 403};
        int[] forbiddenBiz = {2001, 2001, 2001, 2001, 2001, 2001};
        Response[] ops = {
                Api.raw("PUT", "/task/api/v1/tasks/" + victimTaskId, attackerToken, null, Map.of("title", "x")),
                Api.raw("DELETE", "/task/api/v1/tasks/" + victimTaskId, attackerToken, null, null),
                Api.raw("POST", "/task/api/v1/tasks/" + victimTaskId + "/accept", attackerToken, null, null),
                Api.raw("POST", "/task/api/v1/tasks/" + victimTaskId + "/approve", attackerToken, null, null),
                Api.raw("GET", "/task/api/v1/tasks/" + victimTaskId + "/comments", attackerToken, null, null),
                Api.raw("POST", "/task/api/v1/tasks/" + victimTaskId + "/comments", attackerToken, null,
                        Map.of("content", "越权评论")),
        };
        for (int i = 0; i < ops.length; i++) {
            assertEquals(forbiddenHttp[i], ops[i].getStatusCode(), "越权操作 HTTP 不符 idx=" + i);
            assertEquals(forbiddenBiz[i], Api.code(ops[i]), "越权操作业务码不符 idx=" + i);
        }
        // admin 用户管理接口对普通用户封闭
        Api.expectErr(Api.raw("GET", "/auth/api/v1/users", attackerToken, Map.of(), null), 403, 3001);
    }
}
