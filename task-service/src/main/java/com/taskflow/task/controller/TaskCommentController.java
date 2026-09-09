package com.taskflow.task.controller;

import com.taskflow.common.Result;
import com.taskflow.task.service.TaskCommentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任务评论接口（接口 #33-35，PRD 4.1.5）。
 */
@RestController
public class TaskCommentController {

    private final TaskCommentService commentService;

    public TaskCommentController(TaskCommentService commentService) {
        this.commentService = commentService;
    }

    /** 评论列表（接口 #33）：正序，含评论人姓名 */
    @GetMapping("/task/api/v1/tasks/{id}/comments")
    public Result<List<Map<String, Object>>> list(@PathVariable Long id) {
        return Result.ok(commentService.list(id));
    }

    /** 发表评论（接口 #34）：{content: 1-500 字符} */
    @PostMapping("/task/api/v1/tasks/{id}/comments")
    public Result<Map<String, Object>> add(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(commentService.add(id, body.get("content")));
    }

    /** 删除评论（接口 #35）：仅评论人本人或 admin */
    @DeleteMapping("/task/api/v1/comments/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        commentService.delete(id);
        return Result.ok();
    }
}
