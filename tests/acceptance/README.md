# TaskFlow M6.1 验收测试（tests/acceptance）

针对 PRD 第 10 章 16 条验收条件的 REST Assured 集成测试套件，直连全链路网关
`http://127.0.0.1:8000`（auth / task / notification / stats 四服务 + Redis/PG/RabbitMQ）。

## 结构

```
tests/acceptance/
├── pom.xml
└── src/test/
    ├── java/com/taskflow/acceptance/
    │   ├── Conf.java                 # 环境常量（网关/DB/Redis/附件根）
    │   ├── Api.java                  # 信封解析、断言、等待工具
    │   ├── Db.java                   # 四个 PG 业务库访问（白盒校验/预造/清理）
    │   ├── AccBase.java              # 基类：唯一账号/任务、矩阵还原、数据清理
    │   ├── Acceptance1StateMachineTest.java      # 条件1 状态机
    │   ├── Acceptance2VisibilityTest.java        # 条件2 可见性
    │   ├── Acceptance3RejectTest.java            # 条件3 验收驳回
    │   ├── Acceptance4AutoArchiveTest.java       # 条件4 自动归档（定时，阶段二）
    │   ├── Acceptance5FilterTest.java            # 条件5 列表筛选
    │   ├── Acceptance6StatsTest.java             # 条件6 统计口径
    │   ├── Acceptance7CalendarTest.java          # 条件7 日程
    │   ├── Acceptance8PermissionMatrixTest.java  # 条件8 权限矩阵
    │   ├── Acceptance9SuperAdminOpsTest.java     # 条件9 超管操作
    │   ├── Acceptance10NotificationEventTest.java# 条件10 通知(事件1-5,8)
    │   ├── Acceptance10ReminderJobTest.java      # 条件10 到期/逾期提醒（定时，阶段二）
    │   ├── Acceptance11ImportTest.java           # 条件11 Excel 导入
    │   ├── Acceptance12ExportTest.java           # 条件12 CSV 导出
    │   ├── Acceptance13SecurityTest.java         # 条件13 安全
    │   ├── Acceptance14CommentTest.java          # 条件14 评论
    │   ├── Acceptance15AttachmentTest.java       # 条件15 附件
    │   └── Acceptance16SubtaskTest.java          # 条件16 子任务
    └── resources/junit-platform.properties       # 按 @Order 串行执行
```

## 运行

前置：全量服务在线；`notification-service` 建议指向本地 SMTP sink
（见阶段准备 ③），否则每条邮件会重试 7s 拖慢整条事件链。

```bash
cd TaskFlow
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
# 阶段一（除两个定时类；ACC-4 / ACC-10b 默认 assumeTrue 跳过）
mvn install -pl tests/acceptance -Dmaven.repo.local=$PWD/.m2/repository test \
  -Dtest='Acceptance1StateMachineTest,Acceptance2VisibilityTest,Acceptance3RejectTest,Acceptance5FilterTest,Acceptance6StatsTest,Acceptance7CalendarTest,Acceptance8PermissionMatrixTest,Acceptance9SuperAdminOpsTest,Acceptance10NotificationEventTest,Acceptance11ImportTest,Acceptance12ExportTest,Acceptance13SecurityTest,Acceptance14CommentTest,Acceptance15AttachmentTest,Acceptance16SubtaskTest'

# 阶段二（先做定时窗口准备：临时缩短 task-service 三个 job cron 为 */15s 并
#         将扫描范围限定到 ACC-% 标题，见 docs/里程碑-M6.1-验收报告.md，跑完必须还原）
mvn -pl tests/acceptance -Dmaven.repo.local=$PWD/.m2/repository test \
  -Dtest='Acceptance4AutoArchiveTest,Acceptance10ReminderJobTest' \
  -Dtf.scheduled.tests=true
```

## 阶段二的环境准备（定时任务触发）

ACC-4（自动归档）与 ACC-10 事件 6/7（到期/逾期提醒）由 task-service 定时任务驱动。
为在验收窗口内触发一轮真实扫描，临时修改
`task-service/src/main/java/com/taskflow/task/job/TaskMaintenanceJobs.java`：

1. 三个 `@Scheduled(cron=...)` 改为 `*/15 * * * * *`（15 秒一轮）；
2. 三个扫描 SQL 临时追加 `AND title LIKE 'ACC-%'`（只扫验收数据，避免对共享环境存量
   数据风暴；扫描逻辑本身不变）；
3. 重启 task-service 跑阶段二，随后 `git checkout` 还原该文件并再次重启验证。

> 任何一次对共享环境/主工程的临时改动都必须在报告里留证据（本模块 README + 报告文档）。

## 数据卫生

- 每个类使用唯一前缀账号（`m61{cls}_{n}{a|u}_{ts}`）与任务标题（`ACC-{cls}-…-{uniq}`），
  @AfterAll 清理：任务/时间线/评论/附件(含文件)/通知/邮件/导入批次；用户不可物理删除，
  统一停用；权限矩阵恢复默认（PRD 3.3）。
- ACC-6 统计用例在 `POST /stats/api/v1/rebuild`（admin）后与手工口径比对，避免历史
  聚合漂移干扰。
- 失败用例若指向产品缺陷，不改主工程代码，在报告中登记复现与建议。
