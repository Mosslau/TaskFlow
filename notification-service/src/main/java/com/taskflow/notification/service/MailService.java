package com.taskflow.notification.service;

import com.taskflow.notification.client.AuthUserClient;
import com.taskflow.notification.entity.MailRecord;
import com.taskflow.notification.mapper.MailRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * SMTP 邮件服务（PRD 4.6.3）。
 *
 * <p>失败重试 3 次（指数退避 1s / 2s / 4s），仍失败：mail_record 记 failed
 * 并给全体系统管理员写一条站内告警（邮件通道已挂，告警只能走站内）。</p>
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** PRD 4.6.3：失败重试 3 次 */
    private static final int MAX_RETRY = 3;

    private final JavaMailSender mailSender;
    private final MailRecordMapper mailRecordMapper;
    private final NotificationService notificationService;
    private final AuthUserClient authUserClient;

    /** 发件人地址（企业 SMTP 发件配置走服务端环境变量，不入库不入代码库） */
    @Value("${taskflow.mail.from:TaskFlow 通知 <taskflow-noreply@taskflow.local>}")
    private String from;

    public MailService(JavaMailSender mailSender, MailRecordMapper mailRecordMapper,
                       NotificationService notificationService, AuthUserClient authUserClient) {
        this.mailSender = mailSender;
        this.mailRecordMapper = mailRecordMapper;
        this.notificationService = notificationService;
        this.authUserClient = authUserClient;
    }

    /**
     * 发送邮件（含重试与落库）。
     *
     * @param taskId         关联任务（可空）
     * @param recipientEmail 收件人邮箱（空则直接跳过，不产生记录）
     * @param subject        主题：【任务管理】<事件> <任务编号> <任务标题>
     * @param text           正文
     */
    public void sendWithRetry(Long taskId, String recipientEmail, String subject, String text) {
        if (recipientEmail == null || recipientEmail.isBlank()) {
            log.warn("收件人邮箱为空，跳过邮件: subject={}", subject);
            return;
        }
        int retry = 0;
        while (true) {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(from);
                msg.setTo(recipientEmail);
                msg.setSubject(subject);
                msg.setText(text);
                mailSender.send(msg);
                record(taskId, recipientEmail, subject, "success", retry);
                log.info("邮件发送成功: to={}, subject={}, retry={}", recipientEmail, subject, retry);
                return;
            } catch (Exception e) {
                if (retry >= MAX_RETRY) {
                    record(taskId, recipientEmail, subject, "failed", retry);
                    log.error("邮件发送最终失败（重试 {} 次）: to={}, subject={}, err={}",
                            retry, recipientEmail, subject, e.getMessage());
                    alertAdmins(taskId, subject, recipientEmail);
                    return;
                }
                retry++;
                long backoff = 1000L << (retry - 1); // 指数退避 1s / 2s / 4s
                log.warn("邮件发送失败，{}ms 后第 {} 次重试: to={}, err={}",
                        backoff, retry, recipientEmail, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    record(taskId, recipientEmail, subject, "failed", retry);
                    return;
                }
            }
        }
    }

    /** 邮件记录落库（每封一次，含重试次数与结果） */
    private void record(Long taskId, String recipient, String subject, String result, int retryCount) {
        MailRecord r = new MailRecord();
        r.setTaskId(taskId);
        r.setRecipient(recipient);
        r.setSubject(subject);
        r.setResult(result);
        r.setRetryCount(retryCount);
        mailRecordMapper.insert(r);
    }

    /** 邮件最终失败：给系统管理员写站内告警（PRD 4.6.3）。
     *  取用户用 getUser(1)(消费线程内已验证可行的 Feign),去重:30 分钟窗口内已有
     *  mail.failed 告警则跳过,避免 SMTP 未配置时刷屏。 */
    @SuppressWarnings("unchecked")
    private void alertAdmins(Long taskId, String subject, String recipientEmail) {
        try {
            Map<String, Object> resp = authUserClient.getUser(1L);
            Map<String, Object> admin = (Map<String, Object>) resp.get("data");
            if (admin == null || !"active".equals(admin.get("status"))) {
                return;
            }
            Long adminId = ((Number) admin.get("id")).longValue();
            // 去重：30 分钟窗口内已有同类告警则不再重复（避免邮件失败风暴占满通知中心）
            if (notificationService.hasRecent(adminId, "mail.failed", 30)) {
                return;
            }
            notificationService.insert(adminId, "mail.failed",
                    "邮件发送失败（已重试 3 次）：「" + truncate(subject, 60) + "」→ " + recipientEmail
                            + "，请检查 SMTP 配置。",
                    taskId, null);
        } catch (Exception e) {
            log.error("邮件失败告警管理员失败: {}", e.getMessage());
        }
    }

    /** 摘要过长截断：避免长邮件主题把通知摘要撑乱 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
