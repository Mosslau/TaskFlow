package com.taskflow.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 验收条件 10 · 通知（事件 6/7 定时部分）：到期前 24h 提醒每任务仅一次（task.due.soon，
 * due_reminded 置位）；逾期提醒每日扫描（task.overdue，处理人+创建人双通道；cron 每日一次）。
 *
 * <p>触发方式：需 task-service cron 临时缩短（阶段二），默认跳过。
 * 到期提醒扫描存在 due_reminded 防重；逾期扫描无同日防重（语义由 cron 每日 09:00 一次保证）。</p>
 */
@Order(20)
@DisplayName("ACC-10b 到期/逾期提醒（定时任务驱动）")
public class Acceptance10ReminderJobTest extends AccBase {

    public Acceptance10ReminderJobTest() {
        this.cls = "rmd";
    }

    private TUser creator;
    private TUser assignee;
    private String cToken;
    private String aToken;

    @BeforeAll
    void setup() {
        assumeTrue(Boolean.getBoolean("tf.scheduled.tests"), "提醒测试需定时任务 cron 已临时缩短（阶段二）");
        creator = newUser("提醒创建人" + uniq(), Conf.ROLE_TASK_ADMIN);
        assignee = newUser("提醒处理人" + uniq(), Conf.ROLE_USER);
        cToken = Api.login(creator.account(), creator.password()).token();
        aToken = Api.login(assignee.account(), assignee.password()).token();
    }

    @AfterAll
    void teardown() {
        cleanupAll();
    }

    @Test
    @Order(1)
    @DisplayName("事件6 到期提醒：24h 内到期任务发一次 task.due.soon（due_reminded 置位后不再重复）")
    void t01_dueSoonOnce() {
        String due = OffsetDateTime.now(ZoneOffset.ofHours(8)).plusHours(6)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        JsonNode t = createTask(cToken, title("RD1"), assignee.id(), null, null, due, null);
        long id = t.path("id").asLong();
        // 释放到期扫描锁，让下一轮(15s)真正执行
        deleteJobLocks("taskflow:job:due-scan");
        // 等待到期提醒扫描发送
        awaitNotifications(id, assignee.id(), "task.due.soon", 1);
        // due_reminded 置位（防重证据）
        assertEquals("t", Db.scalar(Conf.DB_TASK, "SELECT due_reminded FROM task WHERE id=?", id),
                "到期提醒后 due_reminded 应置位");
        // 再等 ≥2 个扫描周期，确认不重复发送
        try {
            Thread.sleep(40_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(1, Db.scalarInt(Conf.DB_NOTIFICATION,
                        "SELECT count(*) FROM notification WHERE task_id=? AND recipient_id=? AND event_type=?",
                        id, assignee.id(), "task.due.soon"),
                "到期提醒每任务仅一次");
        // 邮件记录也有对应成功邮件
        Api.await(Conf.EVENT_WAIT.toMillis(), 2000,
                () -> !Db.rows(Conf.DB_NOTIFICATION,
                        "SELECT 1 FROM mail_record WHERE task_id=? AND subject LIKE '%到期提醒%' LIMIT 1", id).isEmpty(),
                "到期提醒邮件记录");
    }

    @Test
    @Order(2)
    @DisplayName("事件7 逾期提醒：处理人与创建人均收到（每日 09:00 扫描语义）；扫描后转走到期防重复")
    void t02_overdueNotifiesBoth() {
        // 逾期任务：due_reminded 置位避免被到期扫描抢先、只验证逾期扫描；due 在过去
        String due = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusHours(2)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        JsonNode t = createTask(cToken, title("RD2"), assignee.id(), null, null, due, null);
        long id = t.path("id").asLong();
        Db.update(Conf.DB_TASK, "UPDATE task SET due_reminded=TRUE WHERE id=?", id);
        // 释放逾期扫描锁，让下一轮(15s)真正执行
        deleteJobLocks("taskflow:job:overdue-scan");

        awaitNotifications(id, assignee.id(), "task.overdue", 1);
        awaitNotifications(id, creator.id(), "task.overdue", 1);

        // 尽快把该任务到期转走/删除，防止同一分钟内下一轮扫描重复（cron 语义为每日一次）
        Db.update(Conf.DB_TASK, "UPDATE task SET due_at = now() + INTERVAL '3 days' WHERE id=?", id);
        try {
            Thread.sleep(35_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 双通道 + 邮件记录（处理人与创建人各一封）
        assertEquals(1, Db.scalarInt(Conf.DB_NOTIFICATION,
                "SELECT count(*) FROM notification WHERE task_id=? AND recipient_id=? AND event_type=?",
                id, assignee.id(), "task.overdue"), "逾期通知处理人 1 条");
        assertEquals(1, Db.scalarInt(Conf.DB_NOTIFICATION,
                "SELECT count(*) FROM notification WHERE task_id=? AND recipient_id=? AND event_type=?",
                id, creator.id(), "task.overdue"), "逾期通知创建人 1 条");
        Api.await(Conf.EVENT_WAIT.toMillis(), 2000,
                () -> Db.scalarInt(Conf.DB_NOTIFICATION,
                        "SELECT count(*) FROM mail_record WHERE task_id=? AND recipient=? AND subject LIKE '%逾期提醒%'",
                        id, assignee.email()) >= 1,
                "逾期提醒邮件(处理人)");
        Api.await(Conf.EVENT_WAIT.toMillis(), 2000,
                () -> Db.scalarInt(Conf.DB_NOTIFICATION,
                        "SELECT count(*) FROM mail_record WHERE task_id=? AND recipient=? AND subject LIKE '%逾期提醒%'",
                        id, creator.email()) >= 1,
                "逾期提醒邮件(创建人)");
    }
}
