package com.taskflow.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应信封（接口设计文档 1.2）。
 * 成功：{ code: 0, message: "ok", data }
 * 失败：{ code: 业务码, message, details }
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private Object details;

    public Result() {
    }

    private Result(int code, String message, T data, Object details) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.details = details;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data, null);
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static Result<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }

    public static Result<Void> fail(ErrorCode errorCode, Object details) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null, details);
    }

    public static Result<Void> fail(ErrorCode errorCode, String message, Object details) {
        return new Result<>(errorCode.getCode(), message, null, details);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Object getDetails() {
        return details;
    }

    public void setDetails(Object details) {
        this.details = details;
    }
}
