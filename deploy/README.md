# TaskFlow M6.3 部署说明

TaskFlow 微服务(Spring Boot 3.3 / Java 17):`gateway-service`、`auth-user-service`、
`task-service`、`notification-service`、`stats-service`,依赖 PostgreSQL / Redis /
RabbitMQ / Nacos;前端为 Vue3 + Vite 产物,经 Nginx 托管并把 `/auth /task /notification
/stats` 反代到网关(8000),网关再经 Nacos 服务发现 `lb://` 到各服务。

本目录(`TaskFlow/deploy/`)提供 **两种从零到可访问的部署方式**,产物全部文件化:

| 方式 | 入口 | 适合 |
|---|---|---|
| A. Docker Compose 一键编排 | `docker-compose.yml`(含 5 个服务 Dockerfile) | 有 Docker 的环境 |
| B. 裸机部署(jar + 系统中间件 + 系统 Nginx) | `scripts/start-all.sh` + `nginx.conf` | 单机/已有中间件 |

两种方式共用同一份构建产物(见 §3)与同一份环境变量模板(`.env.example`)。

---

## 1. 部署包结构

```
TaskFlow/deploy/
├── docker-compose.yml                  # 方式 A:一键编排
├── .env.example                        # 环境变量模板(复制为 .env 使用)
├── .gitignore                          # 忽略 .env/artifacts/data/logs/backups
├── .dockerignore                       # docker build 上下文精简
├── build.sh                            # 本地构建:5 个 fat jar + 前端 dist
├── nginx.conf                          # 方式 B:宿主机 Nginx 站点配置(server 8080)
├── docker/
│   ├── gateway-service/Dockerfile      # 5 个服务运行镜像(仅 COPY 预构建 jar)
│   ├── auth-user-service/Dockerfile
│   ├── task-service/Dockerfile
│   ├── notification-service/Dockerfile
│   ├── stats-service/Dockerfile
│   └── frontend/
│       ├── Dockerfile                  # Nginx 前端镜像
│       └── nginx.conf                  # 容器内站点配置(反代 gateway 服务名)
├── postgres/init/01-create-databases.sql  # 建 4 个业务库(空卷首次启动自动执行)
└── scripts/
    ├── start-all.sh                    # 方式 B:启动 5 个服务(env 由 .env 注入)
    ├── stop-all.sh                     # 方式 B:停止
    └── backup.sh                       # 每日全量备份(4 库 + 附件,7 天滚动)
```

构建后由 `build.sh` 额外生成(不入库,已 gitignore):

```
deploy/artifacts/
├── gateway-service/gateway-service-1.0.0.jar   # 可执行 Spring Boot fat jar
├── auth-user-service/auth-user-service-1.0.0.jar
├── task-service/task-service-1.0.0.jar
├── notification-service/notification-service-1.0.0.jar
├── stats-service/stats-service-1.0.0.jar
└── frontend-dist/                                # 前端静态产物
```

运行时由脚本/compose 生成的目录:`deploy/logs`、`deploy/.pids`、`deploy/data/attachments`(附件落盘,Compose 绑定卷)、`deploy/backups`。

---

## 2. 架构与端口表

```
                        浏览器
                          │ :8080
                          ▼
                    ┌───────────┐   /auth /task /notification /stats
                    │  Nginx    │ ────────────────────────────────┐
                    │(前端dist) │                                 ▼
                    └───────────┘                          gateway-service :8000
                                                      lb://(Nacos 注册发现)
                                          ┌──────────┬──────────┬──────────────┬──────────┐
                                          ▼          ▼          ▼              ▼          ▼
                                   auth-user :8081  task :8082  notification  stats :8084
                                        │            │  :8083         │
                          ┌──────────────┼─────┬──────┼───────────────┼──┐
                          ▼              ▼     ▼      ▼              ▼  ▼
                     PostgreSQL:5432   Redis:6379   RabbitMQ:5672   Nacos:8848(9848 gRPC)
                     (4 个业务库)
```

