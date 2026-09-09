# TaskFlow 前端 UI/UX 代码级走查报告

> 走查日期:2026-09-09 | 范围:frontend/src(views/components/styles),静态代码级审读(非截图级)

## 总体结论

主题令牌体系(theme.scss teal 主色 + 文字/边框/表格令牌)与**结构骨架是统一的**(页面标题区 page-head、白色容器、表格行高 48、表头浅底均一致)。问题集中在四类:**自绘控件残留**、**样式重复实现**、**字号/标题色散点**、**颜色硬编码双轨**。均非功能缺陷,按优先级排序如下。

## A. 自绘控件残留(建议统一)

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| A1 | **任务列表"范围"筛选(全部/我创建/指派给我)仍是自绘 `.segmented`(28px 原生 button)**——全站分段控件里它已是最后一个是自绘的(统计/通知已改 el-radio-button) | `views/task/TaskListView.vue` ~442/862 | 换成 el-radio-group+el-radio-button(与统计/通知一致) |
| A2 | 铃铛图标、抽屉 × 关闭是原生 `<button>`(图标触发器,非文字按钮) | `NotificationBell.vue:134`、`TaskDetailDrawer.vue:296` | 视觉统一(尺寸/悬停态对齐 Element icon-button),可保留原生但把样式并入规范 |
| A3 | 行内"操作文字链"在 3+ 处重复实现 `.op-link`(任务列表 13px、UserTab 拷贝版、NotificationBell link 按钮) | TaskListView / UserTab / NotificationBell | 抽公共类 `.tf-op-link` 放 theme.scss 统一字号 13px/行高/悬停,删重复定义 |
| A4 | el-button 残留 `size="small"`×2、`link small`×1 | 各页(表格内紧凑/弹层小按钮) | 核对是否刻意;若为紧凑场景保留并注释,否则升默认 |

## B. 字体与排版散点(低)

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| B1 | **页面标题色两套**:任务/日历 h1 `#12242E`,统计 `.page-title` `#1F2D3D`(正文 token);通知/权限页可能又一套 | TaskListView:805、StatsOverviewView:520 | 标题统一一种深色(推荐 #12242E),或全部走 `var(--el-text-color-primary)` |
| B2 | 字号散点:12px×41/13px×48/14×12/15×3/16×6/18×1/20×4/28×3——主体规则(表内 13、按钮 14、辅助 12、标题 20、KPI 28)基本成型,个别 15px/18px 需逐个核对归属 | 全站扫描 | 建立字号层级注释;核对 15/18 用法 |
| B3 | tabular-nums 只用在 `.tf-num`(时间/编号已用),KPI/负载数字页是否都挂 .tf-num | theme.scss | KPI 卡/统计数字统一加,防跳动 |
| B4 | 全站无 letter-spacing/字重体系文件;font-weight 500/600 混合 | — | 已有 UI 规范文档,如需可在 theme 固化 |

## C. 间距/容器/空态(低)

| # | 问题 | 建议 |
|---|---|---|
| C1 | 空态类七八种:empty-tip×5 / chart-empty×4 / n-empty / notice-empty / comment-empty / day-empty / timeline-empty——视觉大概率不统一 | 抽 `.tf-empty` 公共组件(图标+文案+可选操作),各页引用 |
| C2 | 容器圆角:控件 6px(theme base)vs 卡片 8px(tf-card)是有意分层;需核对各页自绘面板(日历/抽屉头)是否都按"容器 8px"落实 | 逐页核对,不一致处统一 8px |
| C3 | page-head 对齐:任务/日历 align-items:flex-end,统计 center | 统一为同一种(视觉参照设计稿为准) |

## D. 色彩硬编码双轨(低,工程性)

| # | 问题 | 建议 |
|---|---|---|
| D1 | 高频硬编码与 token 同值双轨:#8A97A8×53、#5E6D82×34、#0E7C86×32…(值和 theme.scss 令牌一致,但直接写死) | 渐进替换为 `var(--el-text-color-secondary)` 等;工作量中等,改主题时收益明显 |
| D2 | 状态色在若干文件重复(成功#2F9E6E/警告#D9822B/危险#C8493F 与令牌一致) | 同 D1,建议抽 `--tf-*` 或直接用 el token |

## 建议处理顺序

1. **快赢(低风险,一次提交)**:A1 范围分段统一、B1 标题色统一、B3 统计数字加 tabular-nums、A3 抽公共 `.tf-op-link` 删重复
2. **视觉抽查**:C1 空态组件、C2 面板圆角核对——需要你浏览器截图确认哪些空态样式走样
3. **工程优化(可选)**:D1 颜色变量化(改动面大,建议闲暇做)
