package com.taskflow.task.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.common.Result;
import com.taskflow.task.config.RequirePerm;
import com.taskflow.task.entity.Task;
import com.taskflow.task.service.TaskService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 任务接口（接口文档 4.1-4.2，接口 #19-32 的 M2 部分）。
 */
@RestController
@RequestMapping("/task/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 任务列表（接口 #19）：筛选 + 分页 + 可见性过滤。
     */
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Long assigneeDeptId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "false") boolean topLevel,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Map<String, Object>> p = taskService.page(keyword, status, priority, taskType,
                creatorId, assigneeId, assigneeDeptId, parentId, topLevel, scope, page, size);
        return Result.ok(Map.of(
                "list", p.getRecords(),
                "total", p.getTotal(),
                "page", p.getCurrent(),
                "size", p.getSize()));
    }

    /**
     * 创建任务（接口 #20；body 含 parentId 即创建子任务）。
     */
    @PostMapping
    @RequirePerm("create")
    public Result<Task> create(@RequestBody Map<String, Object> body) {
        Task task = taskService.create(
                (String) body.get("title"),
                (String) body.get("description"),
                (String) body.get("taskType"),
                (String) body.get("priority"),
                Long.valueOf(String.valueOf(body.get("assigneeId"))),
                body.get("dueAt") == null ? null : OffsetDateTime.parse((String) body.get("dueAt")),
                body.get("parentId") == null ? null : Long.valueOf(String.valueOf(body.get("parentId"))));
        return Result.ok(task);
    }

    /** 任务详情（接口 #21）：任务 + 时间线 + 子任务清单 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(taskService.detail(id));
    }

    /** 编辑任务（接口 #22，editOwn） */
    @PutMapping("/{id}")
    public Result<Task> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(taskService.update(id, body));
    }

    /** 删除任务（接口 #23，deleteOwn，仅待办） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return Result.ok();
    }

    // ==================== 状态机动作（接口 #24-32） ====================

    /** 受理（待办 → 进行中） */
    @PostMapping("/{id}/accept")
    public Result<Task> accept(@PathVariable Long id) {
        return Result.ok(taskService.accept(id));
    }

    /** 更新进度 {progress, note} */
    @PostMapping("/{id}/progress")
    public Result<Task> progress(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(taskService.updateProgress(id,
                Integer.parseInt(String.valueOf(body.get("progress"))),
                (String) body.get("note")));
    }

    /** 提交验收（进行中 → 待验收；有未完成子任务返回 2004） */
    @PostMapping("/{id}/submit-acceptance")
    public Result<Task> submitAcceptance(@PathVariable Long id) {
        return Result.ok(taskService.submitAcceptance(id));
    }

    /** 验收通过（待验收 → 已完成，创建人/admin） */
    @PostMapping("/{id}/approve")
    public Result<Task> approve(@PathVariable Long id) {
        return Result.ok(taskService.approve(id));
    }

    /** 验收驳回（待验收 → 进行中，驳回原因必填 ≤500） */
    @PostMapping("/{id}/reject")
    public Result<Task> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(taskService.reject(id, body.get("reason")));
    }

    /** 转派 {newAssigneeId, note}（转派说明必填 ≤200） */
    @PostMapping("/{id}/transfer")
    public Result<Task> transfer(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(taskService.transfer(id,
                Long.valueOf(String.valueOf(body.get("newAssigneeId"))),
                (String) body.get("note")));
    }

    /** 调整优先级 {priority}（prioOwn） */
    @PatchMapping("/{id}/priority")
    public Result<Task> changePriority(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(taskService.changePriority(id, body.get("priority")));
    }

    /** 调整到期时间 {dueAt}（dueOwn） */
    @PatchMapping("/{id}/due")
    public Result<Task> changeDue(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(taskService.changeDue(id, OffsetDateTime.parse(body.get("dueAt"))));
    }

    /** 手动归档（已完成 → 已归档，viewAll） */
    @PostMapping("/{id}/archive")
    @RequirePerm("viewAll")
    public Result<Task> archive(@PathVariable Long id) {
        return Result.ok(taskService.archive(id));
    }
}
