package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关 API 访问器：统一信封 {code,message,data,details}，HTTP 状态码与业务码分离。
 */
public final class Api {

    private static final ObjectMapper OM = new ObjectMapper();

    private Api() {
    }

    /** 登录结果 */
    public record Login(long userId, String account, String name, String roleKey,
                        List<String> permissions, String token, String refreshToken) {
    }

    /** 登录并校验成功 */
    public static Login login(String account, String password) {
        Response r = raw("POST", "/auth/api/v1/login", null, null,
                Map.of("account", account, "password", password));
        assertHttp(r, 200);
        JsonNode d = dataOf(r);
        JsonNode user = d.get("user");
        return new Login(user.path("id").asLong(), user.path("account").asText(),
                user.path("name").asText(), user.path("roleKey").asText(),
                listOf(user.path("permissions")), d.path("token").asText(),
                d.path("refreshToken").asText());
    }

    /** admin 登录（token 已缓存于 Session） */
    public static Login adminLogin() {
        return login(Conf.ADMIN_ACCOUNT, Conf.ADMIN_PASSWORD);
    }

    /** 通用请求 */
    public static Response raw(String method, String path, String token,
                               Map<String, Object> query, Object body) {
        RequestSpecification spec = RestAssured.given().baseUri(Conf.BASE)
                .accept("application/json")
                .log().ifValidationFails();
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        if (query != null && !query.isEmpty()) {
            spec.queryParams(query);
        }
        if (body != null) {
            spec.contentType("application/json");
            try {
                spec.body(OM.writeValueAsString(body));
            } catch (Exception e) {
                throw new IllegalStateException("body 序列化失败", e);
            }
        }
        return spec.request(method, path);
    }

    // ---------- 响应解析 ----------

    public static JsonNode tree(Response r) {
        try {
            return OM.readTree(r.getBody().asString());
        } catch (Exception e) {
            throw new IllegalStateException("响应非 JSON: " + safeBody(r), e);
        }
    }

    /** 业务码 */
    public static int code(Response r) {
        JsonNode n = tree(r);
        return n.has("code") ? n.path("code").asInt() : -1;
    }

    public static JsonNode dataOf(Response r) {
        return tree(r).path("data");
    }

    public static JsonNode detailsOf(Response r) {
        return tree(r).path("details");
    }

    /** 断言 HTTP 状态 */
    public static void assertHttp(Response r, int expect) {
        assertEquals(expect, r.getStatusCode(),
                "HTTP 状态不符，body=" + safeBody(r));
    }

    /** 断言成功信封：HTTP 200 且 code=0，返回 data */
    public static JsonNode expectOk(Response r) {
        assertHttp(r, 200);
        JsonNode n = tree(r);
        assertEquals(0, n.path("code").asInt(-1), "业务码非 0，body=" + safeBody(r));
        return n.path("data");
    }

    /** 断言业务错误信封：指定 HTTP 状态与业务码，返回 details */
    public static JsonNode expectErr(Response r, int httpStatus, int bizCode) {
        assertEquals(httpStatus, r.getStatusCode(),
                "HTTP 状态不符，body=" + safeBody(r));
        JsonNode n = tree(r);
        assertEquals(bizCode, n.path("code").asInt(-1),
                "业务码不符，body=" + safeBody(r));
        return n.path("details");
    }

    /** GET data 便捷方法 */
    public static JsonNode getData(String path, String token, Map<String, Object> query) {
        return expectOk(raw("GET", path, token, query, null));
    }

    private static String safeBody(Response r) {
        try {
            return r.getBody().asString();
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    private static List<String> listOf(JsonNode n) {
        if (n == null || !n.isArray()) {
            return List.of();
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }

    public static ObjectMapper om() {
        return OM;
    }

    /** 等待条件成立 */
    public static void await(long timeoutMs, long intervalMs, java.util.function.BooleanSupplier cond, String desc) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            try {
                if (cond.getAsBoolean()) {
                    return;
                }
            } catch (Throwable t) {
                last = t.getMessage();
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被打断: " + desc);
            }
        }
        throw new AssertionError("等待超时(" + timeoutMs + "ms): " + desc + (last.isEmpty() ? "" : " lastErr=" + last));
    }

    public static void assertTrue2(boolean cond, String msg) {
        assertTrue(cond, msg);
    }
}
