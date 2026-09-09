package com.taskflow.task.controller;

import com.taskflow.common.Result;
import com.taskflow.task.config.RequirePerm;
import com.taskflow.task.service.ImportExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 任务导入导出接口（PRD 4.2.3 / 4.7，接口 #40-42）。
 *
 * <p>/tasks/export、/tasks/import-template 为字面量路径，Spring MVC 精确匹配
 * 优先于 TaskController 的 /tasks/{id}，不会落入 detail()。</p>
 */
@RestController
@RequestMapping("/task/api/v1/tasks")
public class ImportExportController {

    private final ImportExportService importExportService;

    public ImportExportController(ImportExportService importExportService) {
        this.importExportService = importExportService;
    }

    /**
     * CSV 导出（接口 #40，exportData）：与任务列表完全同参 + 同款可见性过滤，
     * 输出 UTF-8 with BOM 的 CSV 文件流，文件名 任务导出-YYYYMMDD-HHmm（东八区）。
     */
    @GetMapping("/export")
    @RequirePerm("exportData")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Long assigneeDeptId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "false") boolean topLevel,
            @RequestParam(defaultValue = "all") String scope) {
        byte[] csv = importExportService.exportCsv(keyword, status, priority, taskType,
                creatorId, assigneeId, assigneeDeptId, parentId, topLevel, scope);
        String fileName = importExportService.exportFileName();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(fileName))
                .body(csv);
    }

    /**
     * 导入模板下载（接口 #41，create）：表头加粗 + 冻结首行 + 一行示例数据。
     */
    @GetMapping("/import-template")
    @RequirePerm("create")
    public ResponseEntity<byte[]> importTemplate() {
        byte[] xlsx = importExportService.importTemplate();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("任务导入模板.xlsx"))
                .body(xlsx);
    }

    /**
     * Excel 导入（接口 #42，create）：multipart 字段 file（.xlsx，≤500 数据行）。
     * 先全量校验后单事务入库；成功返回 {batchId, totalRows, successCount}。
     */
    @PostMapping("/import")
    @RequirePerm("create")
    public Result<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) {
        return Result.ok(importExportService.importExcel(file));
    }

    /** RFC 5987 中文文件名（附 ASCII 兜底名，老客户端可读） */
    private static String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"download\"; filename*=UTF-8''" + encoded;
    }
}
