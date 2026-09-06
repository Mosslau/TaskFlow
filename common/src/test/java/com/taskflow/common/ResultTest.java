package com.taskflow.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Result 响应信封单元测试。
 */
class ResultTest {

    @Test
    @DisplayName("ok(data)：code=0，data 有值，details 为 null")
    void okWithData() {
        Result<String> r = Result.ok("pong");
        assertEquals(0, r.getCode());
        assertEquals("ok", r.getMessage());
        assertEquals("pong", r.getData());
        assertNull(r.getDetails());
    }

    @Test
    @DisplayName("fail：code/message 取错误码，data 为 null，details 透传")
    void failWithDetails() {
        Result<Void> r = Result.fail(ErrorCode.PERMISSION_DENIED, "缺少权限点 exportData",
                java.util.Map.of("required", "exportData"));
        assertEquals(3001, r.getCode());
        assertEquals("缺少权限点 exportData", r.getMessage());
        assertNull(r.getData());
        assertEquals(java.util.Map.of("required", "exportData"), r.getDetails());
    }

    @Test
    @DisplayName("错误码的 HTTP 状态映射正确（抽样）")
    void httpStatusMapping() {
        assertEquals(200, ErrorCode.SUCCESS.getHttpStatus());
        assertEquals(401, ErrorCode.TOKEN_INVALID.getHttpStatus());
        assertEquals(403, ErrorCode.PERMISSION_DENIED.getHttpStatus());
        assertEquals(500, ErrorCode.INTERNAL_ERROR.getHttpStatus());
    }
}
