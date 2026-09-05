package com.taskflow.common;

/**
 * 业务错误码全表（接口设计文档第 6 章）。
 *
 * <p>分段规则：</p>
 * <ul>
 *   <li>0 —— 唯一成功码</li>
 *   <li>1xxx —— 通用（参数、限流、非法请求）</li>
 *   <li>2xxx —— 任务域（状态机、子任务、附件、导入导出）</li>
 *   <li>3xxx —— 权限与认证（权限点、登录、令牌、API Key）</li>
 *   <li>4xxx —— 通知域</li>
 *   <li>5000 —— 服务端兜底</li>
 * </ul>
 *
 * <p>每个枚举值同时携带对应的 HTTP 状态码：业务码给前端做精细提示，
 * HTTP 状态码给网关/浏览器/监控做粗粒度判断（接口文档 1.2：不用 200 包一切）。</p>
 *
 * <p>安全约定：{@link #TASK_NOT_FOUND_OR_INVISIBLE} 对"不存在"与"不可见"返回同一码，
 * 防止遍历 id 探测他人任务是否存在。</p>
 */
public enum ErrorCode {

    // ========== 通用 1xxx ==========

    /** 成功（唯一成功码） */
    SUCCESS(0, "ok", 200),

    /** 请求参数不合法；details 携带逐字段错误 [{field, reason}] */
    PARAM_INVALID(1001, "参数校验失败", 400),

    /** 路径/查询指向的资源不存在 */
    RESOURCE_NOT_FOUND(1002, "资源不存在", 404),

    /** 触发网关限流；details 携带 retryAfterSeconds */
    RATE_LIMITED(1003, "触发限流", 429),

    /** 非法请求，如同时携带 JWT 与 API Key 两种互斥凭证 */
    BAD_REQUEST(1004, "非法请求", 400),

    // ========== 任务 2xxx ==========

    /** 任务不存在或当前用户不可见（同码返回，防探测） */
    TASK_NOT_FOUND_OR_INVISIBLE(2001, "任务不存在或不可见", 403),

    /** 状态机前置状态不满足；details 携带 {currentStatus, requiredStatus} */
    ILLEGAL_STATE_TRANSITION(2002, "非法状态流转，前置状态不满足", 400),

    /** 操作人身份不符（如非处理人提交验收、非创建人验收） */
    OPERATOR_MISMATCH(2003, "操作人身份不符", 400),

    /** 父任务存在未完成子任务，禁止提交验收；details 携带 {unfinishedCount}（PRD 4.1.7） */
    UNFINISHED_SUBTASKS(2004, "存在未完成子任务，禁止提交验收", 400),

    /** 已归档任务只读，拒绝一切编辑/转派/删除（PRD 4.1.2 补充规则 3） */
    TASK_ARCHIVED_READONLY(2005, "任务已归档，只读", 400),

    /** 删除仅限待办状态，任何角色含 admin 不例外（PRD 3.5.4） */
    DELETE_ONLY_TODO(2006, "删除仅限待办状态", 400),

    /** 处理人不合法：admin 角色 / 已停用 / 不存在（PRD 4.1.1 处理人约束） */
    INVALID_ASSIGNEE(2007, "处理人不合法（admin / 已停用 / 不存在）", 400),

    /** 附件超限；details 携带 {limit: "size|count|type"}（PRD 4.1.6） */
    ATTACHMENT_LIMIT_EXCEEDED(2008, "附件超限（大小 / 数量 / 类型）", 400),

    /** 进度值非 0-100 或步进非 5（PRD 4.1.1 进度约束） */
    INVALID_PROGRESS(2009, "进度值非法（须为 0-100 且步进 5）", 400),

    /** CSV 导出超 10000 行上限（PRD 4.2.3） */
    EXPORT_LIMIT_EXCEEDED(2010, "导出行数超上限（10000 行）", 400),

    /** 父任务不存在或自身是子任务（一级嵌套约束，PRD 4.1.7） */
    INVALID_PARENT_TASK(2011, "父任务非法（不存在 / 非顶层）", 400),

    /** Excel 导入校验失败整批拒绝；details 携带逐行错误 [{row, reason}]（PRD 4.7） */
    IMPORT_VALIDATION_FAILED(2012, "导入校验失败，整批未入库", 400),

    // ========== 权限 3xxx ==========

    /** 缺少权限点；details 携带 {required, roleKey}（PRD 3.5.2 拒绝原因提示） */
    PERMISSION_DENIED(3001, "缺少权限点", 403),

    /** 账号或密码错误；details 携带 {remainingAttempts} 剩余尝试次数 */
    BAD_CREDENTIALS(3002, "账号或密码错误", 401),

    /** 连续失败 5 次锁定 15 分钟；details 携带 {unlockAt}（PRD 7.3.1） */
    ACCOUNT_LOCKED(3003, "账号锁定中（15 分钟）", 401),

    /** 账号已停用，禁止登录（PRD 4.5.1） */
    ACCOUNT_DISABLED(3004, "账号已停用", 401),

    /** JWT 无效 / 过期 / 在黑名单（登出、改密后旧令牌即入黑名单） */
    TOKEN_INVALID(3005, "令牌无效或已过期", 401),

    /** API Key 无效 / 已停用 / 访问了未对 Key 开放的接口（一期 Key 仅开放创建任务） */
    API_KEY_INVALID(3006, "API Key 无效、已停用或无权访问该接口", 401),

    /** 新增用户时账号重复（app_user.account 唯一约束） */
    ACCOUNT_EXISTS(3007, "账号已存在", 400),

    /** 部门下存在在职用户，不可删除；details 携带 {userCount}（PRD 4.5.2） */
    DEPARTMENT_HAS_USERS(3008, "部门存在在职用户，不可删除", 400),

    /** admin 的 manageUser / setPerm 锁定为开启，防系统失去管理入口（PRD 4.5.4） */
    ADMIN_PERM_LOCKED(3009, "admin 的 manageUser / setPerm 不可关闭", 400),

    // ========== 通知 4xxx ==========

    /** 站内消息不存在或试图操作他人消息 */
    NOTIFICATION_NOT_FOUND(4001, "通知不存在或非本人", 403),

    // ========== 系统兜底 ==========

    /** 未预期异常兜底；details 携带 {traceId} 便于排查 */
    INTERNAL_ERROR(5000, "服务端内部错误", 500);

    /** 业务错误码（响应信封 code 字段） */
    private final int code;

    /** 默认提示文案（可被具体场景的自定义文案覆盖） */
    private final String message;

    /** 对应的 HTTP 状态码 */
    private final int httpStatus;

    /**
     * 枚举构造。
     *
     * @param code       业务错误码
     * @param message    默认提示文案
     * @param httpStatus 对应 HTTP 状态码
     */
    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /** @return 业务错误码 */
    public int getCode() {
        return code;
    }

    /** @return 默认提示文案 */
    public String getMessage() {
        return message;
    }

    /** @return 对应 HTTP 状态码 */
    public int getHttpStatus() {
        return httpStatus;
    }
}