| 组件 | 端口 | 说明 |
|---|---|---|
| Nginx(前端) | 8080 | 托管 dist;反代 API 到网关。Compose 与裸机均为 8080 |
| gateway-service | 8000 | API 网关(对外唯一入口;各服务仅内网互访) |
| auth-user-service | 8081 | 认证与用户(库 `auth_user_db`) |
| task-service | 8082 | 任务(库 `task_db`;附件落盘) |
| notification-service | 8083 | 通知(库 `notification_db`;RabbitMQ/SMTP) |
| stats-service | 8084 | 统计(库 `stats_db`) |
| PostgreSQL | 5432 | 16.x;四个业务库。表结构与种子数据由 Flyway 自动迁移 |
| Redis | 6379 | 7.x;令牌黑名单/刷新会话等 |
| RabbitMQ | 5672 / 15672 | 3.x;事件/通知队列;15672 为管理控制台 |
| Nacos | 8848 / 9848 | 注册中心(v2.4.3 单机);控制台 `http://<host>:8848/nacos`;9848 为客户端 gRPC |
| MySQL | 3306 | 本项目业务不使用(见 docker-compose.yml 注释,默认不启动) |

> 端口映射可按需增删(docker-compose.yml 中均有注释说明)。若宿主机已占用某端口(例如本机
> 用 brew 已跑 PostgreSQL/Redis/RabbitMQ/Nacos),请先停宿主服务或改映射,见 §11 FAQ。

---

## 3. 前置准备与构建(两种方式共用)

### 3.1 构建环境要求(只需在一台“构建机”上)

| 依赖 | 版本 | 用途 |
|---|---|---|
| JDK | 17 | 后端编译(需 `JAVA_HOME` 指向 JDK17) |
| Maven | 3.8+ | 后端构建 |
| Node.js | 18+ | 前端 |
| pnpm | 任意 | 前端 |

> 本机开发环境与项目根 `start.sh` 一致即可;本地 Maven 仓库默认复用
> `TaskFlow/.m2/repository`(可用环境变量 `MVN_REPO` 覆盖)。

### 3.2 执行构建

```bash
cd TaskFlow/deploy
./build.sh
```

构建内容与关键点:

1. `mvn install -N`(父 POM)+ `mvn install -pl common` 装到本地仓库;
2. 逐个服务 `mvn -pl <svc> package spring-boot:repackage`:父 POM 未继承
   `spring-boot-starter-parent`,**repackage 不会自动绑定**,必须显式执行
   `spring-boot:repackage`,否则产出的是不可执行的瘦 jar;
3. 汇总 fat jar 到 `deploy/artifacts/<svc>/<svc>-1.0.0.jar`;
4. 前端 `pnpm install --frozen-lockfile && pnpm build`,产出拷贝到
   `deploy/artifacts/frontend-dist/`。

> 产物版本号默认 `1.0.0`,与父 POM `<version>` 一致;若升级版本,同步改父 POM 与本目录
> `build.sh` 的 `TASKFLOW_VERSION` 即可(镜像 tag / COPY 路径 / 启动脚本均引用同一约定)。

### 3.3 准备环境变量(两种方式都必做)

```bash
cd TaskFlow/deploy
cp .env.example .env
# 编辑 .env,至少修改:
#   JWT_SECRET          (openssl rand -base64 48)
#   POSTGRES_PASSWORD、REDIS_PASSWORD、RABBITMQ_PASSWORD
```

详见 §9 安全清单与 §10 环境变量总表。

---

## 4. 方式 A:Docker Compose 一键部署(从零到可访问)

前置:已安装 Docker Engine + Compose v2(本仓库验证环境为 Docker Compose v5.x)。

```bash
cd TaskFlow/deploy

# 1) 构建产物(必须在 docker compose build 之前)
./build.sh

# 2) 环境变量
cp .env.example .env        # 并按 §3.3 修改敏感项

# 3) (可选)语法校验
docker compose config

# 4) 构建并后台启动
docker compose up -d --build
```

说明:

- Compose 内部使用命名网络 `taskflow-net`,服务间以**服务名**互通(`postgres:5432`、
  `redis:6379`、`rabbitmq:5672`、`nacos:8848`),各服务 `application.yml` 中写死的
  `127.0.0.1` 均通过 `environment` 覆盖(见 compose 顶部 `x-app-env` 注释);
