# PRD · TailTopia 留存修复与归因补齐（线上诊断 v1.1.2 后续）

| 项 | 值 |
|---|---|
| 版本 | v1.0（2026-08-19） |
| 状态 | draft（待产品拍板 OQ 后转 ready-for-dev） |
| 依据 | `tailtopia_posthog_diagnosis.md`（2026-08-19，PostHog 四份报告） |
| 范围 | 前端 App 改动为主；归因链路涉及 App 端属性回填 |
| 负责人 | Shawn（产品决策）/ 研发（实现） |

---

## 0. 一句话结论

> 诊断文档把病根归为「8/6–8/7 断链版本」，但剔除该版本后 v1.1.2 已注册用户 D1 仍只有 **13.5%**（健康线 30%+）。真正的结构性漏点是**激活与发布飞轮断链**，本 PRD 修四个点：庆祝页主 CTA 接线错误、内容详情页游客无下一步、渠道归因缺失、回放与屏名缺失。前两个是增长飞轮的主链路修复，后两个是让「数据能回答对错」的前置基建。

---

## 1. 背景与问题（引用诊断数据）

| # | 问题 | 数据证据 | 定性 |
|---|---|---|---|
| 1 | 建档成功庆祝页主 CTA「Rekam Momen Pertama」**言行不一**：文案承诺「记录第一个瞬间」，点击却跳内容流首页 | 建档→首条发布仅 **26.7%**，中位间隔 2 小时（非当场） | 结构性，修 |
| 2 | 内容详情页 `/content/:id` 是最大断头路，游客看完就走、无下一步 | 2,208 次会话 **50.5%** 在此结束；日记页→点 CTA 流失 47%、中位停留仅 11–14s | 结构性，修 |
| 3 | 渠道归因缺失，分渠道留存/转化永远不可算 | `media_source`/`campaign` 全空，`$virt_initial_channel_type` 对 1,240 人全为 `Unknown` | 基建，补 |
| 4 | 无会话回放、rageclick 无法定位控件 | 项目 0 条录制；665 次 rageclick 屏名全为 `Flutter` | 基建，补 |

**已确认为真、但不在本 PRD 范围（避免返工/越界）：**

- v1.1.0 断链包（473 用户 / D1 2.3%）：深链修复已在 v1.1.2 落地，剩余是**运营召回**动作，见 §8 前置依赖，不写代码需求。
- 直接安装用户注册率 0.6%（644 人）：根因是冷启动首屏无价值主张，涉及信息架构/首屏重做，**单独立 PRD**，不在本次四个点内。

---

## 2. 目标与非目标

### 目标

1. 把「建档 → 首条发布」这一增长飞轮的关键环节修通（修接线，而非新增引导）。
2. 给内容详情页游客一个明确的、复用既有门控的「创建档案」下一步。
3. 打通 AppsFlyer → PostHog 的归因属性，使渠道分析可用。
4. 开启移动端回放并修正屏名解析，使「靠事件猜」变成「能真看」。

### 非目标（Non-Goals）

- ❌ 本 PRD 不重做冷启动首屏 / 信息架构 —— **但已升级为 P0 优先级的下一份 PRD**（TikTok 数据证明：428 次付费安装 → 18 注册 3.9% → 12 建档 2.6%，直接安装注册率在所有版本恒为 0.6%，是当前最大失血点）。
- ❌ 不新增任何提示条类组件、不复活 FR-0H（护栏 AD-15 Rule 3，见 §6）。
- ❌ 不引入后端新埋点、不引入新中间件 / MQ / 缓存（CLAUDE.md 硬护栏）。
- ❌ 不改 Tab 顺序、不改引导流程（诊断已确认注册→引导 97%、引导→建档 83% 是健康的）。
- ❌ 不做 v1.1.0 断链用户的召回推送（运营动作，见 §8）。

---

## 3. 成功指标（Success Metrics）

