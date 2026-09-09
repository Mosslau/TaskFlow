package com.taskflow.task.service;

import com.taskflow.task.config.AuthContext;
import com.taskflow.task.entity.ImportBatch;
import com.taskflow.task.mapper.ImportBatchMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 导入批次失败记录器（独立新事务）。
 *
 * <p>导入采用"先全量校验、后单事务入库"：校验失败时外层事务必须回滚（不入库任何任务），
 * 但失败批次本身仍要留痕（PRD 4.7：成功/失败都记录 import_batch），
 * 因此失败批次记录走 REQUIRES_NEW，与外层回滚互不影响。</p>
 */
@Service
public class ImportBatchRecorder {

    private final ImportBatchMapper importBatchMapper;

    public ImportBatchRecorder(ImportBatchMapper importBatchMapper) {
        this.importBatchMapper = importBatchMapper;
    }

    /**
     * 失败批次落库（新事务，外层随后回滚也不影响本记录）。
     *
     * @param fileName    导入文件名
     * @param totalRows   数据行数
     * @param failCount   失败行数
     * @param errorReport 逐行错误 JSON：[{"row":2,"reason":"..."}]
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String fileName, int totalRows, int failCount, String errorReport) {
        ImportBatch batch = new ImportBatch();
        batch.setOperatorId(AuthContext.getUserId());
        batch.setFileName(fileName);
        batch.setTotalRows(totalRows);
        batch.setSuccessCount(0);
        batch.setFailCount(failCount);
        batch.setErrorReport(errorReport);
        importBatchMapper.insert(batch);
    }
}
