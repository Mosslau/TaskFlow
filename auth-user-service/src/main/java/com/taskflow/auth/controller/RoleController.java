package com.taskflow.auth.controller;

import com.taskflow.auth.service.UserService;
import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 角色与权限点目录（接口 #15，登录即可查——供矩阵页与用户表单渲染）。
 */
@RestController
@RequestMapping("/auth/api/v1")
public class RoleController {

    private final UserService userService;

    public RoleController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 角色与权限点目录。
     *
     * @return { roles: [{id, roleKey, name}], permissionKeys: [{key, name, group}] }
     */
    @GetMapping("/roles")
    public Result<Map<String, Object>> roles() {
        return Result.ok(userService.roleCatalog());
    }
}