| 编号 | 指标 | 现状基线 | 目标 | 观察窗口 |
|---|---|---|---|---|
| SM-1 | 建档 → 首条发布转化率 | 26.7% | ≥ 50% | 修复版本发布后，按新 cohort 观察 2 周 |
| SM-2 | `/content/:id` 会话退出率 | 50.5% | ≤ 40% | 同上 |
| SM-3 | 分享落地 → 注册转化率（`diary_guest_create_profile_cta_tapped` 各 source 分列） | 落地→注册 37%（综合） | 提升，重点看 `content_detail` 来源是否新增转化 | 同上 |
| SM-4 | 渠道归因覆盖率（`media_source` 非空的 person 占比） | 0%（全 Unknown） | 新增用户 ≥ 80% 有归因属性 | 归因改造上线后 |
| SM-5 | 直接安装 → 注册转化率 | 0.6%（全版本）/ 3.9%（TikTok 8/1–8/12） | ≥ 10% | 冷启动 PRD 上线后（本 PRD 之外） |

> **SM-5 说明**：3.9%（TikTok）与 0.6%（直接安装整体）差异来自断链包与样本口径，两者都远低于健康线。SM-5 的 0.6% 是「直接安装」这一人群的稳定基线，冷启动修复以它为准。

> ⚠️ 口径纪律（来自诊断 §附）：留存/转化一律按 `person_id` 去重、只看成熟 cohort；`signup_succeeded` 2026-08-06 才上线，跨版本比较时用 `onboarding_completed` 对齐。

---

## 4. 功能需求

### FR-1 庆祝页主 CTA 接回「发布」流（修接线）

**现状**：`app_router.dart:624` 的 `onStartExplore` 中，主 CTA「Rekam Momen Pertama 📸」与次 CTA「Lihat profil dulu」共用同一回调，行为都是「推送权限闸门 → `c.go('/home')`」。主按钮文案承诺「记录第一个瞬间」，落地却是内容流首页，发布入口（「＋」）在别处。

**需求**：主 CTA 与次 CTA 分流。

- 主 CTA「Rekam Momen Pertama」：推送权限闸门后 → `PublishComposePage.open(context, preset: ContentType.growthMoment)`（预选成长日历类型，直接进入发布 bottom sheet）。
- 次 CTA「Lihat profil dulu」：保持现状「推送权限闸门 → `c.go('/home')`」。

**验收标准（AC）**：

- [ ] AC1（L2 视觉）：建档成功 → 庆祝页点主 CTA → 直接打开发布页且「成长日历」类型已预选；点次 CTA → 进首页。
- [ ] AC2（L0 静态）：主/次 CTA 各自上报独立事件（见 §5），`flutter analyze` 零警告。
- [ ] AC3（L0 单测）：新增事件名通过 `v112_events_test.dart` 命名断言。
- [ ] AC4（护栏）：推送权限闸门仍只在「庆祝页后、进发布前」触发，不因分流而重复弹或漏弹（`maybeRequestAfterProfileCreated` 语义不变）。

**代码位置**：`petgo_app/lib/core/router/app_router.dart`（`/profile/created` 路由，约 624–630 行）；`petgo_app/lib/features/content/presentation/publish_compose_page.dart`（`PublishComposePage.open`）。

---

### FR-2 内容详情页 `/content/:id` 游客态加「创建档案」CTA

**现状**：`content_detail_page.dart` 的 `_DetailScaffold` 底部只有评论框（游客点评论触发登录），正文与评论区之间没有任何「创建你自己的宠物档案」的正向引导。游客从分享链接落地到真实内容详情页后，看完即走。

**需求**：当 `currentUserId == null`（游客态）时，在正文结束、评论区标题之前插入一条「创建档案」引导卡。

- 文案**复用**既有 `diaryGuestPrimaryCta`（ID「Mulai Catat Perjalanan Si Kecil」/ EN「Start your pet's story」），**不新增 i18n 键**。
- 动作**复用**既有单一门控 `requireLogin` + `RouteIntent(location: '/profile/create')` + `pendingAction`，登录后回跳建档；**不新写一套跳转**。
- 样式复用 Diary 游客引导卡组件（与 `diary_guest_page.dart` / `diary_demo_detail_page.dart` 同源），**不新造版式**。