- 五个服务镜像只 COPY `deploy/artifacts` 里的预构建 jar,镜像内**无任何密钥**;
- `postgres` 首次启动(空数据卷)自动执行 `postgres/init/01-create-databases.sql`
  创建 4 个库;表结构与种子数据由各服务 Flyway 迁移;
- `task-service` 容器内附件目录固定 `/taskflow/attachments`,绑定宿主
  `deploy/data/attachments`(备份直接打宿主机目录即可);
- Nacos 单机模式:`MODE=standalone`(等价 `-m standalone`),使用**内置 Derby**,
  不依赖 MySQL/外部数据库。

验证(等 1~2 分钟完成首启,`docker compose ps` 全部 healthy):

```bash
curl http://127.0.0.1:8000/auth/api/v1/ping      # 期望 {"code":0,...}
# 浏览器打开 http://<host>:8080                    → 前端登录页
# 浏览器打开 http://<host>:8848/nacos              → Nacos 控制台,服务列表可见 5 个服务
# RabbitMQ 控制台 http://<host>:15672(用户/口令见 .env RABBITMQ_USER/PASSWORD)
```

常用命令:

```bash
docker compose ps                    # 状态
docker compose logs -f task-service  # 看某服务日志
docker compose down                  # 停止(数据卷保留)
docker compose down -v               # 停止并删除卷——数据全丢!仅测试环境使用
docker compose restart gateway-service
```

---

## 5. 方式 B:裸机部署(jar + 系统中间件 + 系统 Nginx,从零到可访问)

适用:单台 Linux/macOS 服务器,中间件用系统服务/brew 托管。

### 5.1 准备运行时依赖

1. **JDK 17**(仅运行 jar 用 JRE 亦可):安装后确认 `java -version`。
2. **PostgreSQL 16**:启动服务后创建 4 个库(表结构交给 Flyway):

   ```sql
   CREATE DATABASE auth_user_db;
   CREATE DATABASE task_db;
   CREATE DATABASE notification_db;
   CREATE DATABASE stats_db;
   ```

   (或 `createdb auth_user_db` × 4)。`.env` 的 `POSTGRES_USER/PASSWORD/PGHOST/PGPORT`
   必须与实例一致。
3. **Redis 7**:启动,并把口令设为 `.env` 的 `REDIS_PASSWORD`
   (如 `redis-server --requirepass '<口令>'`;或改配置 `requirepass`)。
4. **RabbitMQ 3.x**:启动,创建与 `.env` `RABBITMQ_USER/PASSWORD` 一致的用户并给 `Administrator`
   标签(`rabbitmqctl add_user … ; rabbitmqctl set_user_tags … administrator`)。
5. **Nacos(standalone)**:与项目根 `start.sh` 相同方式:

   ```bash
   sh ~/Downloads/nacos/bin/startup.sh -m standalone     # 单机 + 内置 Derby
   curl http://127.0.0.1:8848/nacos                      # 就绪探测
   ```

   `.env` 的 `NACOS_ADDR` 默认 `127.0.0.1:8848`(与各 `application.yml` 一致)。

### 5.2 构建并启动

```bash
cd TaskFlow/deploy
./build.sh                 # 产出 deploy/artifacts/**
cp .env.example .env       # 修改敏感项(见 §3.3)

./scripts/start-all.sh     # 启动 5 个后端服务(env 由 .env 注入;日志 deploy/logs/*.log)
./scripts/stop-all.sh      # 停止
```

`start-all.sh` 会:按 pid 幂等去重、用 `SPRING_*` 环境变量把各服务指向你 `.env` 配置的
中间件地址/口令、后台 `java -jar` 启动并写 pid 文件、最后按
`/<前缀>/api/v1/ping`(期望 `"code":0`)轮询就绪(最长约 90 秒)。

### 5.3 前端:Nginx 托管

1. 构建产物 dist 已在 `deploy/artifacts/frontend-dist`;
2. 编辑 `deploy/nginx.conf`:**把 `root` 改成你机器上 dist 的实际绝对路径**(默认示例
   `/opt/taskflow/deploy/artifacts/frontend-dist`;若网关不在本机,同步改 `proxy_pass`);
3. 安装:

   ```bash
   sudo cp TaskFlow/deploy/nginx.conf /etc/nginx/conf.d/taskflow.conf
   sudo nginx -t && sudo systemctl reload nginx     # (macOS: nginx -t && nginx -s reload)
   ```

