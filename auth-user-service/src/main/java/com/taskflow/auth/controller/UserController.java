package com.taskflow.auth.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.auth.config.RequirePerm;
import com.taskflow.auth.service.UserService;
import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户管理接口（接口文档 3.2，接口 #5-10）。
 * 全部需要 manageUser 权限点（PRD 4.5.1）。
 */
@RestController
@RequestMapping("/auth/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户列表（接口 #5）：关键字/部门/角色/状态筛选 + 分页。
     */
    @GetMapping
    // @RequirePerm：拦截器校验当前角色拥有 manageUser，无则 403（3001）
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Long departmentId,
                                            @RequestParam(required = false) Long roleId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        Page<Map<String, Object>> p = userService.pageUsers(keyword, departmentId, roleId, status, page, size);
        return Result.ok(Map.of(
                "list", p.getRecords(),
                "total", p.getTotal(),
                "page", p.getCurrent(),
                "size", p.getSize()));
    }

    /**
     * 用户详情（登录即可；供前端展示与 task-service Feign 校验处理人合法性）。
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(userService.getUserDetail(id));
    }

    /**
     * 新增用户（接口 #6）：返回初始密码（仅此一次），首次登录强制改密。
     */
    @PostMapping
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return Result.ok(userService.createUser(
                (String) body.get("name"),
                (String) body.get("account"),
                Long.valueOf(String.valueOf(body.get("departmentId"))),
                Long.valueOf(String.valueOf(body.get("roleId"))),
                (String) body.get("email")));
    }

    /**
     * 编辑用户（接口 #7）：仅部门与邮箱。
     */
    @PutMapping("/{id}")
    @RequirePerm("manageUser")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        userService.updateUser(id,
                Long.valueOf(String.valueOf(body.get("departmentId"))),
                (String) body.get("email"));
        return Result.ok();
    }

    /**
     * 停用 / 启用（接口 #8）。
     */
    @PutMapping("/{id}/status")
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> changeStatus(@PathVariable Long id,
                                                    @RequestBody Map<String, String> body) {
        userService.changeStatus(id, body.get("status"));
        return Result.ok(Map.of("id", id, "status", body.get("status")));
    }

    /**
     * 角色指派（接口 #9）：即时生效。
     */
    @PutMapping("/{id}/role")
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> assignRole(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> body) {
        Long roleId = Long.valueOf(String.valueOf(body.get("roleId")));
        userService.assignRole(id, roleId);
        return Result.ok(Map.of("id", id, "roleId", roleId));
    }

    /**
     * 重置密码（接口 #10）：返回随机新密码（仅此一次）。
     */
    @PutMapping("/{id}/password-reset")
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> resetPassword(@PathVariable Long id) {
        return Result.ok(userService.resetPassword(id));
    }
}