**验收标准（AC）**：

- [ ] AC1（L2 视觉）：游客打开任意 `/content/:id`，正文后可见「Start your pet's story」引导卡；点击 → 登录 → 回跳 `/profile/create`。
- [ ] AC2（L2 视觉）：已登录用户（含已建档）**不显示**该卡。
- [ ] AC3（L0 单测）：点击上报事件 `source=content_detail`（见 §5）。
- [ ] AC4（护栏）：该引导仍走唯一门控入口，不新增旁路登录；不复活 FR-0H 提示条形态。

**代码位置**：`petgo_app/lib/features/content/presentation/content_detail_page.dart`（`_DetailScaffold.build`，评论区标题之前）；复用 `petgo_app/lib/features/profile/presentation/diary_guest_page.dart` 的引导卡与门控模式。

---

### FR-3 AppsFlyer 归因属性灌入 PostHog person property

**现状**：AppsFlyer SDK 已接入（`appsflyer_client.dart` + `appsflyer_sdk ^6.18.1`），`af_complete_registration` 等事件已上报，但**归因结果没有回填到 PostHog 的 person 属性**，导致渠道分析全 `Unknown`。

**需求**：在 AppsFlyer 的归因回调（conversion data / attribution data）拿到 `media_source`、`campaign`、`af_status`（Organic / Non-organic）后，作为 **person property** 写入 PostHog。

- 写入时机：归因数据可用后、且用户 `identify` 之后（person 属性挂在 person 上，与事件属性不同）。
- 写入方式：经 `Analytics` 门面新增 `setAttributionProperties(Map)` 方法，内部走 `Posthog().identify(userId, userProperties: {...})`（或 `register` 作为 super property 的替代方案——二选一，见 OQ-2）。
- 字段名（person property 键）：`media_source`、`campaign`、`af_status`。**三者均不在 `scrub()` 黑名单内，无需改脱敏规则**。
- 隐私约束：仅这三个归因字段，**禁止**把 AppsFlyer 原始回调里的其他键（可能含广告 ID / 设备标识）一并透传。

**验收标准（AC）**：

- [ ] AC1（L0 单测）：`setAttributionProperties` 只接受白名单三键，其余键丢弃；键名经 `scrub` 后仍保留。
- [ ] AC2（L2 端到端）：新装 App → 完成归因 → 注册后，PostHog 该 person 出现 `media_source`/`campaign`/`af_status`。
- [ ] AC3（看板）：PostHog 新用户渠道分布出现非 `Unknown` 的 `media_source` 分桶。

**代码位置**：`petgo_app/lib/core/analytics/analytics.dart`（新增方法）、`petgo_app/lib/core/analytics/appsflyer_client.dart`（暴露归因回调结果）。

**已确认的归因事实（2026-08-19 拉取，供验收对拍）**：

- `media_source` 实际值 = **`tiktokglobal_int`**（TikTok 归因正常回传，AF 侧已可见）。
- campaign 名 = `App promotion20260805142028`（单一 campaign，TikTok App Promotion 自动投放）。
- **成本侧 AF 没有任何副本**（`Cost & Ad Revenue Status = No active cost integrations`，`Connect to partners = Pending`）——CPI/ROAS 无法从 AF 计算。

**配套（非代码，运营/配置任务，与 FR-3 一起走 P0）**：

- [ ] AC5（配置）：在 AppsFlyer 接通 **ROI360 的 TikTok cost integration**，使 `spend`/`impressions`/`clicks` 自动回传，CPI/ROAS 在 AF 内可算，无需每次手工对表。
- [ ] AC6（投放侧，下次投放前）：TikTok 优化目标从「安装」切到「注册」（AEO，目标事件 = `af_complete_registration`，该事件已在回传），避免再买到「只装不注册」的量。

---

### FR-4 开启移动端 Session Replay + 修正屏名解析

