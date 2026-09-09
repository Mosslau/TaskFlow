package com.taskflow.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.task.config.StringJsonbTypeHandler;

import java.time.OffsetDateTime;

/**
 * Excel 导入批次记录 import_batch（PRD 4.7；保留 1 年）。
 * 整批校验：任一行失败则全批不入库，success/fail 计数与逐行错误报告落本表。
 */
@TableName(value = "import_batch", autoResultMap = true)
public class ImportBatch {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 导入操作人（逻辑引用 app_user.id；导入任务的创建人） */
    private Long operatorId;

    /** 导入文件名 */
    private String fileName;

    /** 文件数据行数（单次上限 500） */
    private Integer totalRows;

    /** 成功行数 */
    private Integer successCount;

    /** 失败行数 */
    private Integer failCount;

    /** 逐行错误报告 JSON：[{"row": 3, "reason": "处理人账号不存在"}]（JSONB） */
    @TableField(typeHandler = StringJsonbTypeHandler.class)
    private String errorReport;

    /** 导入时间（UTC，DB 默认值生成） */
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }
    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }
    public Integer getFailCount() { return failCount; }
    public void setFailCount(Integer failCount) { this.failCount = failCount; }
    public String getErrorReport() { return errorReport; }
    public void setErrorReport(String errorReport) { this.errorReport = errorReport; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
