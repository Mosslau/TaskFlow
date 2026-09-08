package com.taskflow.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.event.TaskEvents;
import com.taskflow.notification.client.AuthUserClient;
import com.taskflow.notification.config.RabbitConfig;
import com.taskflow.notification.mapper.ProcessedEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 任务域事件消费者（架构 3.3 / PRD 4.6.1）。
 *
 * <p>消费 task.events 交换机的 8 类事件：先按 eventId 幂等去重（processed_event），
 * 再生成站内消息 + 发邮件（task.commented 仅站内）。</p>
 *
 * <p>失败策略（开发态取舍）：消息体解析失败或分发异常记日志后吞掉
 * （不再重投，避免毒消息死循环）；投递可靠性靠生产端 outbox 重投，
 * 消费端失败告警可后续接死信队列完善。</p>
 */
@Component
public class NotificationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);

    /** 消费幂等标识（processed_event.consumer） */
    private static final String CONSUMER = "notification";

    /** 事件类型 → 中文事件名（邮件主题用，PRD 4.6.3） */
    private static final Map<String, String> EVENT_NAMES = Map.of(
            TaskEvents.TASK_ASSIGNED, "任务指派",
            TaskEvents.TASK_TRANSFERRED, "任务转派",
            TaskEvents.TASK_COMMENTED, "新评论",
            TaskEvents.TASK_ACCEPTANCE_SUBMITTED, "提交验收",
            TaskEvents.TASK_APPROVED, "验收通过",
            TaskEvents.TASK_REJECTED, "验收驳回",
            TaskEvents.TASK_DUE_SOON, "到期提醒",
            TaskEvents.TASK_OVERDUE, "逾期提醒");

    private final ProcessedEventMapper processedEventMapper;
    private final NotificationService notificationService;
    private final MailService mailService;
    private final AuthUserClient authUserClient;
    private final ObjectMapper objectMapper;

    public NotificationEventConsumer(ProcessedEventMapper processedEventMapper,
                                     NotificationService notificationService,
                                     MailService mailService,
                                     AuthUserClient authUserClient,
                                     ObjectMapper objectMapper) {
        this.processedEventMapper = processedEventMapper;
        this.notificationService = notificationService;
        this.mailService = mailService;
        this.authUserClient = authUserClient;
        this.objectMapper = objectMapper;
    }

    /** 接收人 + 摘要的处理计划 */
    private record Plan(List<Long> recipients, String summary) {
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onEvent(String body) {
        TaskEvents.TaskEvent event;
        try {
            event = objectMapper.readValue(body, TaskEvents.TaskEvent.class);
        } catch (Exception e) {
            log.error("事件反序列化失败，丢弃: {}", body, e);
            return;
        }
        // 幂等去重：生产者 at-least-once，重复投递直接跳过（架构文档第 5 章）
        if (processedEventMapper.insertIgnore(event.eventId().toString(), CONSUMER) == 0) {
            log.debug("重复事件已跳过: {}", event.eventId());
            return;
        }
        log.info("消费事件: type={}, id={}", event.eventType(), event.eventId());
        try {
            dispatch(event);
        } catch (Exception e) {
            log.error("事件处理失败: type={}, id={}", event.eventType(), event.eventId(), e);
        }
    }

    /** 按事件类型分发：写站内消息 + 发邮件（task.commented 仅站内） */
    private void dispatch(TaskEvents.TaskEvent event) {
        Map<String, Object> p = event.payload() == null ? Map.of() : event.payload();
        Long taskId = toLong(p.get("taskId"));
        String taskNo = str(p.get("taskNo"));
        String title = str(p.get("title"));
        // 单个事件内的用户信息缓存（Feign 调 auth-user-service）
        Map<Long, Map<String, Object>> userCache = new HashMap<>();

        Plan plan = switch (event.eventType()) {
            case TaskEvents.TASK_ASSIGNED -> new Plan(
                    List.of(toLong(p.get("assigneeId"))),
                    "「" + nameOf(userCache, toLong(p.get("creatorId"))) + "」给你指派了新任务 "
                            + taskNo + "《" + title + "》");
            case TaskEvents.TASK_TRANSFERRED -> new Plan(
                    List.of(toLong(p.get("newAssigneeId"))),
                    "「" + nameOf(userCache, toLong(p.get("operatorId"))) + "」把任务 " + taskNo + " 转派给你");
            case TaskEvents.TASK_ACCEPTANCE_SUBMITTED -> new Plan(
                    List.of(toLong(p.get("creatorId"))),
                    "「" + nameOf(userCache, toLong(p.get("assigneeId"))) + "」提交了任务 " + taskNo + " 的验收申请");
            case TaskEvents.TASK_APPROVED -> new Plan(
                    List.of(toLong(p.get("assigneeId"))),
                    "你处理的任务 " + taskNo + " 验收已通过");
            case TaskEvents.TASK_REJECTED -> new Plan(
                    List.of(toLong(p.get("assigneeId"))),
                    "你处理的任务 " + taskNo + " 验收被驳回：" + str(p.get("reason")));
            case TaskEvents.TASK_COMMENTED -> {
                Long commenter = toLong(p.get("commenterId"));
                List<Long> recipients = Stream.of(toLong(p.get("creatorId")), toLong(p.get("assigneeId")))
                        .filter(Objects::nonNull)
                        .filter(id -> !id.equals(commenter))
                        .distinct()
                        .toList();
                yield new Plan(recipients,
                        "「" + nameOf(userCache, commenter) + "」评论了任务 " + taskNo + "《" + title + "》");
            }
            case TaskEvents.TASK_DUE_SOON -> new Plan(
                    List.of(toLong(p.get("assigneeId"))),
                    "任务 " + taskNo + " 将于 24 小时内到期，请尽快处理");
            case TaskEvents.TASK_OVERDUE -> new Plan(
                    Stream.of(toLong(p.get("assigneeId")), toLong(p.get("creatorId")))
                            .filter(Objects::nonNull).distinct().toList(),
                    "任务 " + taskNo + " 已逾期，请尽快处理");
            default -> null;
        };
        if (plan == null) {
            log.warn("未知事件类型，仅登记幂等: {}", event.eventType());
            return;
        }

        // PRD 4.6.1：事件 8（新评论）仅站内，其余站内 + 邮件
        boolean sendMail = !TaskEvents.TASK_COMMENTED.equals(event.eventType());
        String subject = ("【任务管理】" + EVENT_NAMES.get(event.eventType()) + " " + taskNo
                + (title.isEmpty() ? "" : " " + title)).trim();
        for (Long recipient : plan.recipients()) {
            if (recipient == null) {
                continue;
            }
            notificationService.insert(recipient, event.eventType(), plan.summary(),
                    taskId, taskNo.isEmpty() ? null : taskNo);
            if (sendMail) {
                String body = plan.summary() + "\n\n请登录任务管理系统查看详情：http://localhost:5173";
                mailService.sendWithRetry(taskId, emailOf(userCache, recipient), subject, body);
            }
        }
    }

    // ---------- 工具 ----------

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = v.toString();
        return s.isBlank() ? null : Long.valueOf(s);
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** 取用户详情（事件内缓存；失败返回 null，降级为「用户#id」/跳过邮件） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> userOf(Map<Long, Map<String, Object>> cache, Long id) {
        if (id == null) {
            return null;
        }
        return cache.computeIfAbsent(id, k -> {
            try {
                return (Map<String, Object>) authUserClient.getUser(k).get("data");
            } catch (Exception e) {
                log.warn("获取用户信息失败 userId={}: {}", k, e.getMessage());
                return null;
            }
        });
    }

    private String nameOf(Map<Long, Map<String, Object>> cache, Long id) {
        Map<String, Object> u = userOf(cache, id);
        return u == null ? "用户#" + id : String.valueOf(u.get("name"));
    }

    private String emailOf(Map<Long, Map<String, Object>> cache, Long id) {
        Map<String, Object> u = userOf(cache, id);
        return u == null ? null : (String) u.get("email");
    }
}
