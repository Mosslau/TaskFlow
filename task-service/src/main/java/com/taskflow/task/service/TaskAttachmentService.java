package com.taskflow.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.task.config.AuthContext;
import com.taskflow.task.entity.Task;
import com.taskflow.task.entity.TaskAttachment;
import com.taskflow.task.mapper.TaskAttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 任务附件服务（PRD 4.1.6，接口 #36-38）。
 *
 * <p>校验口径：单文件 ≤ 20MB、每任务 ≤ 10 个、扩展名白名单（大小写不敏感），
 * 任一超限返回 2008 并在 details.limit 标明 size/count/type。
 * 文件本体落盘于 taskflow.attachment.root，文件名只取随机 UUID（防路径穿越，
 * 下载侧再做一次 UUID 格式 + 目录前缀双重校验）。</p>
 *
 * <p>权限口径："任务可见即可"上传/下载；删除仅限上传人本人或 admin（3001）。</p>
 */
@Service
public class TaskAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(TaskAttachmentService.class);

    /** 单文件上限 20MB（与 task_attachment.size_bytes CHECK 一致） */
    private static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    /** 每任务附件数上限 */
    private static final int MAX_COUNT_PER_TASK = 10;

    /** 扩展名白名单（小写口径，比较前转小写） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "png", "jpg", "jpeg", "zip", "rar", "7z");

    /** 落盘文件名只许 UUID（下载/删除前的路径穿越防护） */
    private static final Pattern STORED_NAME_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final TaskAttachmentMapper attachmentMapper;
    private final TaskService taskService;

    /** 附件存储根路径（taskflow.attachment.root，架构文档 2.4） */
    private final Path attachmentRoot;

    public TaskAttachmentService(TaskAttachmentMapper attachmentMapper, TaskService taskService,
                                 @Value("${taskflow.attachment.root:/tmp/taskflow/attachments}") String attachmentRoot) {
        this.attachmentMapper = attachmentMapper;
        this.taskService = taskService;
        this.attachmentRoot = Paths.get(attachmentRoot).toAbsolutePath().normalize();
    }

    /**
     * 上传附件（接口 #36）：任务可见即可；已归档任务拒绝（2005）。
     *
     * @return 附件元数据对象
     */
    @Transactional
    public TaskAttachment upload(Long taskId, MultipartFile file) {
        Task task = taskService.requireVisible(taskId);
        if (TaskService.ST_CLOSE.equals(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_ARCHIVED_READONLY);
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件不能为空");
        }
        // ① 大小上限
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BizException(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "单文件大小超过 20MB 上限", Map.of("limit", "size"));
        }
        // ② 扩展名白名单（大小写不敏感）
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "unnamed";
        String ext = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BizException(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "文件类型不允许：" + (ext.isEmpty() ? "（无扩展名）" : ext),
                    Map.of("limit", "type"));
        }
        // ③ 每任务数量上限
        long count = attachmentMapper.selectCount(
                new LambdaQueryWrapper<TaskAttachment>().eq(TaskAttachment::getTaskId, taskId));
        if (count >= MAX_COUNT_PER_TASK) {
            throw new BizException(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "每任务附件数量超过 10 个上限", Map.of("limit", "count"));
        }

        // 落盘：文件名只取随机 UUID，与原始文件名彻底解耦（防路径穿越）
        String storedName = UUID.randomUUID().toString();
        try {
            Files.createDirectories(attachmentRoot);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, attachmentRoot.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件落盘失败");
        }

        TaskAttachment attachment = new TaskAttachment();
        attachment.setTaskId(taskId);
        attachment.setOriginalName(originalName);
        attachment.setStoredName(storedName);
        attachment.setSizeBytes(file.getSize());
        attachment.setUploaderId(AuthContext.getUserId());
        attachmentMapper.insert(attachment);
        log.info("附件上传: taskNo={}, name={}, size={}, uploader={}",
                task.getTaskNo(), originalName, file.getSize(), AuthContext.getUserId());
        // created_at 由 DB 默认值生成，回查补齐响应字段
        return attachmentMapper.selectById(attachment.getId());
    }

    /**
     * 下载附件（接口 #37）：可见性继承任务；返回元数据与落盘路径（Controller 负责流式输出）。
     *
     * @return 附件元数据 + 文件路径
     */
    public AttachmentFile download(Long attachmentId) {
        TaskAttachment attachment = mustExist(attachmentId);
        taskService.requireVisible(attachment.getTaskId());
        Path path = resolveStoredPath(attachment.getStoredName());
        if (!Files.exists(path)) {
            // 元数据在而文件丢失：视为资源不存在
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "附件文件不存在或已丢失");
        }
        return new AttachmentFile(attachment, path);
    }

    /**
     * 删除附件（接口 #38）：仅上传人本人或 admin（越权 3001）；删 DB 记录 + 删文件。
     */
    @Transactional
    public void delete(Long attachmentId) {
        TaskAttachment attachment = mustExist(attachmentId);
        taskService.requireVisible(attachment.getTaskId());
        boolean isOwner = AuthContext.getUserId().equals(attachment.getUploaderId());
        boolean isAdmin = "admin".equals(AuthContext.getRoleKey());
        if (!isOwner && !isAdmin) {
            throw new BizException(ErrorCode.PERMISSION_DENIED, "仅上传人本人或 admin 可删除附件",
                    Map.of("required", "uploader|admin", "roleKey", AuthContext.getRoleKey()));
        }
        attachmentMapper.deleteById(attachmentId);
        // 文件删除失败不影响记录删除（残留文件由运维清理）
        try {
            Files.deleteIfExists(resolveStoredPath(attachment.getStoredName()));
        } catch (IOException e) {
            log.warn("附件文件删除失败（记录已删）: storedName={}, err={}",
                    attachment.getStoredName(), e.getMessage());
        }
        log.info("附件删除: attachmentId={}, operator={}", attachmentId, AuthContext.getUserId());
    }

    /** 附件不存在统一 1002 */
    private TaskAttachment mustExist(Long attachmentId) {
        TaskAttachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "附件不存在");
        }
        return attachment;
    }

    /**
     * 落盘路径解析（路径穿越防护）：stored_name 必须严格是 UUID 格式，
     * 且解析后的归一化路径必须仍在附件根目录内。
     */
    private Path resolveStoredPath(String storedName) {
        if (storedName == null || !STORED_NAME_PATTERN.matcher(storedName).matches()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件存储名非法");
        }
        Path path = attachmentRoot.resolve(storedName).normalize();
        if (!path.startsWith(attachmentRoot)) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件路径非法");
        }
        return path;
    }

    /** 取扩展名（小写；无扩展名返回空串） */
    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    /** 下载结果：元数据 + 文件路径 */
    public record AttachmentFile(TaskAttachment meta, Path path) {
    }
}
