# TaskFlow · 任务管理系统

> 企业内部任务分派、跟踪、验收、提醒、统计与权限管理平台。
> 前后端分离 · Spring Cloud 微服务 · 事件驱动 · 每服务独立库

![java](https://img.shields.io/badge/Java-17-0E7C86) ![spring boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-0E7C86) ![spring cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.3-0E7C86) ![vue](https://img.shields.io/badge/Vue-3-2F9E6E) ![postgres](https://img.shields.io/badge/PostgreSQL-18-3B6FD4)

---

## 目录

- [1. 项目简介](#1-项目简介)
- [2. 功能特性](#2-功能特性)
- [3. 系统架构](#3-系统架构)
- [4. 服务与端口](#4-服务与端口)
- [5. 数据架构](#5-数据架构)
- [6. 通信与一致性](#6-通信与一致性)
- [7. 安全架构](#7-安全架构)
- [8. 技术栈](#8-技术栈)
- [9. 代码结构](#9-代码结构)
- [10. 本地开发启动](#10-本地开发启动)
- [11. 部署](#11-部署)
- [12. 配置与环境变量](#12-配置与环境变量)
- [13. 接口约定](#13-接口约定)
- [14. 测试与验收](#14-测试与验收)
- [15. 默认账号](#15-默认账号)
- [16. 文档索引](#16-文档索引)
- [17. 已知限制与演进方向](#17-已知限制与演进方向)

---

## 1. 项目简介

TaskFlow 面向企业内部小团队的任务协作场景,解决三个问题:**任务归属与进度不透明、逾期无人跟进、工作量无法量化**。

一期为全量交付:任务全生命周期在线化、责任到人、数据可度量、权限可控。已完成 M0–M6 全部里程碑,PRD 第 10 章 **16 条验收标准全部通过**(REST Assured 54 个用例)。

## 2. 功能特性

| 域 | 能力 |
|---|---|
| **任务管理** | 创建/编辑/删除、8 动作状态机(受理 → 进度 → 提交验收 → 通过/驳回 → 转派 → 优先级 → 到期 → 归档)、操作时间线、可见性规则 |
| **任务内容** | 评论、附件(本地存储/白名单/20MB/每任务 10 个)、子任务(仅一级;父任务有未完成子任务禁止验收) |
| **列表与检索** | 6 个筛选维度任意叠加、范围切换(全部/我创建/指派给我)、关键字命中创建人与处理人、分页 |
| **导入导出** | CSV 导出(UTF-8 BOM、按当前筛选、1 万行上限)、Excel 模板下载与导入(≤500 行、整批校验、逐行错误、批次留痕) |
| **通知中心** | 8 类业务事件站内信 + 邮件双通道、铃铛未读角标(60s 轮询)、消息面板与通知中心页、系统告警隔离 |
| **统计总览** | 6 项 KPI + 4 张图表(趋势/状态/优先级/人员负载)、四种统计区间、事件驱动预聚合 + 一键 rebuild 校准 |
| **日程** | 月历按到期日聚合(含子任务)、可选具体日期自动跳月并高亮、当日日程面板 |
| **权限管理** | 用户 CRUD/停用/重置密码/角色指派、部门管理、**权限矩阵即改即存**(3 角色 × 14 权限点)、审计日志 |
| **开放接入** | API Key 签发/停用/重生成,仅开放 `POST /task/api/v1/tasks`,绑定 taskAdmin 服务账号 |
| **运维能力** | 定时任务(到期提醒/逾期提醒/自动归档/孤儿附件清理/数据保留清理)、死信队列、权限缓存自愈 |

## 3. 系统架构

### 3.1 拓扑

```
                        ┌──────────────────────────────────┐
   浏览器 ──────────────▶│ 前端 SPA  Vue3 + Vite + Element  │  dev :5173 / 生产 nginx
                        └────────────────┬─────────────────┘
                     /auth /task /notification /stats (代理到网关)
                                         ▼
                        ┌──────────────────────────────────┐
                        │  gateway-service          :8000  │  Spring Cloud Gateway (WebFlux)
                        │  ① JWT 验签 + jti 黑名单          │
                        │  ② API Key 校验(Redis 缓存 60s)   │
                        │  ③ 限流 429 / code 1003          │
                        └──┬─────────┬─────────┬───────────┘
        Path 路由 + lb     │         │         │   透传 X-User-Id / X-Role-Key
        ┌──────────────────┼─────────┼─────────┼──────────────────┐
        ▼                  ▼         ▼         ▼                  ▼
 ┌───────────────┐ ┌───────────────┐ ┌────────────────┐ ┌───────────────┐
 │auth-user      │ │task-service   │ │notification    │ │stats-service  │
 │  :8081        │ │  :8082        │ │  :8083         │ │  :8084        │
 │ 认证/用户/角色 │ │ 任务/状态机/   │ │ 站内信/邮件/DLQ │ │ 统计预聚合     │
 │ 权限矩阵/审计  │ │ 评论/附件/日程 │ │ 数据保留清理    │ │ 总览接口 #47   │
 │ API Key       │ │ 导入导出       │ │                │ │               │
 └──┬────────────┘ └──┬────────────┘ └───┬────────────┘ └──┬────────────┘
    ▼                 ▼                  ▼                 ▼
 auth_user_db     task_db         notification_db      stats_db     ← 每服务独占库
    └─────────────────┴────────┬───────┴─────────────────┘
                              ▼
   PostgreSQL 5432 · Redis 6379(共享:权限缓存/黑名单/锁/限流桶)
   RabbitMQ 5672(事件总线) · Nacos 8848(注册发现) · Nginx(静态与反代)
```

### 3.2 架构风格

| 风格 | 采用 | 说明 |
|---|---|---|
| 前后端分离 SPA | ✅ | Vue3 + Vite + Element Plus + Pinia,接口统一走网关 |
| API 网关模式 | ✅ | 唯一入口:路由、鉴权、限流、身份透传 |
| 微服务 + 数据库 per service | ✅ | 5 服务 4 库,跨库仅逻辑外键,无跨库事务 |
| 服务注册发现 | ✅ | Nacos;OpenFeign + LoadBalancer 客户端负载均衡 |
| 事件驱动(EDA) | ✅ | transactional outbox → at-least-once → 幂等消费 → 死信队列 |
| 分层架构 | ✅ | Controller → Service → Mapper(MyBatis-Plus)→ Entity |
| 预聚合读模型 | ✅ | 统计侧 `stats_task_daily` / `stats_completion_daily` + rebuild 校准 |
| 分布式事务框架 | ❌ | 用「同库事务 + 事件最终一致 + rebuild 补偿」替代 |
| 服务网格 / 链路追踪 / 熔断 | ❌ | 未引入(见 [第 17 节](#17-已知限制与演进方向)) |

### 3.3 服务边界与职责

| 服务 | 职责 | 独占库 | 依赖 |
|---|---|---|---|
| **gateway-service** | 统一入口、路由、JWT/API Key 鉴权、限流、身份透传 | — | Redis、auth(API Key 校验) |
| **auth-user-service** | 登录/登出/刷新/改密、用户与部门、角色、**权限矩阵**、审计日志、API Key、权限缓存所有者 | `auth_user_db` | Redis |
| **task-service** | 任务 CRUD 与状态机、可见性、时间线、评论、附件、子任务、日程聚合、导入导出、**事件生产(outbox)**、定时任务 | `task_db` | PG、Redis、RabbitMQ、auth(Feign) |
| **notification-service** | 消费任务事件 → 站内消息 + 邮件(重试/落库/告警)、消息查询接口、死信消费、数据保留清理 | `notification_db` | RabbitMQ、PG、auth(Feign)、SMTP |
| **stats-service** | 消费事件增量维护日聚合/人员负载/逾期快照、统计总览接口、rebuild 全量校准 | `stats_db` | RabbitMQ、PG、Redis、auth + task(Feign) |
| **frontend** | 单页应用:登录、任务列表与详情抽屉、日程、统计、通知中心、权限管理 | — | 网关 API |

## 4. 服务与端口

| 端口 | 用途 |
|---|---|
| 5173 | 前端 Vite dev server |
| 8000 | **网关**(唯一后端入口;8080 被 Nacos 控制台占用,故网关用 8000) |
| 8081 / 8082 / 8083 / 8084 | auth-user / task / notification / stats |
| 8080 | Nacos 控制台(本机已关闭登录认证) |
| 8848 / 9848 | Nacos 注册中心(HTTP / gRPC) |
| 5432 / 6379 / 5672 | PostgreSQL / Redis / RabbitMQ |
| 8088 | 本机 nginx(brew 默认页) |
| 15672 | RabbitMQ 管理台 |

## 5. 数据架构

**四库一服务**(无跨库外键,跨库引用为逻辑 id):

| 库 | 主要表 |
|---|---|
| `auth_user_db` | `app_user`、`role`、`role_permission`(42 行权限矩阵)、`department`、`api_key`、`audit_log` |
| `task_db` | `task`、`task_timeline`、`task_comment`、`task_attachment`、`event_outbox`(本地消息表)、`import_batch` |
| `notification_db` | `notification`、`mail_record`、`processed_event`(消费幂等) |
| `stats_db` | `stats_task_daily`(创建日聚合)、`stats_completion_daily`(完成日聚合)、`stats_assignee_load`、`stats_overdue_daily`、`processed_event` |

- **迁移**:Flyway 随服务启动自动执行(每库独立 `db/migration`)
- **种子**:3 角色、42 行权限矩阵、初始 admin、默认部门
- **保留策略**:站内信 180 天、邮件记录 1 年、导入批次 1 年(定时清理)

## 6. 通信与一致性

### 6.1 同步 —— OpenFeign(实时性要求高)

| 调用 | 场景 |
|---|---|
| task → auth | 处理人合法性校验(2007)、姓名解析、写审计 |
| notification → auth | 取收件人姓名/邮箱、管理员信息 |
| stats → auth / stats → task | 人员姓名与角色过滤;rebuild 拉全量任务 |
| gateway → auth | API Key 校验回源 |

身份头(`X-User-Id` / `X-Role-Key`)由 `FeignConfig` 的 `RequestInterceptor` 透传;MQ 消费线程无请求上下文时注入系统身份。

### 6.2 异步 —— RabbitMQ 事件总线

```
task-service 业务事务 ──写──▶ event_outbox ──定时投递(2s)──▶ exchange: task.events
                                                                  │ routing key = 事件类型
                        ┌─────────────────────────────────────────┴──────────────┐
                        ▼                                                        ▼
        queue: notification.task.events                            queue: stats.task.events
        (站内信 + 邮件;失败落 DLQ)                                  (增量聚合)
                        │ 失败
                        ▼
        exchange: task.events.dlx → queue: notification.task.events.dlq(人工排障)
```

**领域事件**:`task.assigned`、`task.transferred`、`task.commented`、`task.acceptance.submitted`、`task.approved`、`task.rejected`、`task.due.soon`、`task.overdue`,以及面向统计的 `task.status.changed`。

**事件信封**(common `TaskEvents.TaskEvent`,字段只增不改):

```json
{ "eventId": "uuid(消费幂等键)", "eventType": "task.assigned",
  "payload": { "taskId": 1, "taskNo": "TSK-100001", "title": "…", "assigneeId": 2, "creatorId": 1 },
  "occurredAt": "2026-09-09T10:00:00Z" }
```

### 6.3 一致性策略

| 场景 | 策略 |
|---|---|
| 单库多表(删除任务连带评论/附件/时间线) | Spring `@Transactional` 强一致 |
| 跨服务(任务变更 → 通知/统计) | **outbox + at-least-once + processed_event 幂等**,允许最终一致 |
| 聚合数据漂移 | `POST /stats/api/v1/rebuild` 全量重算(admin 专用) |
| 定时任务多实例 | Redis `setIfAbsent` 分布式锁,抢不到即跳过 |
| 事务与副作用(写审计) | `TransactionSynchronizationManager` 提交后执行,失败仅告警不回滚主流程 |

## 7. 安全架构

| 环节 | 机制 |
|---|---|
| **登录** | BCrypt 校验 → 签发 JWT(HS256,含 `jti`,2h)+ refresh token(7d,Redis) |
| **令牌失效** | 登出/改密把 `jti` 写入 Redis 黑名单;网关每次校验 |
| **请求链路** | 网关验签 → 透传 `X-User-Id` / `X-Role-Key` → 下游拦截器建立 `AuthContext` |
| **权限判定** | 14 个权限点;各服务读共享 Redis `auth:perms:{roleKey}`(持久缓存 + 矩阵变更主动失效 + miss 回源自愈),方法级 `@RequirePerm` |
| **数据可见性** | admin 全可见;否则创建人/处理人/授权范围;越权统一 403 且不区分"不存在/不可见"(防探测) |
| **第三方接入** | `X-API-Key`(库内 sha256、明文仅签发时返回一次),仅放行 `POST /task/api/v1/tasks`,停用即时 401 |
| **防护** | 登录 5 次失败锁 15 分钟、SQL 全参数化、Vue 默认转义(全站无 `v-html`)、附件白名单 + UUID 落盘名防路径穿越 |
| **限流** | 网关 Redis 分钟桶:用户 300/min、匿名按 IP 600/min,超限 429 + code 1003 |

> 生产上线前必改:JWT_SECRET、数据库/Redis/RabbitMQ/SMTP 口令,并开启 HTTPS —— 详见 [docs/安全核查清单.md](docs/安全核查清单.md)。

## 7.5 可观测性

| 能力 | 现状 |
|---|---|
| 健康检查/探针 | 五服务 `/actuator/health`(+ `/health/readiness` `/health/liveness`),`/actuator/**` 白名单放行 |
| 指标 | `micrometer-registry-prometheus`,抓取端点 `/actuator/prometheus`(含 `application` 标签) |
| 链路追踪 | 网关生成/透传 `X-Trace-Id`,下游 MDC 注入,日志 pattern 输出 `[traceId]`,跨服务可 grep 串联 |
| 日志 | 五服务统一 `logback-spring.xml`:控制台+文件双输出、按天滚动 30 天、`totalSizeCap=1GB` |
| 配置管理 | Nacos 配置中心(共享 + 专属 dataId,热刷新) |

> 尚未接入:Prometheus/Grafana 实际部署、分布式追踪后端(Jaeger/OTel)、告警规则。详见 [docs/可观测性与配置中心.md](docs/可观测性与配置中心.md)。

## 8. 技术栈

**后端**:Java 17 · Spring Boot 3.3.5 · Spring Cloud 2023.0.3 · Spring Cloud Alibaba 2023.0.1.0 · Spring Cloud Gateway(WebFlux)· OpenFeign + LoadBalancer · Spring AMQP · Spring Data Redis · Spring Mail · Spring Scheduling/事务 · Actuator + Micrometer(Prometheus)· MyBatis-Plus 3.5.7 · Flyway 10 · JJWT 0.12.6 · Apache POI 5.3.0 · PostgreSQL 18 · RabbitMQ 3 · Redis 7 · Nacos(注册发现 + 配置中心)

**前端**:Vue 3.5 · TypeScript 5.9 · Vite 5 · Element Plus 2.14 · Pinia 2 · Vue Router 4 · ECharts 5 · Sass

**测试**:JUnit 5 · REST Assured 5 · Mockito · reactor-test

## 9. 代码结构

```
TaskFlow/
├── pom.xml                    # 父 POM(聚合 + 依赖版本管理)
├── common/                    # 公共件:Result 信封、ErrorCode、BizException、JwtUtils、RedisUtils、事件契约 TaskEvents
├── gateway-service/           # 网关:JwtAuthFilter / ApiKeyAuthFilter / RateLimitFilter
├── auth-user-service/         # 认证与用户域(权限矩阵、审计、API Key、权限缓存所有权)
├── task-service/              # 任务域(状态机、内容、导入导出、outbox、定时任务)
├── notification-service/      # 通知域(事件消费、站内信、邮件、DLQ、保留清理)
├── stats-service/             # 统计域(事件聚合、总览接口、rebuild)
├── frontend/                  # Vue3 SPA(src/api · components · views · layouts · router · styles)
├── tests/acceptance/          # 验收测试模块(54 用例,映射 PRD 16 条验收)
├── deploy/                    # 部署产物:docker-compose.yml、Dockerfile×6、nginx.conf、build.sh、脚本、.env.example、README
├── docs/                      # 需求/设计/里程碑/运维/走查 全套文档
└── start.sh / stop.sh         # 本地一键起停(中间件检查 → Nacos → 依赖安装 → 5 服务 → 健康检查)
```

## 10. 本地开发启动

### 10.1 前置依赖

| 依赖 | 说明 |
|---|---|
| JDK 17 | 本项目验证于 `/opt/homebrew/opt/openjdk@17` |
| Maven | 使用项目内本地仓库 `-Dmaven.repo.local=<repo>/.m2/repository` |
| Node 18+ / pnpm | 前端 |
| 中间件 | PostgreSQL 18、Redis、RabbitMQ、Nacos(standalone);macOS 可用 brew 管理 |

### 10.2 启动步骤

```bash
# 1) Nacos(standalone;勿重复启动,会报 8848 被占)
sh ~/Downloads/nacos/bin/startup.sh -m standalone
# 控制台 http://localhost:8080(本机已关闭控制台登录认证)

# 2) 后端 + 前端
cd TaskFlow
./start.sh --frontend        # 起 5 个服务 + 前端(:5173);日志 logs/,pid .pids/
./stop.sh                    # 停止
```

首次运行且数据库为空时,四个业务库需先创建(详见 [docs/本地中间件.md](docs/本地中间件.md)):

```bash
for db in auth_user_db task_db notification_db stats_db; do
  PGPASSWORD=root psql -h 127.0.0.1 -U postgres -c "CREATE DATABASE $db OWNER postgres;"
done
PGPASSWORD=root psql -h 127.0.0.1 -U postgres -d task_db -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
```

表结构与种子数据由各服务 Flyway 首次启动自动执行。

### 10.3 访问入口

| 入口 | 地址 |
|---|---|
| 前端 | http://localhost:5173(dev server 绑定 localhost,`127.0.0.1:5173` 可能不可用) |
| 网关 | http://127.0.0.1:8000 |
| Nacos 控制台 | http://localhost:8080(免登录) |
| RabbitMQ 管理台 | http://127.0.0.1:15672(guest/guest) |

> 排查提示:若出现"改了代码不生效",先用 `lsof -nP -iTCP:<端口> -sTCP:LISTEN` 确认是否有**孤儿 Java 进程**占着端口(pid 文件记录的是 mvn 包装进程)。

## 11. 部署

### 11.1 Docker Compose(用于支持 Docker 的环境)

```bash
cd TaskFlow/deploy
cp .env.example .env          # 填生产凭据(标「必改」项)
./build.sh                    # 构建后端 jar + 前端 dist 到 deploy/artifacts/
docker compose up -d --build  # 起 PG/Redis/RabbitMQ/Nacos + 5 服务 + Nginx(前端 :8080)
```

### 11.2 裸机部署

1. `cd deploy && ./build.sh` 产出 jar 与 `frontend-dist`
2. 安装 PostgreSQL / Redis / RabbitMQ / Nacos(standalone)
3. `cp .env.example .env` 并按需修改;`./scripts/start-all.sh` 起服务
4. 用 `deploy/nginx.conf` 托管前端并反代 `/auth|/task|/notification|/stats` 到网关 8000
5. `./scripts/backup.sh` 加入 crontab:每日全量备份四库 + 附件目录,滚动保留 7 天(RPO 24h)

> 详见 [deploy/README.md](deploy/README.md)。注意:`deploy/` 已通过 `compose config` 与 `nginx -t` 静态校验,**镜像构建与整套 `up` 尚未在真机演练**。

## 12. 配置与环境变量

| 变量 | 作用 | 默认(开发) |
|---|---|---|
| `JWT_SECRET` | 五服务共用的 JWT 签名密钥 | `taskflow-dev-secret-please-override` ⚠️必改 |
| JWT TTL | 令牌有效期 | `PT2H`(refresh 7d) |
| `POSTGRES_USER/PASSWORD` 等 | 各服务数据源 | `postgres/root@127.0.0.1:5432` ⚠️必改 |
| `REDIS_HOST/PORT/PASSWORD` | 缓存/锁/黑名单/限流 | `127.0.0.1:6379`,密码 `root` ⚠️必改 |
| RabbitMQ | 事件总线 | `guest/guest@127.0.0.1:5672` ⚠️必改 |
| `SMTP_HOST/PORT/USERNAME/PASSWORD/AUTH/STARTTLS/TIMEOUT` | 邮件通道 | 本机 `localhost:25` 无认证(未配置时邮件失败 → 系统告警) |
| `MAIL_FROM` | 发件人地址 | `taskflow@example.com` |
| `ATTACHMENT_ROOT` | 附件存储根目录 | `/tmp/taskflow/attachments` |
| `taskflow.ratelimit.*` | 网关限流配额 | user 300/min、ip 600/min |

> 密钥只经环境变量注入,代码库与镜像内不含任何真实凭据。

### Nacos 配置中心(已落地)

五个服务通过 `spring.config.import` 拉取两条可选配置:`taskflow-common.yaml`(共享:限流配额、日志级别)与 `${spring.application.name}.yaml`(服务专属),**支持运行时热刷新**(`@RefreshScope`,实测改配额即生效)。密钥类配置仍走环境变量。详见 [docs/可观测性与配置中心.md](docs/可观测性与配置中心.md)。

## 13. 接口约定

- **前缀路由**:`/auth/api/v1/**` → auth;`/task/api/v1/**` → task;`/notification/api/v1/**` → notification;`/stats/api/v1/**` → stats
- **统一响应信封**:`{ "code": 0, "message": "ok", "data": {…}, "details": null }`,`code = 0` 唯一表示成功
- **错误码分段**:1xxx 通用 / **2xxx 任务** / **3xxx 权限** / **4xxx 通知**(全表见 [接口设计文档](docs/接口设计文档-v1.0.md) 第 6 章)
- **HTTP 语义**:参数与业务错误 400、未认证 401、无权限 403、不存在 404;不使用"200 包一切"
- **时间**:ISO 8601 UTC,前端按东八区渲染;资源路径用内部 id(`taskNo` 仅展示与搜索)
- 共 47 个接口(任务域 32、认证与用户域 18、通知 4、统计 1 等分域编号)
- **幂等**:`POST /task/api/v1/tasks` 支持可选 `Idempotency-Key`(同用户+Key 24h 内只建一条,并发超时 409/2014,详见 [可观测性与配置中心](docs/可观测性与配置中心.md))

## 14. 测试与验收

```bash
cd TaskFlow
# 验收套件(需系统已在运行;阶段一 15 个类)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn install -pl tests/acceptance \
  -Dmaven.repo.local=$PWD/.m2/repository test

# 定向重跑
mvn install -pl tests/acceptance -Dmaven.repo.local=$PWD/.m2/repository \
  test -Dtest='Acceptance11ImportTest,Acceptance16SubtaskTest'
```

- `tests/acceptance/`:54 个用例**逐条映射 PRD 第 10 章 16 条验收**,自建账号、唯一前缀数据、跑完自动清理
- ACC-4(自动归档)与 ACC-10Reminder 属阶段二:需临时缩短定时任务 cron 窗口并以 `-Dtf.scheduled.tests=true` 执行
- 结果见 [M6.1 验收报告](docs/里程碑-M6.1-验收报告.md);性能见 [M6 性能报告](docs/里程碑-M6-性能报告.md)

**性能基线**(1 万任务规模):列表 P95 **6ms** · 详情 **3ms** · 统计 **8ms** · 导入 500 行 **0.67s** · 导出 1 万行 **0.21s**(均远超 PRD 目标)

## 15. 默认账号

| 账号 | 角色 | 密码 | 备注 |
|---|---|---|---|
| `admin` | 系统管理员 | `Admin@123` | **首次登录强制改密** |
| `zhangming` / `zhaoqiang` | 任务管理员 | `TaskFlow@2026` | 演示账号 |
| `lihua` / `wangfang` | 普通用户 | `TaskFlow@2026` | 演示账号(可见性/权限受限) |
| `testuser` | 普通用户 | `TaskFlow@2026` | 早期联调账号 |

> 忘记 admin 密码的应急重置见 [本地中间件](docs/本地中间件.md) 或 [上线手册](docs/上线手册.md)。

## 16. 文档索引

| 类别 | 文档 |
|---|---|
| 需求与设计 | [PRD](docs/PRD-任务管理系统-v1.0.md) · [架构设计](docs/架构设计文档-v1.0.md) · [库表设计](docs/库表设计文档-v1.0.md) · [接口设计](docs/接口设计文档-v1.0.md) · [UI 设计规范](docs/UI设计规范-v1.0.md) · [实施计划](docs/实施计划-v1.0.md) |
| 里程碑报告 | [M0](docs/里程碑-M0-工程脚手架.md) · [M1](docs/里程碑-M1-认证与用户域.md) · [M2](docs/里程碑-M2-任务核心.md) · [M3](docs/里程碑-M3-通知与事件链路.md) · [M4](docs/里程碑-M4-任务扩展.md) · [M5](docs/里程碑-M5-统计与定时任务.md) · [M6](docs/里程碑-M6-联调验收与上线.md) · [M6.1 验收](docs/里程碑-M6.1-验收报告.md) · [M6 性能](docs/里程碑-M6-性能报告.md) |
| 运维与上线 | [可观测性与配置中心](docs/可观测性与配置中心.md) · [本地中间件](docs/本地中间件.md) · [安全核查清单](docs/安全核查清单.md) · [上线手册](docs/上线手册.md) · [deploy/README](deploy/README.md) |
| 规划与评估 | [企业级能力对照与演进路线](docs/企业级能力对照与演进路线.md) · [项目现状](docs/项目现状.md) · [人工走查清单](docs/人工走查清单.md) · [前端 UI 走查报告](docs/前端UI走查报告.md) |
| 原型 | [可交互原型](docs/任务管理系统原型.html) · [关键页高保真](docs/ui/) |

## 17. 已知限制与演进方向

**已知限制**

1. **无 CI/CD**;单元测试较薄(以验收套件为主)
2. **默认凭据 + HTTP**:生产必须更换密钥并启用 TLS
3. **部署未真机演练**:Compose 未实际 `up`,备份恢复未演练
4. **SMTP 未配置**:邮件通道不可用(失败仅记 `mail_record` + 一条系统告警)
5. **单实例无高可用**:Nacos standalone、PG/Redis/Rabbit 单机
6. **可观测性已补齐基础面**(Actuator/Prometheus/traceId/统一日志/Nacos Config);仍缺 Prometheus-Grafana 实际部署与追踪后端、告警规则
7. 统计部分口径按库表设计简化(P0、趋势 completed);转派完成态任务的负载漂移靠 `rebuild` 兜底
8. 历史遗留:压测/验收产生的测试账号与旧 `mail.failed` 记录待整理

**演进路线(建议优先级)**

1. **1 天**:GitHub Actions(编译 + 验收套件 + 前端构建);清理测试账号;配置真 SMTP
2. **3 天**:备份恢复演练 + Compose 真机跑通 + HTTPS + 生产密钥注入
3. **1 周**:关键 Service 单测补齐;部署 Prometheus + Grafana 看板与告警规则;接入 OTel/Jaeger 追踪后端
4. **2 周+**:服务多副本与中间件高可用、权限缓存模型重构、统计口径与 PRD 完全对齐

---

## License

MIT(见 [LICENSE](LICENSE))
