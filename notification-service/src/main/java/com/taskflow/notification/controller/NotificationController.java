package com.taskflow.notification.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.common.Result;
import com.taskflow.notification.config.AuthContext;
import com.taskflow.notification.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站内消息接口（接口文档 5.1，#43-46；登录即可）。
 */
@RestController
@RequestMapping("/notification/api/v1")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 消息列表（接口 #43）：时间倒序，可按已读筛选。
     */
    @GetMapping("/notifications")
    public Result<Map<String, Object>> list(@RequestParam(required = false) Boolean isRead,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        Page<Map<String, Object>> p = notificationService.page(AuthContext.getUserId(), isRead, page, size);
        return Result.ok(Map.of(
                "list", p.getRecords(),
                "total", p.getTotal(),
                "page", p.getCurrent(),
                "size", p.getSize()));
    }

    /**
     * 标记已读（接口 #44）：仅本人消息，越权 4001。
     */
    @PutMapping("/notifications/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(AuthContext.getUserId(), id);
        return Result.ok();
    }

    /**
     * 全部已读（接口 #45）。
     */
    @PutMapping("/notifications/read-all")
    public Result<Map<String, Object>> readAll() {
        return Result.ok(Map.of("updatedCount", notificationService.readAll(AuthContext.getUserId())));
    }

    /**
     * 未读数（接口 #46）：前端 60 秒轮询。
     */
    @GetMapping("/notifications/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        return Result.ok(Map.of("count", notificationService.unreadCount(AuthContext.getUserId())));
    }
}
