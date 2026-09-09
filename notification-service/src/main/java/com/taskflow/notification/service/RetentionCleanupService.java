package com.taskflow.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.notification.entity.MailRecord;
import com.taskflow.notification.entity.Notification;
import com.taskflow.notification.mapper.MailRecordMapper;
import com.taskflow.notification.mapper.NotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 数据保留清理（M5 5.5）：站内消息保留 180 天、邮件记录保留 1 年。
 *
 * <p>单实例部署直接跑；多实例时需加分布式锁（参考 task-service 定时任务）。</p>
 */
@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final NotificationMapper notificationMapper;
    private final MailRecordMapper mailRecordMapper;

    public RetentionCleanupService(NotificationMapper notificationMapper,
                                   MailRecordMapper mailRecordMapper) {
        this.notificationMapper = notificationMapper;
        this.mailRecordMapper = mailRecordMapper;
    }

    /** 每日 04:30（东八区）清理过期数据 */
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Shanghai")
    public void cleanup() {
        OffsetDateTime notificationDeadline = OffsetDateTime.now().minusDays(180);
        int notifications = notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .lt(Notification::getCreatedAt, notificationDeadline));

        OffsetDateTime mailDeadline = OffsetDateTime.now().minusYears(1);
        int mails = mailRecordMapper.delete(new LambdaQueryWrapper<MailRecord>()
                .lt(MailRecord::getCreatedAt, mailDeadline));

        if (notifications > 0 || mails > 0) {
            log.info("过期数据清理: notification={} 条（>180天）, mail_record={} 条（>1年）", notifications, mails);
        }
    }
}
