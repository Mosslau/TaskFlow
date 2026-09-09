package com.taskflow.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.notification.entity.Notification;
import com.taskflow.notification.mapper.NotificationMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
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

    /** 系统运维告警事件类型（SMTP 失败等；非业务事件，默认不在业务通知列表展示） */
    public static final String SYS_ALERT_TYPE = "mail.failed";

    /**
     * 分页查询本人消息（接口 #43）：时间倒序，可按已读筛选。
     *
     * @param view 列表口径：business=仅业务通知（默认，排除 mail.failed 系统告警）；
     *             system=仅系统告警；all=全部
     */
    public Page<Map<String, Object>> page(Long recipientId, Boolean isRead, int page, int size, String view) {
        LambdaQueryWrapper<Notification> qw = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(isRead != null, Notification::getIsRead, isRead);
        if ("system".equals(view)) {
            qw.eq(Notification::getEventType, SYS_ALERT_TYPE);
        } else if (!"all".equals(view)) {
            // 默认 business：业务通知，排除系统运维告警
            qw.ne(Notification::getEventType, SYS_ALERT_TYPE);
        }
        qw.orderByDesc(Notification::getCreatedAt);
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
     * 未读数（接口 #46，前端 60 秒轮询）。只统计业务通知，排除系统运维告警（mail.failed），
     * 保证铃铛角标只反映"有实际业务要看"的未读。
     */
    public long unreadCount(Long me) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, me)
                .eq(Notification::getIsRead, false)
                .ne(Notification::getEventType, SYS_ALERT_TYPE));
    }

    /**
     * 某接收人在近 N 分钟内是否已有指定事件类型的通知（告警去重用）。
     *
     * @param recipientId 接收人
     * @param eventType   事件类型（如 mail.failed）
     * @param minutes     回溯窗口分钟
     */
    public boolean hasRecent(Long recipientId, String eventType, int minutes) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getEventType, eventType)
                .ge(Notification::getCreatedAt, OffsetDateTime.now().minusMinutes(minutes))) > 0;
    }
}