该配置已含:`server 8080` 托管 dist、`/auth/ /task/ /notification/ /stats/` 反代
`127.0.0.1:8000`、`client_max_body_size 25m`、gzip、SPA history 回退。

### 5.4 验证

```bash
curl http://127.0.0.1:8000/auth/api/v1/ping        # 网关直达
curl http://127.0.0.1:8080/                          # 前端(经 Nginx)
# 浏览器打开 http://<host>:8080
```

> 生产建议用 systemd 托管每个 `java -jar`(可参照 `start-all.sh` 的环境注入方式,把
> `EnvironmentFile=deploy/.env` + `ExecStart=java -jar …` 写进 unit,一个服务一个 unit)。

---

## 6. 默认账号与初始化数据

| 账号 | 初始密码 | 说明 |
|---|---|---|
| `admin` | `Admin@123` | 系统管理员;Flyway V2 种子数据创建 |

- 种子用户 `must_change_password = TRUE`,**首次登录强制改密**,改密后旧令牌全部失效;
- 管理员在“用户管理”新建/重置密码时,系统返回一次随机初始密码,该用户下次登录同样强制改密;
- 生产上线前,建议在首次登录后立即修改 admin 密码;更稳妥的做法是在首次启动前替换
  `auth-user-service` 的 V2 种子 SQL 中的 BCrypt 哈希为自生成的 `Admin@123` 新哈希。

---

## 7. 备份与恢复

### 7.1 每日备份

```bash
cd TaskFlow/deploy
./scripts/backup.sh
```

- `pg_dump -Fc`(自定义格式)备份 `auth_user_db / task_db / notification_db / stats_db`;
- 附件目录按 `ATTACHMENT_ROOT` 打包(Compose 部署时指向宿主机 `deploy/data/attachments`);
- 每次备份写入 `deploy/backups/<时间戳>/`,含 `MANIFEST.txt` 摘要;
- 按 `BACKUP_KEEP_DAYS`(默认 **7**)滚动删除,满足 **RPO 24h**;
- 依赖 `pg_dump`(macOS: `brew install libpq`,并把 `/opt/homebrew/opt/libpq/bin` 加 PATH)。

加入 crontab(每天 02:15,输出到日志):

```cron
15 2 * * * cd /opt/taskflow/deploy && ./scripts/backup.sh >> logs/backup.log 2>&1
```

### 7.2 恢复

**数据库**(示例恢复 `auth_user_db`):

```bash
pg_restore -h 127.0.0.1 -p 5432 -U postgres -d auth_user_db \
  --clean --if-exists deploy/backups/20260909_021500/auth_user_db.dump
```

四个库分别恢复(`task_db / notification_db / stats_db` 同理)。恢复后建议重启对应服务
(Flyway 校验通过即可,无需手工迁移)。

**附件**:

```bash
tar xzf deploy/backups/20260909_021500/attachments.tar.gz -C /   # 解回原目录结构
# 或指定位置:
mkdir -p <ATTACHMENT_ROOT> && tar xzf .../attachments.tar.gz -C <ATTACHMENT_ROOT> --strip-components=1
```

恢复后重启 `task-service` 即可。

---

## 8. Nacos 配置说明

- 本项目五个服务**只用 Nacos 做服务注册发现**(`spring.cloud.nacos.discovery.*`),
  网关 `lb://` 负载均衡依赖它;
- compose 默认镜像 `nacos/nacos-server:v2.4.3`,单机 `MODE=standalone`,**内置 Derby**
  (等价 `-m standalone`),无需外部数据库;
- 控制台(免登录,默认鉴权关):`http://<host>:8848/nacos` —— 服务列表应出现 5 个服务;
- 各服务 `application.yml` 写死的 `127.0.0.1:8848` 在 Compose 内被 environment 覆盖为
  服务名 `nacos:8848`;裸机则由 `start-all.sh` 用 `.env` 的 `NACOS_ADDR` 注入;