**现状**：`analytics.dart:52` `sessionReplay = false`（后台开关对移动端无效，需 SDK 侧开）；rageclick 的屏名全部解析为 `Flutter`，无法定位控件。

**需求**：

- Session Replay：`PostHogConfig.sessionReplay = true` + `sessionReplayConfig.maskAllTexts = true`（印尼用户隐私，全文打码）。
  - 仅 production 开启；staging/development 关闭以省免费额度（沿用 `posthogenvironmentsplit.md` 的 `environment` 判定）。
- 屏名解析：排查 autocapture 的 `$screen_name` 为何恒为 `Flutter`；必要时在 `AnalyticsAutocapture` 显式注入当前屏名（复用 `Analytics.screen()` 已维护的 `*_page` 屏名体系）。

**验收标准（AC）**：

- [ ] AC1（L2 端到端）：发布 production 版本后 1–2 天，PostHog 出现回放样本，且文本已打码。
- [ ] AC2（L0 静态）：`flutter analyze` 零警告；staging 构建下 `sessionReplay=false`。
- [ ] AC3（看板）：rageclick 事件能解析到具体屏名（非 `Flutter`）。

**代码位置**：`petgo_app/lib/core/analytics/analytics.dart`（SDK 配置）、`petgo_app/lib/app.dart`（`AnalyticsAutocapture`）。

---

## 5. 埋点事件清单（工程侧权威副本，同步 `docs/analytics-posthog-tracking.md`）

> 命名遵守 §8.1 `<模块前缀>_<对象>_<动作>`，动作后缀 `_tapped`；受 `v112_events_test.dart` 断言约束。

| 编号 | 事件名 | 一句话 | 属性 | 代码位置 |
|---|---|---|---|---|
| N-1 | `diary_profile_created_record_first_tapped` | 建档成功庆祝页点主 CTA「Rekam Momen Pertama」 | 无（可选 `has_avatar`: bool，看头像是否有图） | `profile_created_celebration_page.dart` 主 CTA / `app_router.dart` 主 CTA 回调 |
| N-2 | `diary_profile_created_view_profile_tapped` | 建档成功庆祝页点次 CTA「Lihat profil dulu」 | 无 | 同上，次 CTA |
| N-3 | （复用）`diary_guest_create_profile_cta_tapped` | 内容详情页游客点「创建档案」引导卡 | 新增 `source=content_detail` | `content_detail_page.dart` 引导卡 |

- **N-3 复用既有 T-4 事件**而非新造事件：同一语义（游客点建档引导）不同 surface，用 `source` 区分，与既有 `diary_demo_detail_page` 的 `source=demo_detail_interaction` 同一套路，**不增加命名测试负担**。
- **N-1/N-2 需在 `v112_events_test.dart` 确认通过**：模块前缀 `diary_`、后缀 `_tapped` 均在白名单；对象名 `profile_created_record_first` / `profile_created_view_profile` 描述性可读。
- 归因属性（FR-3）是 **person property，不是事件**，不进本事件表。

---

## 6. 护栏与合规自查

| 护栏（来源） | 本 PRD 是否触碰 | 处理 |
|---|---|---|
| 禁引入 MQ / 缓存 / 新中间件（CLAUDE.md） | 否 | 无后端改动 |
| 建档引导渠道收敛为唯一一条 = Diary（`home_page.dart` 注释 / AD-15） | ⚠️ FR-2 在 `/content/:id` 加引导，表面新增入口 | **见 OQ-1，需产品拍板**：主张该页属「分享落地链路」的一部分、复用 Diary 引导卡与同一门控，不算新渠道；若产品不认可则降级为只复用不新增 |
| 不复活 FR-0H 提示条（AD-15 Rule 3） | FR-2 引导卡 ≠ 顶部提示条 | 复用 Diary 游客引导卡形态，非首页提示条 |
| 隐私：全文打码、PII 不入事件（`scrub`） | FR-4 回放需打码；FR-3 只透传三字段 | `maskAllTexts=true`；归因白名单三键 |
| 红色态零变现 / 安全规则层只升不降 / 注销级联（CLAUDE.md 三类安全攸关） | 否 | 本 PRD 不触碰 |
| 事件命名受单测约束（`v112_events_test.dart`） | FR-1 新增 N-1/N-2 | 需 AC3 验证 |

