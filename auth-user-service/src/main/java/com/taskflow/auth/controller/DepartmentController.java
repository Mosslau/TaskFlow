package com.taskflow.auth.controller;

import com.taskflow.auth.config.RequirePerm;
import com.taskflow.auth.service.UserService;
import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 部门管理接口（接口文档 3.3，接口 #11-14）。
 * 查询登录即可（供表单下拉）；写操作需要 manageUser。
 */
@RestController
@RequestMapping("/auth/api/v1/departments")
public class DepartmentController {

    private final UserService userService;

    public DepartmentController(UserService userService) {
        this.userService = userService;
    }

    /** 部门列表（接口 #11，含在职人数；登录即可，供表单下拉） */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(userService.listDepartments());
    }

    /** 新增部门（接口 #12，名称唯一） */
    @PostMapping
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        return Result.ok(userService.createDepartment(body.get("name")));
    }

    /** 编辑部门名称（接口 #13） */
    @PutMapping("/{id}")
    @RequirePerm("manageUser")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @RequestBody Map<String, String> body) {
        userService.updateDepartment(id, body.get("name"));
        return Result.ok(Map.of("id", id, "name", body.get("name")));
    }

    /** 删除部门（接口 #14；存在在职用户返回 3008） */
    @DeleteMapping("/{id}")
    @RequirePerm("manageUser")
    public Result<Void> delete(@PathVariable Long id) {
        userService.deleteDepartment(id);
        return Result.ok();
    }
}
