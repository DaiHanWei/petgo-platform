# PRD · TailTopia 直接安装冷启动首屏重做（P0）

| 项 | 值 |
|---|---|
| 版本 | v1.0（2026-08-19） |
| 状态 | draft（待产品拍板 OQ 后转 ready-for-dev） |
| 依据 | `tailtopia_posthog_diagnosis.md` + AppsFlyer 归因拉取（2026-08-19） |
| 范围 | 前端 App 游客首屏 + 可能的公开内容取数；A/B 实验 |
| 负责人 | Shawn（产品决策）/ 研发（实现） |
| 关联 | 本仓库同目录 `PRD.md`（留存修复 v1.0）§2 已将此列为 P0 下一份 PRD |

---

## 0. 一句话结论

> 直接安装（当前唯一付费获客形态）注册率 **0.6%**，对比分享落地 **70%**。差异的根因不是「流量差」，是**首屏**：直接安装用户看到的是明确标着「✨ Contoh（示例）」的**假演示页**，且价值主张被埋在滚动之后——3 秒内既看不懂「这是什么」，也感受不到「有真人真的在用」。本 PRD 只修首屏，**不碰 Tab 顺序与产品定位**。

---

## 1. 背景与问题（数据 + 代码实读）

### 1.1 数据

| 人群 | 注册率 | 建档率 | 说明 |
|---|---|---|---|
| 分享落地（朋友的日记链接） | **70.3%** | 59.0% | 真实社交证明 |
| 直接安装（无分享落地） | **0.6%** | 7.5% | 当前唯一付费获客形态 |
| TikTok campaign（8/1–8/12） | 3.9%（18/462） | 2.6%（12/462） | 428 次付费安装换 12 建档、1 下单 |

### 1.2 现状首屏（代码实读 `diary_guest_page.dart`）

直接安装游客（`AppUserState.guest`）冷启动落 `/profile` → `DiaryGuestPage`，自上而下是：

1. **`✨ Contoh`（示例）条** —— 第一眼就宣告「这是假的」。
2. 假宠 `Mochi` 的页头 + **5 条演示时间线**（`DiaryDemoData` 静态数据，零网络）。
3. 情感化标题「Every day with them is worth keeping」+ 一句话说明 —— **被埋在 5 条条目之后，需滚动才能看到**。
4. 底部常驻 CTA「Start your pet's story」。

> 定位约束（必须尊重）：`user_state.dart` / FR-78 / AD-8 已决策「游客落 Diary（工具 + 私密陪伴优先），**不以信息流优先**」；代码注释明确「不以执行细节的短期波动推翻定位决策」。本 PRD 在此约束内优化首屏，不改 Tab 顺序。

### 1.3 根因假设（两条，均可实验验证）

- **H1（示例杀死欲望）**：首屏标「Contoh」读作「这是假演示 / 营销模板」，不产生「我也想要」的欲望。对照证据：分享落地用户看到的是朋友**真实**日记，转化 70%。
- **H2（价值主张被埋）**：「这是干嘛的 + 为什么留下」在 5 条示例之后，首屏 3 秒无解释、无方向。

---

## 2. 目标与非目标

### 目标

1. 让直接安装用户在首屏 3 秒内看懂「这是什么 + 我为什么要留下」。
2. 用**真实社交证明**替换「示例」，消除「这是假演示」的信号。
3. 用 A/B 实验**验证**假设，而不是拍脑袋改版。

### 非目标（Non-Goals）

- ❌ 不改 Tab 顺序、不改「Diary-first」定位（FR-78/AD-8 决策）。
- ❌ 不新增登录墙、不复活 FR-0H 提示条（AD-15 Rule 3）。
- ❌ 不引入后端新中间件 / MQ / 缓存（CLAUDE.md 硬护栏）。
- ❌ 不碰分享落地链路（那是 `PRD.md` FR-2 的职责，但两处 CTA 需复用同一门控）。

---

## 3. 成功指标（Success Metrics）

| 编号 | 指标 | 现状基线 | 目标 | 观察窗口 |
|---|---|---|---|---|
| SM-1 | 直接安装 → 注册转化率 | 0.6%（全版本）/ 3.9%（TikTok） | ≥ 10% | 实验上线后按新 cohort 2 周 |
| SM-2 | 直接安装用户 D1 留存 | 7.2% | ≥ 15% | 同上 |
| SM-3 | 首屏价值主张曝光 → 点 CTA 转化 | 待测 | 显著高于 Control | 实验窗口 |
| SM-4 | 分享落地注册率（护栏指标） | 70.3% | 不下降 | 全程监控，防首屏改动误伤分享链路 |