- **生产开启鉴权**:`.env` 设 `NACOS_AUTH_ENABLE=true` 并填
  `NACOS_AUTH_TOKEN`(Base64 强随机串)、`NACOS_AUTH_IDENTITY_KEY/VALUE`,同时给服务注册
  配账号(compose environment / 裸机 env):
  `SPRING_CLOUD_NACOS_DISCOVERY_USERNAME=nacos`、`SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD=<密码>`,
  `SPRING_CLOUD_NACOS_CONFIG_USERNAME/PASSWORD` 同理(仅开启了 config starter 才需要);
- 备选镜像 `nacos/nacos-server:v3.2.4`:同样 `MODE=standalone` + 内置 Derby,但 **v3 控制台
  默认端口移到 8080**,与前端 8080 冲突,需 `NACOS_CONSOLE_PORT` 调整或改前端映射;且
  v3.3+ 客户端 API 鉴权默认开启、对 spring-cloud-alibaba 2023.0.1.0 自带的 nacos-client
  2.3.x 属新大版本,故本包默认 v2.4.3(与客户端版本匹配、行为与本项目开发环境一致),未实测 v3。

---

## 9. 安全清单(生产必读)

1. **JWT_SECRET【必改】**:五个服务共用同一份,不一致将导致令牌校验失败;
   生产用 `openssl rand -base64 48` 生成;
2. **数据库口令【必改】**:`POSTGRES_PASSWORD`(root 是开发默认)、`REDIS_PASSWORD`、
   `RABBITMQ_PASSWORD`(建议连 `RABBITMQ_USER` 一起改掉默认用户名);
3. **SMTP 凭据**:发信若启用,`SMTP_USERNAME/SMTP_PASSWORD` 走 `.env`,不进代码;
4. **密钥只经环境变量注入、不入镜像**:镜像内仅 jar + JRE,无任何密钥/配置文件;
   `deploy/.env` 已被 `.gitignore` 排除,严禁提交;换机器/换人部署用 `.env.example`
   重新生成;
5. **Nacos**:控制台与 8848/9848 仅在可信内网开放;对外暴露请按 §8 开启鉴权;
6. **端口收敛**:对外只暴露 8080(Web)与 8000(网关,如需直连 API);5432/6379/5672/
   15672/8848 等仅限本机/内网,必要时在防火墙层限制来源;
7. **依赖升级**:生产环境请关注 PostgreSQL/Redis/RabbitMQ/Nacos 官方安全公告并及时升级。

---

## 10. 环境变量总表(见 `.env.example`,此处为说明)

| 变量 | 默认 | 说明 |
|---|---|---|
| `JWT_SECRET` | (空→开发默认) | 【必改】五服务共用 JWT 密钥 |
| `TZ` | `Asia/Shanghai` | 时区(compose 容器级) |
| `POSTGRES_USER` | `postgres` | PostgreSQL 超级用户 |
| `POSTGRES_PASSWORD` | `root` | 【必改】PostgreSQL 口令(compose/裸机脚本共用) |
| `PGHOST` / `PGPORT` | `127.0.0.1` / `5432` | 裸机脚本连库地址(备份也用它) |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | Redis 地址 |
| `REDIS_PASSWORD` | `root` | 【必改】Redis 口令 |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `taskflow` / `taskflow` | 【必改口令】RabbitMQ 应用用户 |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `127.0.0.1` / `5672` | RabbitMQ 地址 |
| `NACOS_ADDR` | `127.0.0.1:8848` | 裸机时各服务注册地址(compose 内部固定 `nacos:8848`) |
| `NACOS_AUTH_ENABLE` | `false` | Nacos 鉴权开关(生产按 §8 开启) |
| `NACOS_AUTH_TOKEN` / `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE` | 空 | 开启鉴权时必填 |
| `SMTP_HOST` / `SMTP_PORT` | `localhost` / `25` | 邮件服务器(notification-service,可选) |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | 空 | SMTP 凭据 |
| `SMTP_AUTH` / `SMTP_STARTTLS` | `false` / `false` | SMTP 认证/TLS |
| `SMTP_TIMEOUT` | `5000` | SMTP 超时(ms);无 SMTP 时快速失败进重试 |
| `MAIL_FROM` | `taskflow@example.com` | 发件人 |
| `ATTACHMENT_ROOT` | `./data/attachments` | 附件目录(相对路径以 deploy/ 为基准;compose 容器内固定 `/taskflow/attachments` 并绑定宿主 `deploy/data/attachments`) |
| `BACKUP_ROOT` | `./backups` | 备份根目录 |
| `BACKUP_KEEP_DAYS` | `7` | 备份保留天数(RPO 24h 下 ≥ 1) |

