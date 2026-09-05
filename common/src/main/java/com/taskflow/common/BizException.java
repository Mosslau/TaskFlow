package com.taskflow.common;

/**
 * 业务异常（受检规则错误的统一载体）。
 *
 * <p>用法：Service 层校验失败时 {@code throw new BizException(ErrorCode.XXX, details)}，
 * 由各服务的全局异常处理器（@RestControllerAdvice）接住，转成 {@link Result} 信封
 * 并按 {@link ErrorCode#getHttpStatus()} 设置 HTTP 状态码。</p>
 *
 * <p>继承 RuntimeException 而非受检异常：业务校验失败不需要调用方强制 catch，
 * 交给统一异常处理器收尾，保持 Service 方法签名干净。</p>
 */
public class BizException extends RuntimeException {

    /** 业务错误码（决定响应 code 与 HTTP 状态码） */
    private final ErrorCode errorCode;

    /** 结构化补充信息（透传到响应信封 details 字段），无补充时为 null */
    private final Object details;

    /**
     * 使用错误码默认文案。
     *
     * @param errorCode 错误码枚举
     */
    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    /**
     * 默认文案 + 结构化补充。
     *
     * @param errorCode 错误码枚举
     * @param details   结构化补充（如缺少的权限点）
     */
    public BizException(ErrorCode errorCode, Object details) {
        this(errorCode, errorCode.getMessage(), details);
    }

    /**
     * 自定义文案（默认文案不够具体的场景）。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义提示文案
     */
    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * 全参构造。
     *
     * @param errorCode 错误码枚举
     * @param message   提示文案（同时作为异常 message，进日志）
     * @param details   结构化补充
     */
    public BizException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    /** @return 业务错误码 */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** @return 结构化补充信息 */
    public Object getDetails() {
        return details;
    }
}
