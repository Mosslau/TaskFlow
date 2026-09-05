# 任务管理系统 · 开发技能选型说明（skill-dev）

> 技术栈：**Vue3（前端）+ Spring 微服务（后端）+ PostgreSQL（数据库，已选定）+ 企业级页面设计**
> 安装位置：`.workbuddy/skills/`（**项目级**，随项目目录走，团队共享）
> 维护人：Work · 最后更新：2026-09-05

---

## 一、安装命令（已执行 ✅）

以下 8 条命令已全部执行完成，技能安装在 `.workbuddy/skills/`：

```bash
npx skills add git@github.com:obra/superpowers --skill brainstorming
npx skills add git@github.com:anthropics/skills --skill frontend-design
npx skills add git@github.com:vuejs-ai/skills --skill vue-best-practices
npx skills add git@github.com:github/awesome-copilot --skill java-springboot
npx skills add git@github.com:wshobson/agents --skill microservices-patterns
npx skills add git@github.com:wshobson/agents --skill postgresql-table-design
npx skills add git@github.com:planetscale/database-skills --skill mysql
npx skills add git@github.com:wshobson/agents --skill api-design-principles
```

> 说明：`npx skills add` 官方 CLI 不识别 WorkBuddy 的项目级目录，安装后需确认技能最终落在 `.workbuddy/skills/`（本项目已核实全部到位）。
> 换机/重装时，也可用 `git clone --depth 1 --filter=blob:none --sparse git@github.com:<owner/repo>.git` 稀疏克隆后直接拷贝对应技能目录，效果相同。
> 数据库选型已定为 **PostgreSQL**：`mysql` 技能已按维护约定从三处技能目录（`.workbuddy/skills/`、`.dsh/skills/`、`.agents/skills/`）及 `skills-lock.json` 移除；上方命令列表保留其安装记录，重装时无需再执行 mysql 那条。

---

## 二、技能清单总览

| # | 技能 | 来源仓库 | 生态安装量 | 覆盖环节 | 一句话定位 |
|---|------|---------|-----------|---------|-----------|
| 1 | `brainstorming` | obra/superpowers | 351.4K | 需求/设计前置 | 任何创作前先头脑风暴，收敛出规格说明 |
| 2 | `frontend-design` | anthropics/skills | 854.7K | 页面设计 | Anthropic 官方 UI 视觉设计指导 |
| 3 | `vue-best-practices` | vuejs-ai/skills | 38.1K | 前端开发 | Vue3 组合式 API 最佳实践（23 篇参考） |
| 4 | `java-springboot` | github/awesome-copilot | 19.9K | 后端开发 | GitHub 官方 Spring Boot 开发规范 |
| 5 | `microservices-patterns` | wshobson/agents | 11.6K | 架构设计 | 微服务拆分、服务边界、事件驱动模式 |
| 6 | `postgresql-table-design` | wshobson/agents | 24.9K | 数据库 | PostgreSQL 表结构 / 索引 / 约束设计 |
| 7 | `api-design-principles` | wshobson/agents | 27.7K | 接口设计 | REST / GraphQL API 设计原则 + 检查清单 |

> 数据库已选定 PostgreSQL；原第 7 套 `mysql`（planetscale/database-skills）已移除。

---

## 三、逐个技能解释

### 1. brainstorming（需求前置）
- **干什么**：在做任何功能/产品创作**之前**，强制先头脑风暴——发散场景、提问澄清、收敛方案，产出规格说明文档（spec）；可启动本地可视化伴侣（visual companion）做图形化推演。
- **为什么选它**：企业级系统最忌"上来就写代码"。任务管理系统的四个模块（总览统计/任务列表/日程/权限管理）重构为 Vue3 + Spring 前，先用它把需求与边界钉死。
- **文件**：8 个（含 `scripts/` 本地预览服务，仅绑 127.0.0.1 + token 鉴权，已审计良性）。

### 2. frontend-design（页面设计）
- **干什么**：提供"有设计意图"的视觉指导——配色、排版、层级、动效，避免生成平庸模板页面。
- **为什么选它**：854.7K 安装量、Anthropic 官方出品，生态中页面设计类头部技能；直接对应"页面设计"硬需求。
- **文件**：SKILL.md + LICENSE（纯文档型）。

