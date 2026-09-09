package com.taskflow.task.controller;

import com.taskflow.common.Result;
import com.taskflow.task.entity.TaskAttachment;
import com.taskflow.task.service.TaskAttachmentService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 任务附件接口（接口 #36-38，PRD 4.1.6）。
 */
@RestController
public class TaskAttachmentController {

    private final TaskAttachmentService attachmentService;

    public TaskAttachmentController(TaskAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /** 上传附件（接口 #36）：multipart 字段名 file；≤20MB、每任务 ≤10 个、扩展名白名单 */
    @PostMapping("/task/api/v1/tasks/{id}/attachments")
    public Result<Map<String, Object>> upload(@PathVariable Long id,
                                              @RequestParam("file") MultipartFile file) {
        return Result.ok(toItem(attachmentService.upload(id, file)));
    }

    /**
     * 下载附件（接口 #37）：文件流；Content-Disposition 用原始文件名，
     * 中文名按 RFC 5987 编码（Spring ContentDisposition 传 UTF-8 charset 即生成 filename*=UTF-8''...）。
     */
    @GetMapping("/task/api/v1/attachments/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        TaskAttachmentService.AttachmentFile file = attachmentService.download(id);
        Resource resource = new FileSystemResource(file.path());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.meta().getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.meta().getOriginalName(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(resource);
    }

    /** 删除附件（接口 #38）：仅上传人本人或 admin */
    @DeleteMapping("/task/api/v1/attachments/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        attachmentService.delete(id);
        return Result.ok();
    }

    /** 附件实体 → 响应项 */
    private Map<String, Object> toItem(TaskAttachment a) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", a.getId());
        item.put("taskId", a.getTaskId());
        item.put("originalName", a.getOriginalName());
        item.put("sizeBytes", a.getSizeBytes());
        item.put("uploaderId", a.getUploaderId());
        item.put("createdAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
        return item;
    }
}