---

## 7. 开放问题（OQ）

| 编号 | 问题 | 倾向 | 责任方 |
|---|---|---|---|
| OQ-1 | FR-2 在 `/content/:id` 加「创建档案」引导，是否违反「建档引导渠道收敛为唯一一条 = Diary」的既有决策？ | 主张不算：该页是分享落地的天然延伸，复用同一引导卡与门控，不新增渠道语义 | 产品（Shawn 拍板） |
| OQ-2 | FR-3 归因属性写入方式：`identify(userProperties:)`（person property）还是 `register()`（super property）？ | 倾向 `identify(userProperties:)`，因归因是「人」的属性、需与 person 绑定；`register` 是设备级 super property，换账号会串 | 研发 |
| OQ-3 | FR-4 屏名解析：是否需在 `AnalyticsAutocapture` 显式注入屏名，还是 SDK 版本升级即可（线上同时存在 4.11.0 与 5.33.0 两版 SDK）？ | 先统一 SDK 版本再排查，避免在旧版上做无效修复 | 研发 |
| OQ-4 | SM-1 目标 50% 是否过激？（诊断中该环节 26.7%，提升到 50% 依赖「预选类型 + 当场记录」双重改善） | 保留 50% 为北极星，2 周后按实测再校准 | 产品 |

---

## 8. 依赖与前置（运营侧）

### 8.1 已查明（2026-08-19，无需再向 Shawn 索取）

- 8/6–8/7 安装 ≈ **100% 来自 TikTok 单一 campaign `App promotion20260805142028`**（`tiktokglobal_int`，412 次安装 / 98.3%）。
- 8/1–8/12 全貌：462 安装中 428 来自该 TikTok campaign、34 自然量，无多渠道混合。
- 成本数据 **AF 侧不存在**（未接 cost integration），需去 **TikTok Ads Manager** 导出该 campaign 的 `spend`/`impressions`/`clicks`。
- 口径差：AF 记 419，诊断文档写 444 —— 差异来自「AF install 归因日 vs PostHog 首次事件日」或取数窗口，渠道对齐时以 **AF 的 `install_time` 为准**。

### 8.2 待 Shawn 提供 / 待配置

1. **TikTok Ads Manager 导出**：campaign `App promotion20260805142028` 在 8/6–8/7 的 `spend` + `impressions` + `clicks`（算真实 CPI）。
2. **接 ROI360 TikTok cost integration**（FR-3 AC5）：一次配置，之后 CPI/ROAS 自动可算。
3. **v1.1.0 断链用户（约 473 人）召回**：确认推送通道与文案，另行出方案（这批人 D1 2.3%，但召回 ROI 低于修冷启动，排序靠后）。

### 8.3 投放决策（Shawn 拍板）

> **在拿到 TikTok `spend` 数字、且冷启动 PRD 上线前，暂停追加投放。** 428 次安装换 12 个建档、1 次下单，无论 CPI 是多少都不成立；且当前无法区分「流量差」与「产品冷启动坏」（产品侧是已知坏变量），此时投就是继续给坏漏斗送钱。恢复投放的充分条件是三者齐备：① 冷启动修复上线；② cost integration 接通、知道 CPI；③ 优化目标切到注册（AEO）。

---

## 9. 验证口径（PostHog Insight 定义）

| Insight | 定义 |
|---|---|
| SM-1 | Funnel：`diary_profile_created_record_first_tapped` → `content_publish_submitted`（或 `publish_page_content_type_selected`），按新 cohort |
| SM-2 | 页面退出率：`$screen = '/content/:id'` 为会话最后一屏的占比，改版前后对比 |
| SM-3 | Funnel：`diary_guest_create_profile_cta_tapped`（`source=content_detail`）→ `signup_succeeded` |
| SM-4 | Person property 分布：`media_source` 非空的 person 占比（SQL editor） |
