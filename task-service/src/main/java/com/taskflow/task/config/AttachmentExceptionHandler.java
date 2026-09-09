package com.taskflow.task.config;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 附件上传异常处理（PRD 4.1.6）：
 * 超过 multipart 容器上限（spring.servlet.multipart.max-file-size）的请求
 * 由容器在进 Controller 前抛出，这里统一转 2008 附件超限。
 */
@RestControllerAdvice
public class AttachmentExceptionHandler {

    /** multipart 超限（文件/请求体过大）→ 2008，details.limit=size */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED.getHttpStatus())
                .body(Result.fail(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                        "单文件大小超过 20MB 上限", Map.of("limit", "size")));
    }
}
