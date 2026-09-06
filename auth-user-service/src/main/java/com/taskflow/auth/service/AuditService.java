package com.taskflow.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.auth.entity.AppUser;
import com.taskflow.auth.entity.AuditLog;
import com.taskflow.auth.mapper.AppUserMapper;
import com.taskflow.auth.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 审计日志服务（只增不改，PRD 4.5.4 / 7.5）。
 */
@Service
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final AppUserMapper userMapper;

    public AuditService(AuditLogMapper auditLogMapper, AppUserMapper userMapper) {
        this.auditLogMapper = auditLogMapper;
        this.userMapper = userMapper;
    }

    /**
     * 写一条审计日志。
     *
     * @param operatorId   操作人 id
     * @param action       操作类型（如 permission.matrix.update）
     * @param changeDetail 变更内容 JSON 字符串
     */
    public void record(Long operatorId, String action, String changeDetail) {
        AuditLog log = new AuditLog();
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setChangeDetail(changeDetail);
        auditLogMapper.insert(log);
    }

    /**
     * 分页查询（接口 #18，仅 admin）：按时间倒序，支持按操作人筛选（PRD 4.5.4）。
     *
     * @param operatorId 操作人筛选，可空
     * @param page       页码
     * @param size       每页条数
     * @return 分页结果（list 携操作人姓名）
     */
    public Page<Map<String, Object>> page(Long operatorId, int page, int size) {
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<AuditLog>()
                .eq(operatorId != null, AuditLog::getOperatorId, operatorId)
                .orderByDesc(AuditLog::getCreatedAt);
        Page<AuditLog> raw = auditLogMapper.selectPage(new Page<>(page, size), qw);

        // 操作人姓名 join 展示
        Map<Long, String> userNames = userMapper.selectList(null).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getName));

        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(l -> Map.<String, Object>of(
                "id", l.getId(),
                "operatorId", l.getOperatorId(),
                "operatorName", userNames.getOrDefault(l.getOperatorId(), ""),
                "action", l.getAction(),
                "changeDetail", l.getChangeDetail(),
                "createdAt", l.getCreatedAt().toString())).collect(Collectors.toList()));
        return result;
    }
}

