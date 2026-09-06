package com.taskflow.auth.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.auth.config.AuthContext;
import com.taskflow.auth.config.RequirePerm;
import com.taskflow.auth.service.AuditService;
import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审计日志查询接口（接口 #18，仅 admin 可见，PRD 4.5.4）。
 * admin 拥有全部权限点，用 manageUser 权限点保护本接口。
 */
@RestController
@RequestMapping("/auth/api/v1/audit-logs")
public class AuditLogController {

    private final AuditService auditService;

    public AuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 审计日志分页查询（时间倒序，可按操作人筛选）。
     */
    @GetMapping
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> list(@RequestParam(required = false) Long operatorId,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        Page<Map<String, Object>> p = auditService.page(operatorId, page, size);
        return Result.ok(Map.of(
                "list", p.getRecords(),
                "total", p.getTotal(),
                "page", p.getCurrent(),
                "size", p.getSize()));
    }

    /**
     * 内部审计写入（登录即可，供其他服务经 Feign 调用——如 task-service 删除任务留痕，
     * PRD 4.1.2：删除动作写入审计日志）。审计日志的唯一写者仍是本服务（铁律 1）。
     *
     * @param body {action, changeDetail}
     */
    @PostMapping
    public Result<Void> write(@RequestBody Map<String, String> body) {
        auditService.record(AuthContext.getUserId(), body.get("action"), body.get("changeDetail"));
        return Result.ok();
    }
}