---

## 11. 常见问题(FAQ)

- **端口被占用(本机已跑 brew 中间件)**:
  开发机若已用 brew 跑 PostgreSQL/Redis/RabbitMQ/Nacos,与 compose 映射冲突。
  方案:① 只跑裸机方式(直接用系统中间件);② 停宿主中间件后 `docker compose up`;
  ③ 改 compose 端口映射(如 `127.0.0.1:5433:5432`),并同步把中间件 healthcheck 相关
  配置与 `.env` 的 `PGPORT` 等改掉。
- **`docker compose build` 报 COPY 找不到 jar/dist**:没先跑 `./build.sh`,或构建机
  与执行机不是同一份代码(artifacts 不入库)。先 `./build.sh`。
- **服务启动后 Nacos 里看不到实例**:确认 `.env` 的 `NACOS_ADDR`(裸机)可达;
  compose 内确认 `docker compose ps` 中 nacos healthy/up,再看服务日志
  (`docker compose logs <svc>`)。
- **首启 gateway 返回 502 / 服务未就绪**:服务注册需要几秒;等 `docker compose ps`
  全部 healthy 后重试;网关日志出现 `No servers available` 说明对应服务尚未注册。
- **前端 404 / 页面刷新 404**:确认 Nginx 配置含 `try_files … /index.html`
  (两版 nginx.conf 均已内置)。
- **附件上传失败**:检查上传大小(前后端均 25m/20MB 上限一致)与 `ATTACHMENT_ROOT`
  目录可写权限。
- **邮件发不出但服务正常**:本机无 SMTP 时 `SMTP_TIMEOUT=5000` 快速失败进重试,属预期;
  配置真实 SMTP 后重启 `notification-service`。
- **改密后旧令牌仍可用几秒**:JWT 有 TTL 缓存/黑名单延迟,属正常;生产按需缩短 TTL
  (`taskflow.jwt.ttl`,默认 PT2H)。

---

## 12. 验证情况(未实测点)

本包为**文件化交付**,已做如下尽力校验(2026-09-09):

- ✅ `docker compose config` 语法/插值校验通过(Docker Compose v5.3.1);
- ✅ `bash -n` 通过:`build.sh`、`scripts/start-all.sh`、`scripts/stop-all.sh`、
  `scripts/backup.sh`;
- ✅ 在真实项目上验证了构建关键路径:`mvn -pl auth-user-service package
  spring-boot:repackage` 产出含 `BOOT-INF/` 的可执行 fat jar(父 POM 无
  spring-boot-starter-parent 时 repackage 需显式执行——`build.sh` 已按此处理);
- ⚠️ 未整跑 `docker compose up`(未启动 Docker daemon;需拉取 6+ 镜像并完整启动,
  时间成本高)——compose 文件、Dockerfile、脚本路径均与项目真实结构逐项核对;
- ⚠️ 未实测:镜像拉取/构建、Nacos 鉴权开启场景、SMTP 真实发信、备份/恢复端到端演练;
  未实测 Nacos v3.2.4 镜像(默认给 v2.4.3,理由见 §8)。

路径一致性依据(核对过的项目事实):

- 服务/库/端口:`application.yml`(gateway 8000;auth 8081+`auth_user_db`;task
  8082+`task_db`+`ATTACHMENT_ROOT`;notification 8083+`notification_db`+SMTP 占位;stats
  8084+`stats_db`);中间件默认 `127.0.0.1` 与 `.env`/脚本默认值一一对应;
- 前端:axios `baseURL:'/'` 发 `/auth|task|notification|stats/api/...`,Nginx 反代前缀与
  网关路由(`/auth/api/v1/**` 等)匹配,SPA 用 `createWebHistory` 需 try_files 回退;
- 构建:父 POM 无 spring-boot-starter-parent、common 为纯依赖模块、服务 jar 名
  `<svc>-1.0.0.jar`、Flyway 迁移位于各服务 classpath `db/migration`(V2 种子含
  admin/Admin@123 + 强制首登改密)。
