package com.taskflow.common;

/**
 * 业务异常：携带错误码与结构化 details（接口设计文档 1.2）。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object details;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BizException(ErrorCode errorCode, Object details) {
        this(errorCode, errorCode.getMessage(), details);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