> 口径纪律：按 `person_id` 去重、只看成熟 cohort；「直接安装」= 首次会话未落在分享日记页（`diary_guest_page_viewed` 代理口径，与诊断一致）。

---

## 4. 功能需求

### FR-1 价值主张前置（above-the-fold）

**现状**：情感化标题 + 一句话说明位于 5 条示例时间线**之后**；首屏第一眼是「✨ Contoh」条 + 假宠页头。

**需求**：把「这是什么 + 为什么留下」提到首屏可视区（above-the-fold），示例/真实内容下沉为「proof」（证明）。

- 首屏顺序（目标）：**一句话价值主张标题 + 副文案 + 主 CTA**（或 CTA 紧邻标题）→ 向下滚动才是内容证明。
- 价值主张文案沿用既有 `diaryGuestPitchTitle` / `diaryGuestPitchBody` / `diaryGuestPrimaryCta`，**不新增 i18n 键**，仅重排布局。
- 保持「种草页而非登录墙」姿态：主 CTA 仍不含「登录」字样，仍无「已有账号？登录」次入口。

**验收标准（AC）**：

- [ ] AC1（L2 视觉）：直接安装游客首屏（不滚动）即可看到价值主张标题 + 副文案 + 主 CTA。
- [ ] AC2（L0 单测）：首屏布局变化不新增埋点事件名（复用 T-3/T-4），`flutter analyze` 零警告。
- [ ] AC3（护栏）：Tab 顺序、落地矩阵（`user_state.dart landingLocation`）零改动。

---

### FR-2 用真实社交证明替换「示例」内容

**现状**：全部为 `DiaryDemoData` 静态示例，顶部显式标「✨ Contoh」。

**需求**：种草页的内容区改为**真实精选日记**（真实用户已公开的 GROWTH_MOMENT 帖），消除「假演示」信号，制造「有真人真的在用」的社交证明。

- 数据源：复用**现有公开内容接口**（游客本可浏览 Social Feed，接口已公开），取少量（建议 3–5 条）精选 GROWTH_MOMENT 帖。
- 精选策略（OQ-1 二选一）：① 后端加「精选/置顶」标记位（最小 Flyway + 后台勾选）；② 前端取「最新已发布 GROWTH_MOMENT」兜底（零后端改动，先跑通实验）。
- 点击行为：真实内容条目 → 打开真实 `/content/:id`（复用 `PRD.md` FR-2 的游客 CTA），不再用 `DiaryDemoDetailPage`。
- 隐私：仅展示 `PUBLISHED` 且本就公开的内容，**绝不**拉取私密/健康数据；头像/昵称沿用 Feed 已有的脱敏投影（`authorDeleted` 匿名化等）。

**验收标准（AC）**：

- [ ] AC1（L2 视觉）：直接安装游客首屏看到的是真实宠物日记（非「✨ Contoh」演示），且无「示例」字样。
- [ ] AC2（L2 视觉）：点击真实内容 → 进 `/content/:id`，详情页内可见 `PRD.md` FR-2 的游客创建档案 CTA。
- [ ] AC3（隐私）：仅公开内容；注销作者匿名化（复用 NFR-8 投影）。
- [ ] AC4（护栏）：无新中间件 / 缓存；若需「精选」标记，走最小 Flyway，不引入 Redis。

---

### FR-3 A/B 实验框架（验证 H1/H2）

**需求**：用 PostHog **Experiment**（feature flag 变体）跑三臂，以数据裁决首屏方案，避免拍脑袋。

| 变体 | 内容 | 目的 |
|---|---|---|
| Control | 现状 Demo 种草页（✨ Contoh） | 基线 |
| Variant A | 价值主张前置 + 真实精选日记（FR-1 + FR-2） | 验证 H1 + H2（**默认推荐**） |
| Variant B | 直接落 Social 真实 Feed（信息流优先） | **对照臂**，用于验证「Diary-first 定位」是否真的比信息流优先更能转化新客；**默认不预上线**，仅收数据供产品决策 |

- 分流键：`distinctId`；目标事件：`diary_guest_create_profile_cta_tapped` → `signup_succeeded`（复用既有事件，变体经 flag 分列）。
- 实验护栏：`sessionReplay` 关闭的变体不受影响；归因属性（`PRD.md` FR-3）需先落地，否则无法按渠道看变体效果。