### 3. vue-best-practices（前端开发）
- **干什么**：强制推荐 Composition API，覆盖 SFC 写法、组件 slots/teleport/suspense、指令、动画、列表性能等 23 个专题。
- **为什么选它**：vuejs-ai 出品、38.1K 安装量，Vue3 开发事实标准；原型 HTML → Vue3 工程重构全程适用。
- **文件**：23 个（SKILL.md + references/ 22 篇专题参考）。

### 4. java-springboot（后端开发）
- **干什么**：Spring Boot 应用开发最佳实践（分层、配置、Web、数据访问、测试等）。
- **为什么选它**：GitHub 官方 awesome-copilot 仓库出品，来源最可信。
- **文件**：单 SKILL.md（轻量常驻规范）。

### 5. microservices-patterns（架构设计）
- **干什么**：微服务架构模式库——服务边界划分、事件驱动、分布式数据、服务间通信。
- **为什么选它**：后端定为"Spring 微服务"，任务/统计/日程/权限等服务怎么拆需要模式依据。
- **文件**：SKILL.md + references/details.md。

### 6. postgresql-table-design（数据库）
- **干什么**：PG 专属 schema 设计：数据类型、索引、约束、性能模式。
- **为什么选它**：数据库已选定 PostgreSQL；原备选 `mysql`（PlanetScale 官方技能，InnoDB 表设计/索引/调优等 19 个专题）已随选型移除。

### 8. api-design-principles（接口设计）
- **干什么**：REST 与 GraphQL API 设计原则，附设计检查清单与 REST API 模板。
- **为什么选它**：前后端分离的契约层——Vue3 前端调 Spring 后端，接口规范必须先统一。
- **文件**：6 个（含 assets/rest-api-template.py 模板资产，非可执行逻辑）。

---

## 四、技能 ↔ 项目模块映射

| 项目模块 / 环节 | 主要启用技能 |
|---|---|
| 需求收敛、方案评审 | brainstorming |
| 原型 HTML → Vue3 页面重构 | frontend-design + vue-best-practices |
| 微服务拆分（任务/统计/日程/权限服务） | microservices-patterns + java-springboot |
| 前后端 API 契约 | api-design-principles |
| 权限管理模块（RBAC 落地） | java-springboot（Security）+ api-design-principles |
| 库表设计（任务/用户/角色/权限矩阵/操作轨迹） | postgresql-table-design |

---

## 五、维护约定

| 事项 | 做法 |
|---|---|
| 更新技能 | 重新执行上方安装命令覆盖；CLI 安装方式下可用 `npx skills update` |
| 新增技能 | 用 `find-skills` 技能或 `npx skills find <关键词>` 检索生态 |
| 安全审计 | **新装任何第三方技能前**，先审 SKILL.md 与 scripts/（危险模式：base64/eval/外联/读密钥/删文件），P2 及以上才入库 |
| 数据库选型确定 | 已完成 ✅：选定 PostgreSQL，mysql 技能已从三处目录与 `skills-lock.json` 移除 |

---

## 六、安全审计记录（2026-09-05）

| 技能 | 文件数 | 可执行脚本 | 结论 |
|---|---|---|---|
| brainstorming | 8 | scripts/（本地预览服务：仅 127.0.0.1 + token 鉴权 + 用户确认后开浏览器，无外联上传） | ✅ P2 良性 |
| frontend-design | 2 | 无 | ✅ P2 纯文档 |
| vue-best-practices | 23 | 无 | ✅ P2 纯文档 |
| java-springboot | 1 | 无 | ✅ P2 纯文档 |
| microservices-patterns | 2 | 无 | ✅ P2 纯文档 |
| postgresql-table-design | 2 | 无 | ✅ P2 纯文档 |
| ~~mysql~~ | 19 | 无 | ✅ P2 纯文档（已随数据库选型移除） |
| api-design-principles | 6 | assets/rest-api-template.py（模板资产，非执行逻辑） | ✅ P2 |
