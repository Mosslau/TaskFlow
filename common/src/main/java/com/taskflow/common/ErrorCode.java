package com.taskflow.common;

/**
 * 业务错误码全表（接口设计文档第 6 章）。
 * 分段：1xxx 通用 / 2xxx 任务 / 3xxx 权限 / 4xxx 通知。
 */
public enum ErrorCode {

    // ========== 通用 1xxx ==========
    SUCCESS(0, "ok", 200),
    PARAM_INVALID(1001, "参数校验失败", 400),
    RESOURCE_NOT_FOUND(1002, "资源不存在", 404),
    RATE_LIMITED(1003, "触发限流", 429),
    BAD_REQUEST(1004, "非法请求", 400),

    // ========== 任务 2xxx ==========
    TASK_NOT_FOUND_OR_INVISIBLE(2001, "任务不存在或不可见", 403),
    ILLEGAL_STATE_TRANSITION(2002, "非法状态流转，前置状态不满足", 400),
    OPERATOR_MISMATCH(2003, "操作人身份不符", 400),
    UNFINISHED_SUBTASKS(2004, "存在未完成子任务，禁止提交验收", 400),
    TASK_ARCHIVED_READONLY(2005, "任务已归档，只读", 400),
    DELETE_ONLY_TODO(2006, "删除仅限待办状态", 400),
    INVALID_ASSIGNEE(2007, "处理人不合法（admin / 已停用 / 不存在）", 400),
    ATTACHMENT_LIMIT_EXCEEDED(2008, "附件超限（大小 / 数量 / 类型）", 400),
    INVALID_PROGRESS(2009, "进度值非法（须为 0-100 且步进 5）", 400),
    EXPORT_LIMIT_EXCEEDED(2010, "导出行数超上限（10000 行）", 400),
    INVALID_PARENT_TASK(2011, "父任务非法（不存在 / 非顶层）", 400),
    IMPORT_VALIDATION_FAILED(2012, "导入校验失败，整批未入库", 400),

    // ========== 权限 3xxx ==========
    PERMISSION_DENIED(3001, "缺少权限点", 403),
    BAD_CREDENTIALS(3002, "账号或密码错误", 401),
    ACCOUNT_LOCKED(3003, "账号锁定中（15 分钟）", 401),
    ACCOUNT_DISABLED(3004, "账号已停用", 401),
    TOKEN_INVALID(3005, "令牌无效或已过期", 401),
    API_KEY_INVALID(3006, "API Key 无效、已停用或无权访问该接口", 401),
    ACCOUNT_EXISTS(3007, "账号已存在", 400),
    DEPARTMENT_HAS_USERS(3008, "部门存在在职用户，不可删除", 400),
    ADMIN_PERM_LOCKED(3009, "admin 的 manageUser / setPerm 不可关闭", 400),

    // ========== 通知 4xxx ==========
    NOTIFICATION_NOT_FOUND(4001, "通知不存在或非本人", 403),

    // ========== 系统 ==========
    INTERNAL_ERROR(5000, "服务端内部错误", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
