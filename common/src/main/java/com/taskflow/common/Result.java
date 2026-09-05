package com.taskflow.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应信封（接口设计文档 1.2 节）。
 *
 * <p>全系统所有 REST 接口的返回值都包装成这个结构，前端 axios 响应拦截器按
 * {@code code == 0} 判断成功并拆出 {@code data}：</p>
 *
 * <pre>{@code
 * // 成功：{ "code": 0, "message": "ok", "data": { ... }, "details": null }
 * // 失败：{ "code": 2002, "message": "非法状态流转，前置状态不满足", "data": null, "details": {...} }
 * }</pre>
 *
 * <p>成功时业务数据放 {@code data}；失败时补充信息（如逐字段校验错误、缺少的权限点）
 * 放 {@code details}，二者不会同时有值。</p>
 *
 * @param <T> 业务数据类型
 */
// @JsonInclude(ALWAYS)：序列化时空字段也输出（details: null 不省略），
// 保证前端拿到的 JSON 结构字段恒定，不用判断字段是否存在
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Result<T> {

    /** 业务错误码，0 表示成功，其余见 {@link ErrorCode}（1xxx 通用 / 2xxx 任务 / 3xxx 权限 / 4xxx 通知） */
    private int code;

    /** 人类可读的提示信息；失败时即展示给用户的文案 */
    private String message;

    /** 成功时的业务数据载荷；失败时为 null */
    private T data;

    /** 失败时的结构化补充（如 [{"field":"title","reason":"必填"}]）；成功时为 null */
    private Object details;

    /**
     * 无参构造：留给 Jackson 反序列化用（如 Feign 调用解析对方服务的响应）。
     * 业务代码不直接用它，统一走 {@link #ok} / {@link #fail} 静态工厂。
     */
    public Result() {
    }

    /**
     * 全字段私有构造，集中控制四种字段组合的合法性（由静态工厂调用）。
     */
    private Result(int code, String message, T data, Object details) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.details = details;
    }

    /**
     * 成功（带业务数据）。
     *
     * @param data 业务数据，可以是对象、列表、null
     * @param <T>  业务数据类型
     * @return code=0 的信封
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data, null);
    }

    /**
     * 成功（无业务数据，如删除、已读等动作类接口）。
     *
     * @return code=0 且 data=null 的信封
     */
    public static Result<Void> ok() {
        return ok(null);
    }

    /**
     * 失败（使用错误码的默认文案，无补充信息）。
     *
     * @param errorCode 错误码枚举
     * @return 失败信封
     */
    public static Result<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }

    /**
     * 失败（默认文案 + 结构化补充）。
     *
     * @param errorCode 错误码枚举
     * @param details   结构化补充，如缺少的权限点 {@code {"required":"exportData","roleKey":"user"}}
     * @return 失败信封
     */
    public static Result<Void> fail(ErrorCode errorCode, Object details) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null, details);
    }

    /**
     * 失败（自定义文案 + 结构化补充）。
     * 用于默认文案不够具体的场景，如"该任务存在 2 个未完成子任务"。
     *
     * @param errorCode 错误码枚举
     * @param message   覆盖默认文案的自定义提示
     * @param details   结构化补充
     * @return 失败信封
     */
    public static Result<Void> fail(ErrorCode errorCode, String message, Object details) {
        return new Result<>(errorCode.getCode(), message, null, details);
    }

    // ========== getter / setter：Jackson 序列化与业务读取用 ==========

    /** @return 业务错误码，0 为成功 */
    public int getCode() {
        return code;
    }

    /** @param code 业务错误码 */
    public void setCode(int code) {
        this.code = code;
    }

    /** @return 提示文案 */
    public String getMessage() {
        return message;
    }

    /** @param message 提示文案 */
    public void setMessage(String message) {
        this.message = message;
    }

    /** @return 业务数据载荷 */
    public T getData() {
        return data;
    }

    /** @param data 业务数据载荷 */
    public void setData(T data) {
        this.data = data;
    }

    /** @return 失败补充信息 */
    public Object getDetails() {
        return details;
    }

    /** @param details 失败补充信息 */
    public void setDetails(Object details) {
        this.details = details;
    }
}
