package com.taskflow.task.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.task.client.UserClient;
import com.taskflow.task.config.AuthContext;
import com.taskflow.task.entity.ImportBatch;
import com.taskflow.task.entity.Task;
import com.taskflow.task.mapper.ImportBatchMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务导入导出服务（PRD 4.2.3 / 4.7，接口 #40-42）。
 *
 * <p>CSV 导出：与任务列表（接口 #19）完全同参，复用 {@link TaskService#page} 的
 * 可见性过滤（size 取上限值），total &gt; 10000 拒绝（2010）。</p>
 *
 * <p>Excel 导入：先逐行全量校验（任一行失败 → 整批不入库，2012 + 逐行明细），
 * 全部通过后在单个事务里逐行调 {@link TaskService#create}（REQUIRED 加入外层事务，
 * 自带时间线 + task.assigned 事件 outbox，通知链路自动生效）。
 * 成功/失败批次均落 import_batch（失败批次走 {@link ImportBatchRecorder} 新事务留痕）。</p>
 */
@Service
public class ImportExportService {

    private static final Logger log = LoggerFactory.getLogger(ImportExportService.class);

    /** 东八区（系统统一时区口径） */
    private static final ZoneOffset CST = ZoneOffset.ofHours(8);

    /** 导出行数上限（PRD 4.2.3） */
    private static final int EXPORT_MAX_ROWS = 10000;

    /** 导入数据行上限（PRD 4.7，与 import_batch.total_rows CHECK 一致） */
    private static final int IMPORT_MAX_ROWS = 500;

    /** 任务类型枚举（PRD 4.1.1） */
    private static final Set<String> TASK_TYPES = Set.of(
            "项目开发", "日常事务", "会议事项", "调研分析", "数据报表", "流程审批");
    /** 优先级枚举 */
    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2", "P3");

    /** 状态键 → 中文（导出列展示） */
    private static final Map<String, String> STATUS_CN = Map.of(
            TaskService.ST_NEW, "待办",
            TaskService.ST_DOING, "进行中",
            TaskService.ST_WAIT, "待验收",
            TaskService.ST_DONE, "已完成",
            TaskService.ST_CLOSE, "已关闭");

    /** CSV 表头（中文，列顺序固定） */
    private static final List<String> CSV_HEADERS = List.of(
            "任务编号", "标题", "任务类型", "优先级", "状态", "进度",
            "创建人", "处理人", "到期时间", "创建时间");

    /** 导入模板表头（* 为必填） */
    private static final List<String> TEMPLATE_HEADERS = List.of(
            "标题*", "描述", "任务类型", "优先级", "处理人账号*", "到期日期*", "到期时间", "进度");

    /** 模板示例行 */
    private static final List<String> TEMPLATE_EXAMPLE = List.of(
            "示例任务", "描述文本", "日常事务", "P2", "testuser", "2026-09-20", "18:00", "0");

    private static final DateTimeFormatter EXPORT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter EXPORT_FILENAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    /** 严格模式日期解析（2026-02-30 这类非法日期直接判错） */
    private static final DateTimeFormatter DATE_STRICT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME_STRICT =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private final TaskService taskService;
    private final UserClient userClient;
    private final ImportBatchMapper importBatchMapper;
    private final ImportBatchRecorder batchRecorder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImportExportService(TaskService taskService, UserClient userClient,
                               ImportBatchMapper importBatchMapper, ImportBatchRecorder batchRecorder) {
        this.taskService = taskService;
        this.userClient = userClient;
        this.importBatchMapper = importBatchMapper;
        this.batchRecorder = batchRecorder;
    }

    // ==================== CSV 导出（接口 #40） ====================

    /**
     * CSV 导出：与任务列表同参，复用同款可见性过滤；超 10000 行拒绝（2010）。
     *
     * @return CSV 文件字节（UTF-8 with BOM，CRLF 行尾）
     */
    public byte[] exportCsv(String keyword, String status, String priority, String taskType,
                            Long creatorId, Long assigneeId, Long assigneeDeptId,
                            Long parentId, boolean topLevel, String scope) {
        // size 取上限值、page=1：一次取全量；total 超限即拒（PRD 4.2.3）
        Page<Map<String, Object>> page = taskService.page(keyword, status, priority, taskType,
                creatorId, assigneeId, assigneeDeptId, parentId, topLevel, scope, 1, EXPORT_MAX_ROWS);
        if (page.getTotal() > EXPORT_MAX_ROWS) {
            throw new BizException(ErrorCode.EXPORT_LIMIT_EXCEEDED,
                    "导出结果 " + page.getTotal() + " 行，超过 10000 行上限，请缩小筛选范围",
                    Map.of("total", page.getTotal(), "limit", EXPORT_MAX_ROWS));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", CSV_HEADERS)).append("\r\n");
        for (Map<String, Object> item : page.getRecords()) {
            List<String> cols = new ArrayList<>();
            cols.add(csvEscape(str(item.get("taskNo"))));
            cols.add(csvEscape(str(item.get("title"))));
            cols.add(csvEscape(str(item.get("taskType"))));
            cols.add(csvEscape(str(item.get("priority"))));
            cols.add(csvEscape(STATUS_CN.getOrDefault(str(item.get("status")), str(item.get("status")))));
            cols.add(str(item.get("progress")));
            cols.add(csvEscape(str(item.get("creatorName"))));
            cols.add(csvEscape(str(item.get("assigneeName"))));
            cols.add(formatTs(str(item.get("dueAt"))));
            cols.add(formatTs(str(item.get("createdAt"))));
            sb.append(String.join(",", cols)).append("\r\n");
        }

        // UTF-8 with BOM：保证 Excel 直接打开不乱码
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        log.info("CSV 导出: rows={}, operator={}", page.getRecords().size(), AuthContext.getUserId());
        return withBom;
    }

    /** 导出文件名：任务导出-YYYYMMDD-HHmm（东八区） */
    public String exportFileName() {
        return "任务导出-" + OffsetDateTime.now(CST).format(EXPORT_FILENAME_TS) + ".csv";
    }

    /** CSV 字段转义：含逗号/引号/换行时整段加引号，内部引号双写（RFC 4180） */
    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** ISO 时间串 → 东八区可读格式 yyyy-MM-dd HH:mm */
    private static String formatTs(String iso) {
        if (!StringUtils.hasText(iso)) {
            return "";
        }
        return OffsetDateTime.parse(iso).withOffsetSameInstant(CST).format(EXPORT_TS);
    }

    // ==================== 导入模板（接口 #41） ====================

    /**
     * 生成导入模板 .xlsx：表头加粗 + 冻结首行 + 一行示例数据。
     *
     * @return xlsx 文件字节
     */
    public byte[] importTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("任务导入");

            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                Cell cell = header.createCell(i, CellType.STRING);
                cell.setCellValue(TEMPLATE_HEADERS.get(i));
                cell.setCellStyle(headerStyle);
            }
            Row example = sheet.createRow(1);
            for (int i = 0; i < TEMPLATE_EXAMPLE.size(); i++) {
                example.createCell(i, CellType.STRING).setCellValue(TEMPLATE_EXAMPLE.get(i));
            }
            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                sheet.setColumnWidth(i, 16 * 256);
            }
            sheet.createFreezePane(0, 1); // 冻结首行

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "导入模板生成失败");
        }
    }

    // ==================== Excel 导入（接口 #42） ====================

    /**
     * Excel 导入：先逐行全量校验（不入库），全部通过后单事务批量创建。
     *
     * @return {batchId, totalRows, successCount}
     */
    @Transactional
    public Map<String, Object> importExcel(MultipartFile file) {
        // ① 文件级校验（1001；此时行数未明或超 import_batch.total_rows 上限，不落批次）
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "导入文件不能为空");
        }
        String fileName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "import.xlsx";
        if (!fileName.toLowerCase().endsWith(".xlsx")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅支持 .xlsx 格式文件");
        }

        // ② 解析 + 逐行全量校验（不落库）
        List<ParsedRow> rows = parseAndValidate(file);

        // ③ 校验失败：批次留痕（新事务）后整批拒绝（2012 + 逐行明细）
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ParsedRow row : rows) {
            for (String reason : row.reasons) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", row.rowNo);
                err.put("reason", reason);
                errors.add(err);
            }
        }
        if (!errors.isEmpty()) {
            String errorJson = toJson(errors);
            batchRecorder.recordFailure(fileName, rows.size(), errors.size(), errorJson);
            log.info("Excel 导入整批拒绝: file={}, rows={}, errors={}, operator={}",
                    fileName, rows.size(), errors.size(), AuthContext.getUserId());
            throw new BizException(ErrorCode.IMPORT_VALIDATION_FAILED, errors);
        }

        // ④ 单事务逐行创建（TaskService.create 为 REQUIRED，加入本事务；任一行失败整体回滚）
        for (ParsedRow row : rows) {
            taskService.create(row.title, row.description, row.taskType, row.priority,
                    row.assigneeId, row.dueAt, null);
        }

        // ⑤ 成功批次落库（同事务，随任务一起提交）
        ImportBatch batch = new ImportBatch();
        batch.setOperatorId(AuthContext.getUserId());
        batch.setFileName(fileName);
        batch.setTotalRows(rows.size());
        batch.setSuccessCount(rows.size());
        batch.setFailCount(0);
        batch.setErrorReport(null);
        importBatchMapper.insert(batch);

        log.info("Excel 导入成功: batchId={}, file={}, rows={}, operator={}",
                batch.getId(), fileName, rows.size(), AuthContext.getUserId());
        return Map.of("batchId", batch.getId(), "totalRows", rows.size(), "successCount", rows.size());
    }

    /** 解析首个工作表并逐行校验；>500 行 → 1001 */
    private List<ParsedRow> parseAndValidate(MultipartFile file) {
        List<ParsedRow> rows = new ArrayList<>();
        // 处理人账号解析缓存（账号 → 用户 id 或错误原因），同一账号只查一次 auth-user-service
        Map<String, AssigneeResolution> assigneeCache = new HashMap<>();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                return rows;
            }
            int lastRow = sheet.getLastRowNum(); // 行 0 为表头
            for (int i = 1; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) {
                    continue; // 整行空白跳过，不计入数据行
                }
                ParsedRow parsed = validateRow(row, i + 1, assigneeCache); // 行号从 2 算（Excel 视角）
                rows.add(parsed);
                if (rows.size() > IMPORT_MAX_ROWS) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "数据行超过 500 行上限（当前 " + (lastRow) + " 行），请拆分文件",
                            List.of(Map.of("field", "file", "reason", "数据行数超过 500 行上限")));
                }
            }
            return rows;
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件解析失败，请使用导入模板（.xlsx）");
        }
    }

    /** 单行解析 + 校验（所有问题一次性收集进 reasons） */
    private ParsedRow validateRow(Row row, int rowNo, Map<String, AssigneeResolution> assigneeCache) {
        ParsedRow parsed = new ParsedRow(rowNo);

        String title = cellString(row, 0);
        String description = cellString(row, 1);
        String taskType = cellString(row, 2);
        String priority = cellString(row, 3);
        String account = cellString(row, 4);
        String dueDateText = cellString(row, 5);
        String dueTimeText = cellString(row, 6);
        String progressText = cellString(row, 7);

        // 标题：必填；上限与 TaskService.create 口径一致（100 字符），保证校验通过即可入库
        if (!StringUtils.hasText(title)) {
            parsed.reject("标题必填");
        } else if (title.length() > 100) {
            parsed.reject("标题不超过 100 字符");
        }
        parsed.title = title;

        if (description != null && description.length() > 2000) {
            parsed.reject("描述不超过 2000 字符");
        }
        parsed.description = StringUtils.hasText(description) ? description : null;

        if (StringUtils.hasText(taskType) && !TASK_TYPES.contains(taskType)) {
            parsed.reject("任务类型非法（可选：项目开发/日常事务/会议事项/调研分析/数据报表/流程审批）");
        }
        parsed.taskType = StringUtils.hasText(taskType) ? taskType : "项目开发";

        if (StringUtils.hasText(priority) && !PRIORITIES.contains(priority)) {
            parsed.reject("优先级非法（可选：P0/P1/P2/P3）");
        }
        parsed.priority = StringUtils.hasText(priority) ? priority : "P2";

        // 处理人账号：必填、存在、在职、非 admin（实时走 UserClient，同账号缓存复用）
        if (!StringUtils.hasText(account)) {
            parsed.reject("处理人账号必填");
        } else {
            AssigneeResolution resolution = assigneeCache.computeIfAbsent(account, this::resolveAssignee);
            if (resolution.userId == null) {
                parsed.reject(resolution.error);
            }
            parsed.assigneeId = resolution.userId;
        }

        // 到期日期：必填且合法（日期单元格或 yyyy-MM-dd 文本均可）
        LocalDate dueDate = cellDate(row, 5);
        if (dueDate == null && StringUtils.hasText(dueDateText)) {
            dueDate = parseDate(dueDateText);
        }
        if (dueDate == null) {
            parsed.reject(StringUtils.hasText(dueDateText) || cellString(row, 5) != null
                    ? "到期日期非法（应为 yyyy-MM-dd）" : "到期日期必填");
        }

        // 到期时间：可空（默认 18:00，HH:mm 文本或时间单元格均可）
        LocalTime dueTime = cellTime(row, 6);
        if (dueTime == null && StringUtils.hasText(dueTimeText)) {
            dueTime = parseTime(dueTimeText);
            if (dueTime == null) {
                parsed.reject("到期时间非法（应为 HH:mm）");
            }
        }
        if (dueTime == null) {
            dueTime = LocalTime.of(18, 0);
        }
        parsed.dueAt = dueDate == null ? null : dueDate.atTime(dueTime).atOffset(CST);

        // 进度：可空；0-100 且 5 的倍数（2009 语义）。注：TaskService.create 固定从 0 起步，
        // 导入行进度仅做合法性校验，不写入任务（见里程碑报告说明）
        if (StringUtils.hasText(progressText)) {
            Integer progress = parseProgress(progressText);
            if (progress == null || progress < 0 || progress > 100 || progress % 5 != 0) {
                parsed.reject("进度值非法（须为 0-100 且步进 5）");
            }
        }
        return parsed;
    }

    /** 处理人账号解析：lookup 精确匹配 account，再 getUser 校验在职/非 admin */
    @SuppressWarnings("unchecked")
    private AssigneeResolution resolveAssignee(String account) {
        try {
            Map<String, Object> envelope = userClient.lookup(account, null);
            List<Map<String, Object>> users = (List<Map<String, Object>>) envelope.get("data");
            Map<String, Object> matched = null;
            if (users != null) {
                for (Map<String, Object> u : users) {
                    if (account.equals(String.valueOf(u.get("account")))) {
                        matched = u;
                        break;
                    }
                }
            }
            if (matched == null) {
                return AssigneeResolution.error("处理人账号不存在：" + account);
            }
            Long userId = Long.valueOf(String.valueOf(matched.get("id")));
            Map<String, Object> detail = (Map<String, Object>) userClient.getUser(userId).get("data");
            if (detail == null || "disabled".equals(detail.get("status"))) {
                return AssigneeResolution.error("处理人已停用：" + account);
            }
            if ("admin".equals(detail.get("roleKey"))) {
                return AssigneeResolution.error("处理人不能是 admin 角色：" + account);
            }
            return AssigneeResolution.ok(userId);
        } catch (Exception e) {
            log.warn("导入处理人账号校验失败: account={}, err={}", account, e.getMessage());
            return AssigneeResolution.error("处理人账号校验失败（用户服务不可用）：" + account);
        }
    }

    // ==================== POI 单元格读取辅助 ====================

    /** 整行空白判定（所有有效单元格均为空） */
    private static boolean isBlankRow(Row row) {
        if (row == null) {
            return true;
        }
        short last = row.getLastCellNum();
        for (int i = 0; i < last; i++) {
            if (StringUtils.hasText(cellString(row, i))) {
                return false;
            }
        }
        return true;
    }

    /** 单元格 → 字符串（文本/数字/布尔/公式缓存值统一转串，去首尾空白；空白返回 null） */
    private static String cellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    // 日期/时间单元格保持原样标记，由 cellDate/cellTime 解析；此处给可读串避免误判非空
                    yield cell.getLocalDateTimeCellValue().format(
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                }
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) && !Double.isInfinite(d)
                        ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 日期单元格 → LocalDate（仅日期格式的数值单元格；文本走 parseDate） */
    private static LocalDate cellDate(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        return null;
    }

    /** 时间单元格 → LocalTime（仅日期格式的数值单元格；文本走 parseTime） */
    private static LocalTime cellTime(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalTime();
        }
        return null;
    }

    /** yyyy-MM-dd 严格解析（非法日期如 2026-02-30 判 null） */
    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text, DATE_STRICT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** HH:mm 严格解析 */
    private static LocalTime parseTime(String text) {
        try {
            return LocalTime.parse(text, TIME_STRICT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 进度解析：整数字符串或日期单元格串（"yyyy-MM-dd HH:mm" 形态不可能是合法进度，直接 null） */
    private static Integer parseProgress(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("错误报告序列化失败", e);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 一行导入数据的解析结果（校验通过后字段即入库参数） */
    private static final class ParsedRow {
        final int rowNo;
        final List<String> reasons = new ArrayList<>();
        String title;
        String description;
        String taskType;
        String priority;
        Long assigneeId;
        OffsetDateTime dueAt;

        ParsedRow(int rowNo) {
            this.rowNo = rowNo;
        }

        void reject(String reason) {
            reasons.add(reason);
        }
    }

    /** 处理人账号解析结果：成功携带 userId，失败携带原因 */
    private record AssigneeResolution(Long userId, String error) {
        static AssigneeResolution ok(Long userId) {
            return new AssigneeResolution(userId, null);
        }

        static AssigneeResolution error(String error) {
            return new AssigneeResolution(null, error);
        }
    }
}
