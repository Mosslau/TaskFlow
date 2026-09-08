package com.taskflow.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.notification.entity.Notification;
import com.taskflow.notification.mapper.NotificationMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 站内消息服务（PRD 4.6.2）。
 */
@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * 写一条站内消息（只增不改）。
     *
     * @param recipientId 接收人
     * @param eventType   事件类型（task.assigned 等）
     * @param summary     摘要
     * @param taskId      关联任务（可空）
     * @param taskNo      任务编号（可空）
     */
    public void insert(Long recipientId, String eventType, String summary, Long taskId, String taskNo) {
        Notification n = new Notification();
        n.setRecipientId(recipientId);
        n.setEventType(eventType);
        n.setSummary(summary);
        n.setTaskId(taskId);
        n.setTaskNo(taskNo);
        n.setIsRead(false);
        notificationMapper.insert(n);
    }

    /**
     * 分页查询本人消息（接口 #43）：时间倒序，可按已读筛选。
     */
    public Page<Map<String, Object>> page(Long recipientId, Boolean isRead, int page, int size) {
        LambdaQueryWrapper<Notification> qw = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(isRead != null, Notification::getIsRead, isRead)
                .orderByDesc(Notification::getCreatedAt);
        Page<Notification> raw = notificationMapper.selectPage(new Page<>(page, size), qw);

        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(n -> Map.<String, Object>of(
                "id", n.getId(),
                "eventType", n.getEventType(),
                "summary", n.getSummary(),
                "taskId", n.getTaskId() == null ? "" : n.getTaskId(),
                "taskNo", n.getTaskNo() == null ? "" : n.getTaskNo(),
                "isRead", n.getIsRead(),
                "createdAt", n.getCreatedAt().toString())).collect(Collectors.toList()));
        return result;
    }

    /**
     * 标记已读（接口 #44）：仅本人消息，越权 4001。
     */
    public void markRead(Long me, Long id) {
        Notification n = notificationMapper.selectById(id);
        if (n == null || !n.getRecipientId().equals(me)) {
            throw new BizException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(n.getIsRead())) {
            return; // 幂等：重复标记不报错
        }
        n.setIsRead(true);
        notificationMapper.updateById(n);
    }

    /**
     * 全部已读（接口 #45）。
     *
     * @return 实际更新行数
     */
    public int readAll(Long me) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getRecipientId, me)
                .eq(Notification::getIsRead, false)
                .set(Notification::getIsRead, true));
    }

    /**
     * 未读数（接口 #46，前端 60 秒轮询）。
     */
    public long unreadCount(Long me) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, me)
                .eq(Notification::getIsRead, false));
    }
}