**验收标准（AC）**：

- [ ] AC1（L0 单测）：feature flag 变体切换不引入新事件名、不破坏 T-3/T-4/T-7。
- [ ] AC2（看板）：PostHog Experiment 出现三臂的 `install → registration` 转化对比。
- [ ] AC3（护栏）：Variant B 仅在实验窗内收数据，不在实验结论出来前作为默认落地。

---

## 5. 埋点事件清单

> 本 PRD **不新增事件**，复用既有事件 + flag 变体分列，最小化命名测试负担：

| 事件 | 用途 | 分列方式 |
|---|---|---|
| `diary_guest_page_viewed`（T-3） | 首屏曝光（实验分母） | feature flag 变体 |
| `diary_guest_create_profile_cta_tapped`（T-4） | 点创建档案 CTA | flag 变体 + `source` |
| `signup_succeeded`（T-7） | 注册成功（实验分子） | flag 变体 + `entry_source` |

> ⚠️ 若 FR-2 需区分「真实内容条点击」与「CTA 点击」，新增 `diary_guest_real_item_tapped`（`source` = item），否则不新增。倾向先不加，用现有 `diary_guest_create_profile_cta_tapped` 的 `source` 体系足够。

---

## 6. 护栏与合规自查

| 护栏（来源） | 是否触碰 | 处理 |
|---|---|---|
| 不改 Tab 顺序 / 定位（FR-78/AD-8） | FR-1/FR-2 只改种草页内容与布局 | `user_state.dart landingLocation` 零改动 |
| 不复活 FR-0H / 不新增提示条（AD-15 Rule 3） | 否 | 首屏是整页重排，非提示条 |
| 禁新中间件 / MQ / 缓存（CLAUDE.md） | FR-2 若需精选标记 | 最小 Flyway + 后台勾选，禁 Redis |
| 隐私：只展示公开内容、匿名化（NFR-8） | FR-2 真实内容 | 仅 PUBLISHED；注销作者匿名化 |
| 事件命名受单测约束（`v112_events_test.dart`） | 本 PRD 不新增事件 | 无需改命名测试 |
| 「不以短期转化波动推翻定位决策」 | Variant B 是数据对照 | 明确标记为实验臂，不默认上线 |

---

## 7. 开放问题（OQ）

| 编号 | 问题 | 倾向 | 责任方 |
|---|---|---|---|
| OQ-1 | FR-2 真实内容取数：后端「精选」标记（新增 Flyway）还是前端「最新 GROWTH_MOMENT」兜底？ | 先用**前端兜底**跑通实验（零后端改动、最快上线），实验有效再上「精选」标记 | 研发 + 产品 |
| OQ-2 | 精选真实日记若混入低质/违规内容，是否需要运营审核？ | V1 复用内容举报审核队列（既有多态），不新增审核环节 | 运营 |
| OQ-3 | Variant B（Social 优先）是否本轮就跑？ | 建议本轮一起收数据（成本极低，flag 已具备），但不作为默认；结论出来后产品再议 | 产品（Shawn 拍板） |
| OQ-4 | SM-1 目标 10%（从 0.6% 提升 ~17 倍）是否现实？ | 分享落地已证明 70% 可达，10% 是「首屏修复 + 社交证明」的保守目标，2 周后按实测校准 | 产品 |

---

## 8. 依赖与前置

1. **归因先行**：`PRD.md` FR-3（AppsFlyer → PostHog person property）需先落地，否则无法按渠道（TikTok vs 自然）看冷启动实验效果。
2. **Session Replay**：`PRD.md` FR-4 开启后，可对「直接安装首屏 3 秒流失」做真实回放验证，替代靠事件猜。
3. **TikTok 成本**：仍待 TikTok Ads Manager 的 `spend`（算真实 CPI），但不阻塞本 PRD 开发。

---

## 9. 验证口径（PostHog Insight 定义）

| Insight | 定义 |
|---|---|
| SM-1 | Funnel（按 flag 变体分列）：`Application Installed` → `diary_guest_page_viewed` → `diary_guest_create_profile_cta_tapped` → `signup_succeeded`，限定「直接安装」cohort |
| SM-2 | Retention：直接安装 cohort 的 D1，按 flag 变体分列 |
| SM-3 | Funnel：`diary_guest_page_viewed` → `diary_guest_create_profile_cta_tapped`（首屏曝光→CTA），按变体 |
| SM-4 | 分享落地 `signup_succeeded`（`entry_source=diary_cta`）占比，实验前后对比 |
