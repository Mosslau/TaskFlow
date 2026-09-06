package com.taskflow.task.config;

import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器（task-service）：统一 Result 信封 + HTTP 状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：按错误码返回对应 HTTP 状态与信封 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(Result.fail(e.getErrorCode(), e.getMessage(), e.getDetails()));
    }

    /** 参数类异常统一转 1001 */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> handleParam(Exception e) {
        return ResponseEntity.status(400)
                .body(Result.fail(ErrorCode.PARAM_INVALID, "参数校验失败：" + e.getMessage(), null));
    }

    /** 兜底：记日志，不带堆栈回前端 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(500).body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }
}
