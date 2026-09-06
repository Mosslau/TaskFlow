package com.taskflow.auth.controller;

import com.taskflow.auth.config.AuthContext;
import com.taskflow.auth.config.RequirePerm;
import com.taskflow.auth.service.PermissionService;
import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 权限矩阵接口（接口 #16-17，PRD 4.5.4）。
 * 读：setPerm 权限；写：setPerm 权限 + admin 锁定项保护 + 审计日志。
 */
@RestController
@RequestMapping("/auth/api/v1/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * 读取完整权限矩阵（接口 #16）。
     *
     * @return [{ roleKey, permissions: {权限点: 开关} }]
     */
    @GetMapping("/matrix")
    @RequirePerm("setPerm")
    public Result<List<Map<String, Object>>> getMatrix() {
        return Result.ok(permissionService.getMatrix());
    }

    /**
     * 修改矩阵单格（接口 #17，即改即存）。
     *
     * @param body {roleKey, permissionKey, enabled}
     * @return 更新后的单元格
     */
    @PutMapping("/matrix")
    @RequirePerm("setPerm")
    public Result<Map<String, Object>> updateMatrix(@RequestBody Map<String, Object> body) {
        String roleKey = (String) body.get("roleKey");
        String permissionKey = (String) body.get("permissionKey");
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        permissionService.updateMatrixCell(AuthContext.getUserId(), roleKey, permissionKey, enabled);
        return Result.ok(Map.of("roleKey", roleKey, "permissionKey", permissionKey, "enabled", enabled));
    }
}
