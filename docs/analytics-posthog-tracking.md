# PostHog 埋点清单（TailTopia App）

> 盘点时间：2026-07-16 · 分支 `stag` · 依据代码实读，非设计稿
> 埋点门面：`petgo_app/lib/core/analytics/analytics.dart`
> 结论速览：**只有前端 App 埋点，后端零埋点**。事件分三类：SDK 自动事件、全局点击 autocapture、9 个手工业务事件。

---

## 1. 接入配置

| 项 | 值 | 来源 |
|---|---|---|
| SDK | `posthog_flutter: ^4.0.0`（锁在 4.11.0） | `pubspec.yaml:75` |
| Project Token | `phc_mww2QxsJpXeHkcyyd4ahjAXUUh6aruzMxLfcFmg8ePzC`（write-only，可入端） | `analytics.dart:20` |
| Host | `https://eu.i.posthog.com`（EU Cloud，project 211847） | `analytics.dart:26` |
| 覆盖方式 | `--dart-define=POSTHOG_KEY=... --dart-define=POSTHOG_HOST=...` | 同上 |
| 初始化时机 | `runApp` 之前，3 秒超时，失败只打日志不阻断启动 | `main.dart:20`、`analytics.dart:46-60` |
| Session Replay | **关闭** | `analytics.dart:52` |
| 生命周期事件 | **开启**（`captureApplicationLifecycleEvents = true`） | `analytics.dart:51` |
| 上送批量 | debug 每条即时上送（`flushAt=1`）；release 默认攒 20 条 | `analytics.dart:54` |
| debug 日志 | 仅 debug 模式开 | `analytics.dart:50` |

**环境隔离现状：** 没有按 stag/prod 分 project——staging 包和生产包默认打进同一个 token，数据混在一起。要分开必须在打包时用 `--dart-define=POSTHOG_KEY` 显式覆盖。

---

## 2. SDK 自动事件（无需写代码）

| 事件名 | 触发 | 开关位置 |
|---|---|---|
| `$screen` | 每次 go_router 路由跳转，页面名取路由 path（如 `/home`、`/profile/id-card`） | `app_router.dart:112` 注册 `PosthogObserver()` |
| `Application Installed` / `Application Opened` / `Application Backgrounded` / `Application Updated` | App 生命周期 | `captureApplicationLifecycleEvents = true` |
| `$autocapture` 相关的 SDK 默认属性（`$screen_name`、设备/系统/版本等） | 每条事件自动附带 | SDK 内建 |

---

## 3. 全局点击 autocapture

事件名 **`button_tapped`**，由 `AnalyticsAutocapture`（挂在 `app.dart:134` 根部）拦截每一次抬指，从 semantics 树里找命中点下**最深的可点节点**取其 label。

| 属性 | 说明 |
|---|---|
| `button_name` | 该控件的 semantics label（本地化文案），经 `sanitizeTapLabel` 脱敏 |
| `autocaptured` | 恒 `true`，用于和手工事件区分 |
| `$screen_name` | SDK 自动注入当前屏幕，代码里不重复带 |

**脱敏规则**（`analytics.dart:101-108`）：标签为空 → `(unlabeled)`；长度 > 40 字符、含 `@`、或含 6 位以上连续数字 → `(redacted)`。命中点下没有可点节点（滚动区、空白）则不上报。

**代价：** 强制常开 semantics 树，有轻微性能开销；label 是印尼语/英语双份文案，同一个按钮在两种语言下会是两个不同的 `button_name`，分析时要注意。

---

## 4. 手工业务事件（共 9 个）

| 事件名 | 属性 | 触发点 | 代码位置 |
|---|---|---|---|
| `login_tapped` | `method`: `google` \| `apple` | 登录页点第三方登录按钮 | `login_page.dart:34,39` |
| `onboarding_nickname_submitted` | 无 | 引导流提交昵称 | `nickname_page.dart:59` |
| `onboarding_completed` | `pet_status` | 引导流最后一步选完养宠状态 | `pet_status_page.dart:29` |
| `pet_profile_create_submitted` | 无 | 提交创建宠物档案 | `pet_profile_create_page.dart:98` |
| `triage_submitted` | 无 | 提交 AI 分诊（上传页） | `triage_upload_page.dart:118` |
| `consult_started` | 无 | 分诊结果页点发起兽医问诊（两个入口都埋了） | `triage_result_view.dart:155,180` |
| `post_like_tapped` | `liked`: bool（点击后的目标态） | 内容流点赞按钮 | `like_button.dart:41` |
| `content_publish_submitted` | `type`（帖子类型 enum name） | 发布内容提交 | `publish_compose_page.dart:156` |
| `milestone_share_created` | `code`（里程碑代码）、`level`（等级 enum name） | 生成里程碑分享 | `milestone_share.dart:39` |

> 所有 `Analytics.capture` 调用都 try/catch 吞错，埋点失败绝不阻断主流程（`analytics.dart:81-90`）。

---

## 5. 用户身份（identify / reset）

绑定逻辑单点收口在 `app.dart:84-100` 的 `ref.listen(authControllerProvider)`，不散在各 call-site：

- **identify**：登录成功（含引导中的新用户，只要拿到 id）且 id 发生变化 → `Posthog().identify(distinctId)`。改昵称等资料变更不会重复 identify。
- **换人**：未经 guest 直接切到另一账号 → 先 `reset()` 再 `identify()`（遵守 PostHog 换人先 reset 规约）。
- **reset**：登出 / 续期失败回到 guest 且此前确有身份 → `reset()`。纯游客态抖动不 reset，保住匿名漏斗连续性。

**distinctId = `sha256("tailtopia-user-" + 内部用户id)`**（`analytics.dart`），并以 person property 额外携带明文数字 id：`userProperties: {internal_user_id: <id>}`。除此之外不传任何其它 `userProperties`。

已知取舍（代码注释里写明）：distinctId 是**无盐** sha256，持 PostHog 读权限的人本可以暴力反推回内部自增 id。V1 接受——该 id 既非 PII 也非健康数据，且 distinctId 不是对外 API 面无枚举风险。

**「自增 id 不外露」护栏的 PostHog 专项豁免（2026-08-18 拍板）**：`internal_user_id` 明文随 identify 上报——既然无盐哈希本可被反推，明文不增加实际暴露面，而运营在 PostHog 需要按数字 id 直接定位用户。**豁免边界**：只限 PostHog 这一家；AppsFlyer CUID 等其它第三方仍只传哈希；对外 API 面仍一律不可枚举 token；username/昵称/邮箱/手机号照旧一概不传。若未来要收紧，替代方案是后端下发不透明分析 token（HMAC 映射）。

---

## 6. 隐私防线

两道：

1. **`scrub()` 黑名单剥离**（`analytics.dart:33-43`，递归嵌套 map）。键先归一化（转小写 + 去掉非字母数字），所以 `display_name` / `displayName` / `Display-Name` 都命中同一条规则。覆盖三类：
   - 身份/联系方式：`email` `mail` `name` `nickname` `displayname` `fullname` `firstname` `lastname` `username` `phone` `mobile` `tel` `whatsapp` `address` `avatarurl` `dob` `birthday`
   - 健康数据：`symptom` `symptoms` `diagnosis` `medication` `disease` `breed`
   - 凭证/精确位置：`password` `token` `jwt` `lat` `lng` `latitude` `longitude` `geo` `ip`
2. **autocapture 标签脱敏**：见第 3 节。

---

## 7. 盘点发现的缺口

按重要性排：

1. **环境不隔离** —— stag 包和 prod 包默认同一个 project token，测试数据污染生产分析。建议 staging 构建脚本加 `--dart-define=POSTHOG_KEY=<stag project token>`。
2. **成功/失败态没埋** —— 现有事件全是「点了/提交了」，没有对应的 `*_succeeded` / `*_failed`。算不出登录成功率、分诊成功率、发布失败率。
3. **V1.1 新功能零埋点** —— PawCoin 充值、GemPay 支付、退款、客服工单、身份证（KTP）、健康记录、新手任务（Lulus Pemula）全部没有手工事件，只能靠 autocapture 的 `button_tapped` 反推，粒度很粗。
4. **兽医端零埋点** —— `/vet/*` 全流程（工作台、接单、回复）没有任何业务事件。
5. **无埋点单测** —— `test/` 下没有 analytics 目录，`sanitizeTapLabel` / `scrub` / `distinctIdFor` 三个纯函数明确标注了「L0 可测」但实际没测。
6. **后端零埋点** —— 服务端事件（订单状态流转、支付回调结果）不进 PostHog，漏斗只能看到前端一半。

---

## 8. V1.1.2 新增埋点（Story 6.1 · 2026-08-04）

> 回写依据：V1.1.2 Story 6.1。**产品侧同一份内容**在
> `3.数据埋点/埋点文件/埋点清单v112.md`（产品文档空间），两边内容须一致。
> 本节是工程侧权威副本：事件名、属性名、代码位置以此为准。

### 8.1 命名约定（**新事件必须遵守**）

`<模块前缀>_<对象>_<动作>`，**模块前缀用产品叫法**，动作用过去式落在词尾。

- 模块前缀：`app_` `bottom_nav_` `diary_` `social_` `health_` `publish_` `me_` `signup_` `milestone_`
- 动作后缀：`_viewed` `_shown` `_tapped` `_selected` `_toggled` `_switched` `_succeeded` `_completed` `_achieved` `_landed_on_tab`

目的：产品在看板上**一眼看出这是哪个页面的哪个按钮**，不必回来问工程。
反例（本轮改掉的）：`tab_switched` 分不清底部导航还是页内 Tab；`diary_sync_toggled`
听着像 Diary 页上的开关、其实在发布页。约定由 `petgo_app/test/analytics/v112_events_test.dart` 的断言守着：它**从 `lib/` 源码里
真提取** `Analytics.capture('…')` 的字面量再逐个校验，所以新增或改名事件时起名不合规会红
（2026-08-04 code-review 前，该断言遍历的是测试文件内手抄的一份数组 —— 那时这句话是不成立的）。
V1.0.x 的 13 个遗留事件在测试里以 `legacyEvents` 显式豁免（见 §8.9），新事件不得加入该名单。

⚠️ **2026-08-04 改名台账：`discovery_*` → `social_*`（用户决策）**

第 1 位 Tab 的显示文案由 `Jelajah` / `Discovery` 改为 `Sosial` / `Social`（原因见 Story 6.1
Review Findings：`Jelajah` 与 `Discovery` 在放大字号下都会被截断），埋点标识**同批跟着改**，
避免代码里叫 discovery、界面上叫 Social 的长期错位。受影响的 5 处：

| 类别 | 旧 | 新 |
|---|---|---|
| 模块前缀 | `discovery_` | `social_` |
| 事件（T-6） | `discovery_soft_login_sheet_shown` | `social_soft_login_sheet_shown` |
| 事件（T-6） | `discovery_soft_login_sheet_login_tapped` | `social_soft_login_sheet_login_tapped` |
| 屏名 | `discovery_page` | `social_page` |
| 属性值 | `tab` / `to_tab` / `from_tab` = `discovery`；`entry_source` = `discovery_soft_login` | 同名改 `social` / `social_soft_login` |

**配看板的人必读**：PostHog 里**已经上报过的历史事件仍是旧名字**，改名不会追溯重写。
凡是跨 2026-08-04 的图表，要么按「旧名 OR 新名」并集取数，要么明确只看改名之后。
若此前已按 `discovery_*` 建过 insight，改名后它会变成 0 条 —— 那不是埋点坏了，是筛选条件要改。

⚠️ **Tab 的枚举名与产品叫法不一致**，埋点一律用产品叫法（`AppTab.analyticsName`）：

| 代码里的枚举 | 埋点/看板里的名字 | 产品叫法 |
|---|---|---|
| `AppTab.profile` | `diary` | Diary（成长日记） |
| `AppTab.triage` | `health` | Health（健康/问诊） |
| `AppTab.home` | `social` | Social（社区广场；文案原 Discovery / Jelajah，2026-08-04 改名） |
| `AppTab.me` | `me` | Me（我的） |

### 8.2 本版本修掉的 P0 缺口：Tab 切换此前完全没有浏览事件

底部 Tab 走 `StatefulShellRoute.goBranch` 切分支、**不 push 根路由** → §2 里的
`PosthogObserver` 收不到 `didPush`，四个 Tab 根页一个 `$screen` 都没有。后果是
「落地页分流是否生效」无法验证 —— 而落地页矩阵正是 V1.1.2 的核心改动。

修法：`Analytics.screen()` 显式补一条，屏名 `<产品名>_page`（`diary_page` / `health_page` /
`social_page` / `me_page` / `vet_workbench_page`）。冷启动落地页用**同一套字面量**，
否则「冷启动落在 Diary」与「切到 Diary」会被算成两个不同页面。详情页仍由 observer 自动上报。

**底栏第 5 个位置（「＋」发布）**（2026-08-04 code-review 决策 D1 补齐）：发布页是
`showModalBottomSheet` 而不是 `PageRoute`，PostHog 的 `defaultPostHogRouteFilter` 只跟踪
`PageRoute` → observer 同样收不到。现由 `PublishComposePage.open()` 手工上报 `publish_page`，
命名与四个 Tab 根页同一套。⚠️ 它**不是 Tab 根页**：统计「四个可导航 Tab 的曝光」时不要把它算进来，
统计「底栏 5 个位置各被用了多少」时才算。

⚠️ **`$screen` 表里有两套命名并存，看板一律用 `*_page` 那套**：`observers: [PosthogObserver()]`
对所有真实路由跳转都会自报一条以**路由路径**为名的 `$screen`（`/profile`、`/content/123`…）。
Tab 切换走 `goBranch` 不触发它，但**冷启动落地是 `ctx.go()`、是真跳转**，所以落地那一刻会同时有
`diary_page`（手工，权威）与 `/profile`（observer）两条。这不影响按 `*_page` 取数的口径，
但按「所有 $screen 汇总」看页面排行时会看到重复行 —— 已知现象，不是漏改（code-review 2026-08-04 核实）。

### 8.3 事件清单（T-1~T-14，**T-5 已删且编号不重分配**）

| # | 事件名 | 一句话（产品视角） | 属性 | 代码位置 |
|---|---|---|---|---|
| T-1 | `app_launch_landed_on_tab` | 冷启动后落在了哪个 Tab | `tab`（diary/social/vet_workbench）、`user_state`、`restore_timeout`（仅超时兜底那次）、`corrected_from`（仅迟到纠正那次） | `app_router.dart` splash 回调 |
| T-2 | `bottom_nav_tab_switched` | 点了底部导航切 Tab | `from_tab`、`to_tab`、`user_state` | `app_shell.dart` |
| T-3 | `diary_guest_page_viewed` | 未登录用户看到了 Diary 游客种草页 | `session_first`（是否本次启动首次看到） | `diary_guest_page.dart` |
| T-4 | `diary_guest_create_profile_cta_tapped` | 游客点了任一「建档引导」入口 | `source` | `diary_guest_page.dart` / `diary_demo_detail_page.dart` |
| T-6 | `social_soft_login_sheet_shown` / `..._login_tapped` | Social（社区广场）刷到第 3 页弹的软登录浮层：曝光 / 点了登录 | 后者带 `method`（google/apple） | `login_guide_controller.dart` |
| T-7 | `signup_succeeded` | **注册真正成功**（不是点了按钮） | `entry_source` | `login_guide_controller.dart` / `login_page.dart` |
| T-8 | `publish_page_content_type_selected` | 发布页选了内容类型 | `type`、`is_default`、`has_pet_profile` | `publish_compose_page.dart` |
| T-9 | `publish_page_sync_to_moment_toggled` | 发布页拨了「同步到 Moment」开关 | `enabled` | `publish_compose_page.dart` |
| T-10 | `diary_timeline_item_tapped` | 点了 Diary 时间线上的某条 | `item_type` | `growth_archive_page.dart` |
| T-11 | `diary_view_mode_switched` | Diary 在时间线 ⇄ 日历之间切换 | `to_view`（timeline/calendar） | `growth_archive_page.dart` |
| T-12 | `milestone_achieved` | 里程碑达成（**后端上报**） | `code`、`level`、`path` | `MilestoneAnalyticsListener.java` |
| T-13 | `login_guide_hard_dialog_shown` / `..._login_tapped` | 强登录弹窗：曝光 / 点了登录（2026-08-31 补，此前这条链路零埋点 → 漏斗上表现为下一个事件直接跳 `af_complete_registration`） | `entry_source`；后者另带 `method`（google/apple） | `login_guide_controller.dart` |
| T-14 | `login_guide_entry_blocked` | 游客点了受控入口、被门控拦下（点击**意图**，与 onAllowed 里的浏览埋点相反，记在门控之外） | `entry`（tab_me / publish_add） | `app_shell.dart` |

属性取值词表：

- `user_state`：`guest` / `vet` / `owner_with_profile` / `owner_without_profile` / `planning` / `enthusiast`
  （即 `AppUserState.wire`。⚠️ Story 6.1 AC3 原表写的是 `A_with_profile`/`B`/`C` —— 实现取了
  枚举自述名：语义等价、可读性更好，且与落地矩阵同源。**看板以此为准**。）
- `source`（T-4）：`bottom_sticky_cta`（底部常驻主按钮）/ `timeline_item`（示例时间线条目与金徽章）/
  `demo_detail_interaction`（示例详情页点赞·评论·举报）/ `header_entry`（页头四个入口）
- `entry_source`（T-7 / T-13）：`diary_cta`（游客态 Diary 引导）/ `social_soft_login`（软登录浮层）/
  `login_page`（登录页直登）/ `tab_me`（底栏受控 Tab，2026-08-31 起）/ `publish_add`（底栏「＋」发布，同）/ `other`
  ⚠️ `other` 的构成在 2026-08-31 之后**收窄**：底栏受控 Tab 与「＋」已改传显式值，
  剩下落在 `other` 档的主要是 401 续期失败的强弹窗与其余未标注入口。
  **改动日期前后的 `other` 占比不可直接对比**（老数据里它还包含 tab_me / publish_add）。
  看板配「转化路径构成」时仍必须显式列出它，否则会出现一个未标注桶。
- `item_type`（T-10）：`HAPPY_MOMENT` / `HAPPY_MOMENT_MILESTONE` / `MILESTONE_BANNER` / `HEALTH_RECORD` / `ID_CARD_ISSUED`，外加 `UNSPECIFIED`
  —— **直取后端下发的 `itemType`**（AD-2），前端不自行推断。
  ⚠️ 类④ 是 `HEALTH_RECORD`，**不是 `HEALTH_EVENT`**（后者是 `kind` 的取值，不是 `itemType`）——
  本行曾写错，按错值配的看板筛选会恒为 0 条（code-review 2026-08-04 修正）。
  `UNSPECIFIED` = 老后端没下发 `itemType` 的兜底：宁可标成「不知道」，也不送前端猜的分类值，
  否则「后端真这么分」与「前端猜的」在看板上无法区分。
- `restore_timeout`（T-1，**仅出现在超时兜底那一次**，V1.1.2 Story 7.4 · FR-91）：`true`
  —— 会话恢复没在 5s 预算内回来、按当时已知态（游客）兜底落地。**这是「兜底发生率」的唯一口径**
  （PRD OQ-23 靠它闭合）：超时兜底态与真实游客态都上报 `user_state=guest`，不靠这个标记分不开。
  正常路径**不带**该属性（不是 `false`，是没有这个键）。
- `corrected_from`（T-1，**仅出现在迟到纠正那一次**）：`diary` / `social` / `vet_workbench`
  —— 恢复晚到后按真实身份重判、把用户从哪个兜底落地页纠正过来的。
  ⚠️ 取值是**产品名**（与同一次上报的 `tab` 同一套词表），不是 `/profile` 这种路由路径原文
  —— 送路径原文会让两个属性在看板上对不起来（code-review 2026-08-04 修正）。
  一次冷启动最多出现一次（FR-91 只纠正一次）。

- `path`（T-12）：`health_record`（疫苗/驱虫/绝育记录触发）/ `consult`（真人兽医问诊结束触发）/
  `checkin`（用户手动打卡）/ `publish`（发布内容回填）/ `system_auto`（计数、组合、档案创建等）

### 8.4 服务端埋点（本版本首次出现）

§7.6 记的「后端零埋点」这一条本版本**局部打通**：里程碑达成的判定全在服务端
（健康记录事件、兽医问诊关闭、计数阈值、组合解锁），客户端看不到「这次是走哪条路径点亮的」，
前端补不了这一环。

- 实现：`shared/analytics/AnalyticsClient`（接口）+ `PostHogAnalyticsClient`（HTTP `POST /i/v0/e/`）。
  **没有引入第三方 SDK** —— capture 就是一个 HTTP POST，用既有 `RestClient` 十几行够了；
  引 SDK 要多背一条供应链依赖 + 它自带的线程池，与「异步只用 `@Async`、不加中间件」的护栏也别扭。
- 触发：订阅既有领域事件 `MilestoneCompletedEvent`（`@TransactionalEventListener`，
  异步发生在 `capture` 那一层）。**提交后才上报**：事务回滚了看板上却多一条达成，比没有更糟。
- 配置：`POSTHOG_SERVER_KEY`（env，留空 = 整个上报静默关闭、不出网）、`POSTHOG_HOST`
  （**留空或空串都回退默认值**，不会变成非法 baseUrl）、`POSTHOG_TIMEOUT_SECONDS`（默认 3）。
  必须与 App 端同一个 project，否则前后端事件落在两个项目里、漏斗拼不起来。
  启动时会 `log.info` 一行 `enabled=? host=?`（不打 key）—— 「以为在报其实没报」只能靠它被发现。
- **隔离与超时**（code-review 2026-08-04 补强）：上报走 `@Async("analyticsExecutor")` 的
  **专用有界线程池**（core 1 / max 2 / 队列 200 / 满则丢弃），并设 3s connect+read 超时。
  两者缺一不可 —— 与业务 `@Async` 共池且无超时时，PostHog 侧一个半开连接就能把里程碑自动完成、
  达成通知、注销级联一起饿死。宁可丢事件，不能拖慢里程碑落库。
- `distinctId` = `sha256("tailtopia-user-" + 内部用户id)`，**与客户端逐字一致**，两端各有一条
  已知向量断言（差一个字节，同一个人会被算成两个人）。
- 失败即放弃，不重试：埋点是可损数据，为它加重试/补偿会引入状态机与新表，代价远大于收益。
  失败只留一行 `log.warn`（事件名 + 异常类名，**不打 properties**）。
- 线路形态由 `PostHogAnalyticsClientTest` 用 `MockRestServiceServer` 真验（端点 / `api_key` /
  `properties.distinct_id` / `timestamp` / 关闭态一个请求都不发）—— 这些一旦错一处，看板上会
  一条都没有，而后端因为吞错只留一行 warn，很难发现。

#### ⚠️ `distinctId` 的两条已知例外（2026-08-04 决策 D4：知情后维持现状）

护栏原文是「对外暴露标识一律不可枚举 token」。当前做法是**无盐哈希内部自增 id**，
因此严格说违反该条。用户判断 PostHog 属内部数据分析用途、外泄风险可接受，**维持现状不改**。
下面两条与外泄无关，是维持现状连带接受的代价，**记录在此以免日后被反复重开或被误当成遗漏**：

1. **注销后在第三方断不干净**。编号由自增 id 确定性推出 → 同一用户永远同一编号。用户注销、
   本库按 D1/D2 删除或匿名化之后，PostHog 里他过去的行为事件**仍可被算回到他**，
   等于注销在第三方平台上没有生效。彻底解法是给用户表加一列随机 `analytics_token`、
   注销时一并删除（PostHog 里的编号随之成为孤儿）；那需要一次 Flyway 迁移 + 存量回填。
2. **连号可推算注册总量与增速**。原始输入是 1、2、3 连续的小整数，拿到编号的人可以枚举
   区间建对照表反查排号，从而算出注册用户总量与每周新增。加一把 env 密钥（HMAC）即可消除，
   但**不能**解决上面第 1 条。

### 8.5 已下线

**FR-0H 首页建档提示条**的曝光 / 点击 / 关闭事件：提示条本体已在 Story 2.3 整条废止
（AD-15 Rule 3），相关看板指标一并下线。核查结论：代码里无残留事件。

### 8.6 AC5 的线上校验口径

健康类四条（M3 疫苗 / M4 驱虫 / M5 第一次看兽医 / M9 绝育）已在 Story 5.2 取消打卡路径，
且后端**在写库前直接拒绝**健康类打卡请求。因此线上若出现
「健康类 code + `path=checkin`」的 `milestone_achieved`，说明那道护栏被绕过了 ——
**可配成告警**。

注意实现上刻意**不做改写**：`MilestoneAnalyticsPath` 遇到「健康类 + 打卡」照实上报 `checkin`，
不会归一成 `health_record`。改写等于把护栏失效的现场擦掉。

### 8.7 PRD §3 指标的失效标注（AD-6）

不取改版前基线 → PRD §3.3 五项核心指标里**三项拿不到**，随之 §3.3「唯一裁决指标」与
§3.4 处置原则**一并失效**：

| 指标 | 状态 |
|---|---|
| 游客→注册总转化率（改版前后对比） | ❌ 不可得（无改版前基线） |
| FR-0B 曝光量变化 | ❌ 不可得（同上） |
| B/C 用户留存（改版前后对比） | ❌ 不可得（同上） |
| 转化路径构成（`entry_source` 占比） | ✅ 可用（绝对值） |
| Diary 主动转私密率（`publish_page_sync_to_moment_toggled` 中 enabled=false 占比） | ✅ 可用（绝对值）——本版本最关键的产品假设验证 |

埋点仍要做（为以后攒数据），但**别指望它回答「这次改版是对是错」**。

### 8.8 §7.5 的缺口已补：埋点有单测了

- 前端 `petgo_app/test/analytics/v112_events_test.dart`（15 例）。观察手段是
  `Analytics.debugCaptureSink`（`@visibleForTesting`，挂在 `scrub()` **之后**），
  断言看到的就是端上真正发出的形态。
- 后端 `MilestoneAnalyticsTest`（9 例）：path 映射、属性只含受控值、distinctId 是哈希、
  凭证缺省时不出网。
- 两端各钉一条同样的 `sha256("tailtopia-user-42")` 向量，跨语言防漂移。

### 8.9 遗留：旧事件仍是旧命名

§4 那 9 个 V1.0.0/V1.1.0 事件（`login_tapped`、`content_publish_submitted` …）**没有按 8.1 改名**：
它们已经在线上产生了历史数据，改名会切断历史序列、也会让既有看板失效。
建议口径：**新事件按 8.1 起名；旧事件维持原名**，等哪天决定重建看板时一起迁。

---

## 9. V1.1.4 新增埋点（Story 4.1 · 2026-08-16 · 社区管控 FR-58 / FR-94）

本版本给「拉黑」与「举报」这两条治理链路补埋点。**只有 4 个事件**，命名沿用 §8.1 的既有约定
（前缀 `social_` + 后缀 `_submitted` / `_tapped`），**没有放宽任何前缀/后缀白名单**。

### 9.1 事件清单

| 事件 | 何时上报 | 属性 |
|---|---|---|
| `social_user_hide_submitted` | **隐藏关系建立成功之后** | `origin`: `BLOCK`\|`REPORT` · `entry`: `mini_profile`\|`comment`\|`blocklist`\|`report_flow` |
| `social_user_unhide_submitted` | 解除拉黑成功之后 | `origin`: 恒 `BLOCK` · `entry`: 恒 `blocklist` |
| `social_account_report_submitted` | 举报**提交成功**之后 | `entry`: `mini_profile`\|`comment`\|`blocklist` · `reason`: 账号维度五类（受控词表） |
| `social_blocklist_report_tapped` | 黑名单页点「⋯ → 举报」的**那一下**（不等提交） | `entry`: 恒 `blocklist` |

### 9.2 两个维度不要挤进一个 key

- **`origin` = 这条隐藏关系是怎么产生的**：`BLOCK`（用户主动拉黑）/ `REPORT`（举报自动产生）。
  ⚠️ 不分来源的话，**隐藏关系总量会被举报量灌大**，完全看不出主动拉黑的真实使用情况 —— 而这两件事的产品含义截然不同。
- **`entry` = 用户从哪个界面动的手**：迷你卡 / 评论区 / 黑名单页 / 举报流程自动产生。

⚠️ `source` 这个 key 在既有事件里已被占用（做多入口区分），所以本版本用 `origin` + `entry` 两个 key。

⚠️ **多入口不拆事件名**（沿用 §8 的教训：「拆了转化率分母就碎了」）——
四个入口共用同一个事件名，靠 `entry` 属性区分。唯一的例外是
`social_blocklist_report_tapped`：它测的不是「举报提交」而是「**这个入口有没有人用**」，
是另一个问题，所以单独成事件。

### 9.3 一次举报会产生两条事件

举报成功时，服务端**同时**建立一条 `source=REPORT` 的隐藏关系（Story 2.1 AC5）。所以端上会连发两条：

```
social_account_report_submitted  {entry: mini_profile, reason: HARASSMENT}
social_user_hide_submitted       {origin: REPORT, entry: report_flow}
```

这是刻意的：第二条让「隐藏关系」这条口径**完整**（举报产生的那部分不会漏计），同时 `origin` 又能把它与主动拉黑分开看。

### 9.4 上报点一律在「成功之后」

拉黑失败、举报失败、中途取消 —— **一条事件都不留**。
沿用 V1.1.2 code-review 抓到的教训：门控前就上报会让指标**系统性高估**
（当时的现象是「游客点受控 Tab 只弹了登录、页面根本没开，却记了一条浏览」）。
四条「失败/取消不上报」的断言都在 `test/analytics/v114_events_test.dart` 里。

### 9.5 明确不埋的

- **影子评论（R2）不做任何单独埋点。** 被拉黑方无感知是设计前提，针对他的行为埋点不改变产品行为，本版本不做。有测试断言全 `lib/` 下不存在任何 shadow 相关事件名。
- **举报的「其他」补充说明绝不进属性**（用户自由文本）。`Analytics.scrub` 有兜底，但不依赖兜底 —— 上报的是受控词表 `reason`，不是原文。有专门用例。
- 四个事件**都不加进 AppsFlyer 白名单**：拉黑/举报是治理行为，不是归因事件。

### 9.6 这些数字要回答的问题

| 指标 | 数据来源 |
|---|---|
| 工单总量 / 工单构成 | 后台统一工单视图（Epic 3），**非埋点** |
| 人均举报次数 | 工单上的「举报人数 / 举报次数」（Story 3.1 AC5），**非埋点** |
| 主动拉黑的真实使用量 | `social_user_hide_submitted` 里 `origin=BLOCK` 的部分 |
| 黑名单页举报入口使用量 | `social_blocklist_report_tapped` —— **长期为 0 就说明这个入口可以撤掉** |
| 客服「为什么刷不到某人内容」类咨询 | 既有客服工单（FR-52），**需人工归类，非埋点** |

**➜ PRD §4.3 的全部度量都能由「本节 4 个事件 + 后台工单数据」得出，无需再新增埋点。**

### 9.7 🚩 非代码交付项（**产品负责，代码这边做不了**）

> 🔄 **2026-08-16 产品改定（决策 C-105）：不做上线前基线，改为上线后先观测数量、再设定基线。**

| 阶段 | 周次 | 做什么 |
|---|---|---|
| 观测期 | 上线后第 1–2 周 | **只看不算** —— 存量骚扰账号的一次性清算 + 入口爬坡，计进基线会把它定歪 |
| 基线期 | 第 3–6 周 | 取周均为**稳态基线**（工单总量 / 工单构成 / 人均举报次数） |
| 回看 | 第 10 周 | 只回看一次，产出一条明确结论（达成 / 未达成 / **数据不足**）记入决策日志。**无论结果如何都要写，不留悬空** |
| 反向指标 | **第 1 天起** | 四条照常监控，任一触发即回看，**不等第 10 周** |

⚠️ **这条改动放弃了什么**：没有上线前的 before，**「工单总量不上升」这个主指标无法被证伪** ——
剩下的只有上线后自己跟自己比的相对趋势。**四条反向指标不受影响**（周环比 / 分布形状 / 绝对阈值都不依赖前置基线）。
埋点侧**无需任何改动**：本节 4 个事件在两种方案下都够用。

---

## 10. V1.1.6 新增埋点

本节随 V1.1.6 各 story 增量补齐；需求侧清单见
`_bmad-output/planning-artifacts/v1.1.6/埋点清单v116.md`（**两边须一致**）。

### 10.1 `push_permission_state_reported`（E-21 · Story 8.1）

| 项 | 值 |
|---|---|
| 时机 | **每次冷启动一次**，在本次启动的通知权限流程**落定之后** |
| 属性 | `granted`（bool）—— 当前系统通知开关状态 |
| 落点 | `lib/features/notify/domain/push_permission_state_reporter.dart`，由 `main.dart` 冷启动链路末尾调用 |

**这是 FR-85（推送权限重构）唯一的裁决指标。** 其余事件（E-19 提示曝光、E-20 用户响应）
只能解释过程 —— 「提示弹了多少次、各触发点响应如何」都回答不了「净授权率涨没涨」。
只有本事件的**趋势**能回答。

🔴 **它必须先于四个触发点上线**：晚一版不是晚点拿到数据，而是**改版前基线永久不存在**
（§8 的 Tab 改版正因缺基线，五项核心指标里三项作废）。

判读注意：

1. **按启动数算授权率**，所以「一次冷启动恰好一次」是指标准确性的前提 ——
   若 resume 也报，重度用户被反复计入，趋势会被拉向他们的个人状态。
   实现里的一次性闸是**进程内布尔、不落盘**（落盘会变成"这台设备永远只报一次"，趋势只剩一个点）。
2. **Android 13 以下没有「通知权限」这个概念**，`granted` 反映的是「用户有没有在系统设置里
   关掉通知」。低版本 Android 上 `true` 偏多属正常，**跨平台绝对值不要直接比**，看同群体趋势。
3. **ATT 未落定的启动也照实上报**。那条路径不会申请通知权限，但仍是一次真实观测；
   跳过会让分母缺失，且缺失非随机（只缺 iPad 兼容模式 / 请求被系统吞掉的设备）。
4. 事件名是 2026-08-18 按命名规范改名后的新名（旧名 `push_permission_state_snapshot`，
   `snapshot` 是名词、不符合"动作在词尾且须是动词"）。**本次是首次落地；一旦发版即锁死**
   —— 理由同 §8.9。

### 10.2 `publish_page_image_source_selected`（E-28 · 2026-08-20 用户要求）

| 项 | 值 |
|---|---|
| 时机 | 发布页点「Add」弹出来源 sheet 后，用户选了相机或相册（**sheet 已返回、真正去拍/去选之前**） |
| 属性 | `source`：`camera` / `gallery`（直取 `MediaSource` 枚举名，稳定受控字面量） |
| 落点 | `lib/features/content/presentation/publish_compose_page.dart` → `_pickImageSource` |

**为什么值得埋**：这两条路的成本完全不同 —— 相册是「我已经有照片了」，
相机是「我现在为发帖专门拍一张」。后者发布意愿更强，但也更容易在拍摄那一步流失。

判读注意：

1. **报的是「选了哪条路」，不是「取图成功」。** 挪到取图之后会变成成功率事件，
   而"选了相机却没拍成"恰恰是想观察的流失。
2. **点遮罩关掉 sheet 不报。** 否则占比的分母会混进"打开又关掉"的人。
3. 一个事件 + `source` 属性，不是两个事件 —— 与同页 `publish_page_content_type_selected`
   形状一致，看板里可直接对比占比。

### 10.3 `push_permission_prompt_shown` / `push_permission_responded`（E-19 / E-20 · Story 8.2）

| 项 | 值 |
|---|---|
| 时机 | 用户侧三点：**首次问诊后** · **建档后** · **打开通知中心**（各一次）· 兽医侧：**切换为在线**（Story 8.3，**不限次数**） |
| 属性（曝光） | `trigger_point`、`prompt_type` |
| 属性（响应） | `trigger_point`、`prompt_type`、`result` |
| 落点 | `lib/features/notify/domain/push_permission_prompt.dart`（判定与上报）· `push_permission_guide_flow.dart`（触发点 1/2 的抽屉）· `notification_center_page.dart`（触发点 4 的顶部条） |

**`trigger_point` 是这条 FR 的全部意义所在。** 产品 2026-08-14 决定「四个触发点先全做、
观察一个周期后再砍」，而**砍哪个留哪个的唯一依据就是按 `trigger_point` 拆分的响应分布**。
缺这个属性不是「埋点不全」，是**决策依据没了**。

取值：

| 属性 | 取值 |
|---|---|
| `trigger_point` | `first_consult` · `profile_created` · `notification_center` · `vet_online`（触发点 5，Story 8.3） |
| `prompt_type` | 🛡 **恒为 `in_app_guide`** |
| `result` | `granted` · `denied` · `settings_opened` · `dismissed` |

判读注意：

1. 🛡 **`prompt_type=native_dialog` 出现即实现违规**（AD-14 Rule 6）——
   原生弹窗的机会在首启就被第二代「首启即申请」消耗掉了，那条分支是死的。
   **这条可以配成线上告警**：一旦出现，说明有人绕过了 `PushPermissionPrompt`。
2. ⚠️ **`result=settings_opened` 只表示跳走了，不代表真的开了。**
   净授权率看 §10.1 的 E-21（冷启动快照）。两者别混着解读。
3. ⚠️ **触发点 3 不存在** —— PRD 编号是 1/2/4/5，不是漏了一个。
4. 🔴 **触发点 5（兽医切为在线，`trigger_point=vet_online`）不限次数**（Story 8.3）。
   一个兽医一天切五次在线就产生五次曝光 ⇒ **它的曝光量天然远高于用户侧三点**。
   横向对比四个触发点时**必须按人去重**，否则兽医那一点会把整体数据带偏 ——
   这是模型差异的必然结果，**不是埋点缺陷**，也**不代表触发点 5 效果最好**。
   兽医端与用户端的响应率**不可直接比**（模型不同、人群不同、动机不同）。
   ⚠️ 兽医端此前**全流程零埋点**（G-3）；本事件是那里的**第一个也是唯一一个**事件，
   其余兽医流程仍是黑盒。
5. 曝光与响应**配对使用**：`_shown` 是分母、`_responded` 是分子。
   两者都不 `await`（埋点不得阻塞用户流程 —— 这段逻辑夹在「建档完成 → 进首页」之间）。

### 10.4 V1.1.6 事件全表（E-1~E-28 · 收尾于 Story 10.1）

> 🔴 **本表列的是实现名。** 其中十条与 `埋点清单v116.md` 的原名不同 —— 那些名字在实现期
> 按本项目命名规范（§8.1）逐条订正过，PRD §3.2 都写了订正块与理由，**清单当时漏了同步**，
> 已随 Story 10.1 补回。看旧讨论/旧清单的人以本表为准。
>
> 🔴 **产品 2026-08-18 拍板的四个名字不在订正范围内**（`phone_prompt_responded` /
> `push_permission_responded` / `push_permission_state_reported` / `post_share_card_sent`）——
> 它们本身就是那次改名的产物。**一旦发版即锁死**（理由同 §8.9）。

| # | 事件名（实现） | 清单原名（若不同） | 属性 | 落点 |
|---|---|---|---|---|
| E-1 | `social_pinned_slot_viewed` | `feed_pinned_slot_viewed` | `pin_config_id`、**`pin_type`**、`content_id` | `feed_view.dart` |
| E-2 | `social_pinned_slot_tapped` | `feed_pinned_slot_tapped` | 同 E-1 + `jump_target` | `feed_view.dart` |
| E-3 | `social_pinned_duplicate_viewed` | `feed_pinned_duplicate_exposed` | `content_id`、`serp_position` | `feed_view.dart` |
| E-4 | `phone_prompt_shown` | — | **`trigger`** = `day3_open` | `phone_soft_prompt.dart` |
| E-5 | `phone_prompt_responded` | — | **`action`**: submitted/skipped/**dismissed** | 同上 |
| E-6 | `me_phone_save_succeeded` | `phone_saved` | **`entry`**、`is_first_time` | `phone_edit_sheet.dart` |
| E-7 | `me_phone_save_error_shown` | `phone_save_failed` | `entry` | 同上 |
| E-8 | `publish_image_crop_shown` | `publish_image_crop_required` | **`original_ratio`**、`batch_size` | `image_crop.dart` |
| E-9 | `publish_image_crop_completed` | `publish_image_crop_confirmed` | **`target_ratio`**、`is_batch_lock_source` | 同上 |
| E-10 | `publish_image_crop_exit_tapped` | `publish_image_crop_abandoned` | `original_ratio` | `publish_crop_page.dart` |
| E-11 | `post_share_card_tapped` | — | `content_type`、**`is_private_diary`**、`has_image` | `content_detail_page.dart` |
| E-12 | `post_share_card_generated` | — | `template`、`size`、**`duration_ms`** | `share_card_preview_page.dart` |
| E-13 | `post_share_card_sent` | （旧名 `..._share_completed` 已废） | `channel` | 同上（**系统回调之后**） |
| E-14 | `post_share_link_opened` | — | `open_method`、`ua_platform` | **服务端** `PostSharePageAnalytics` |
| E-15 | `user_badge_tooltip_opened` | — | `badge_id`、**`position`** | `user_tag_row.dart` |
| E-16 | `content_badge_tooltip_opened` | — | `badge_id`、**`position`** | `content_tag_chip.dart` |
| E-17 | `app_notification_center_viewed` | `notification_center_opened` | `unread_count`、**`push_permission`** | `notification_center_page.dart` |
| E-18 | `app_notification_item_tapped` | `notification_item_tapped` | **`notif_type`**、`level` | 同上 |
| E-19 | `push_permission_prompt_shown` | — | 见 §10.3 | `push_permission_prompt.dart` |
| E-20 | `push_permission_responded` | — | 见 §10.3 | 同上 |
| E-21 | `push_permission_state_reported` | — | **`granted`** | 见 §10.1 |
| E-22 | `post_like_tapped`（扩展） | — | `liked` + **新增 `source`** | `like_button.dart` |
| E-23 | `pet_card_share_tapped` | — | **`entry`**、`has_milestone` | `growth_archive_page.dart` |
| E-24 | `pet_card_link_opened` | — | **`page_state`**、`ua_platform`、`referrer_host` | **服务端** `CardPageAnalytics` |
| E-25 | `pet_card_cta_tapped` | — | **`page_state`**、`ua_platform` | 同上（浏览器 → `/p/track`） |
| E-26 | `pet_card_cta_outcome` | — | **`outcome`** | 同上（**尽力上报、会丢**） |
| E-27 | `signup_succeeded`（扩展） | — | `entry_source` **新增 `pet_card`** | `login_guide_controller.dart` |
| E-28 | `publish_page_image_source_selected` | — | **`source`** | 见 §10.2 |

**三条口径，缺一条对应结论就做不出来：**

1. **E-12 与 E-13 是两个事件，不得合并。** E-12 报「出图成功」（`duration_ms` 衡量基建性能），
   E-13 报「系统分享菜单**回调成功**」。合成一个 = 每次预览导出都算一次分享，
   「实际分享出去多少」只会被高估，且**事后无法修正**。
   ⚠️ Story 9.3 当初就是合成一个的，Story 10.1 拆开了。
2. **E-4 是 E-5 的分母。** 拿 E-5 的条数当分母算响应率会系统性高估
   —— 那是"作答数"，与"曝光数"在崩溃/杀进程/埋点丢失时并不相等。
3. **`open_method` 靠二维码印的那份 URL 带 `?src=qr`。**
   码里和文字里印同一个 URL，服务端就永远分不出扫码与点链接 ——
   而这是**下载二维码唯一的验收依据**（它占了卡片页脚近一半版面）。

### 10.5 🛡 六项已知缺口：本版本**不解决、不承诺**

> **写明缺口不是免责声明，是防止误判。** 看板上没数据的时候，分析的人必须能立刻分清
> 「埋点坏了」和「这里从来没埋」。否则每次都要白查一轮。

| # | 缺口 | 说明 |
|---|---|---|
| G-1 | **深链归因基建缺失** | 未装 App 的用户「安装 → 打开 → 注册」这一段拼不出来（AD-16 Rule 5）。E-27 的 `pet_card` **只覆盖已装用户**（深链直接进 App）。要覆盖未装用户需要 deferred deeplink 一整套基建，不在本版本。 |
| G-2 | **兽医端全流程仍零埋点** | 本版本触发点 5（Story 8.3 · `trigger_point=vet_online`）是兽医端**第一个也是唯一一个**事件。派单、接单、回复、结束会话 —— 全是黑盒。 |
| G-3 | **stag / prod 共用同一个 project token** | 打包时须用构建参数覆盖。忘了覆盖 ⇒ 测试流量混进生产看板，且**无法事后剔除**（事件里没有环境维度）。 |
| G-4 | **后台侧完全无埋点** | 顶置配置、装饰标签打标、用户标签分配、批量催填 —— 这些**运营操作全无记录**。一旦出现「这条内容为什么被顶置了」「谁给这个用户打了标签」的争议，**没有任何客观依据可查**。⚠️ 出事时会先被当成"埋点坏了"去查，白费一轮 —— 所以这条尤其要写在这里。 |
| G-6 | **没有曝光类埋点 ⇒「人均浏览深度」做不出来** | 🔴 数据侧从来没有 Feed 曝光事件（内容进入视口时上报）。后果有两处：① 灰度观测建议里的「人均浏览深度」这个指标**做不出来**；② 推荐算法的曝光衰减只能用「序列下发即记曝光」的口径（Story 16.1 AC2），代价是"下发但用户没滚到"的内容也被记入。🛡 Story 16.5 明确**不补**曝光埋点 —— 补它要一整套视口上报 + 回传，且量级远大于现有任何事件。 |
| G-5 | **访客登录态属性做不出来** | `viewer_state`（访客是否已有账号）与 `is_app_installed`：H5 是无登录态公开页，服务端判不出来。🛡 **不得写进任何验收标准**（AD-16 Rule 4）—— 这不是「暂时没做」，是**做不出来**。也**不要拿 cookie 有无去猜新老访客**，那不是同一回事。已由 `PostSharePageAnalyticsIntegrationTest` 与 `CardPageAnalyticsIntegrationTest` 各钉一条「不得出现这两个属性」的测试。 |

### 10.6 可配成线上告警的两条护栏

两条都是**「实现违背规格」型**，与 §8.6 的 T-12 是同一类用法：出现即说明代码被绕过，
而不是数据异常。

| # | 出现即异常的组合 | 为什么 | 怎么查 |
|---|---|---|---|
| 护栏一 | `push_permission_prompt_shown` 且 `trigger_point=notification_center` 且 `prompt_type=native_dialog` | FR-85 于 2026-08-05 定稿「触发点 4 **永不**唤起原生弹窗」（原生弹窗机会留给 1/2/5）。AD-14 Rule 6。 | PostHog 建一个该组合的 insight，阈值 > 0 即告警。命中说明有人绕过了 `PushPermissionPrompt`。 |
| 护栏二 | 同一设备同一用户出现**多条** `push_permission_prompt_shown` 且 `trigger_point ≠ vet_online` | 除兽医端外，每个触发点每设备每用户**最多一条**。重复即 AD-14 的四个标记键实现有误。 | 按 `distinct_id` + `trigger_point` 分组计数 > 1。🔴 **必须排除 `vet_online`** —— 触发点 5 不受"各一次"限制（2026-08-05 确认），不排除会天天误报。 |

**§8.6 那条 V1.1.2 的护栏继续有效**：健康类里程碑（M3/M4/M5/M9）出现
`path=checkin` 的 `milestone_achieved`，说明"取消手动打卡"的后端护栏被绕过。

### 10.7 明确不做（本版本范围外）

- **后台侧埋点** —— 见 G-4，本版本只写明缺口
- **兽医端其余流程埋点** —— 见 G-2
- **深链归因基建** —— 见 G-1，需独立基建


### 10.8 `feed_tab` / `rank_mode`（Story 16.5 · FR-95）

首页推荐算法上线后，Feed 相关事件补两个属性。**两个都要有，缺一个就都不带。**

| 属性 | 取值 | 谁给的 |
|---|---|---|
| `feed_tab` | `all` / `daily` / `moment` / `tips` | 客户端（当前分类 Tab） |
| `rank_mode` | `recommend` / `chrono` / `mixed` / `unknown` | 🔴 **服务端下发**（`FeedPageResponse.rankMode`） |

**🔴 为什么 `rank_mode` 必须服务端给、客户端推不出来**

降级链级别 4 会让 **ALL Tab 也走时间倒序**，而那对客户端完全无感。
客户端按「是不是 ALL Tab」自己判断的话，降级期间的数据会被算进推荐序的效果里 ——
而那正是 FR-95 参数校准要看的数（参数第一次校准必然在发版之后，OQ-B1）。

**🔴 为什么只有 `feed_tab` 不够**

降级时 `feed_tab=all` 但 `rank_mode=chrono`。只看 tab 会把两条排序路径的数据混在一起。

**两个"说不清"的取值，都是有意保留的**

- `mixed` —— 同一次刷新里各页路径不一致（首屏推荐序、第二页恰好降级）。
  挑一边冒充会污染归因：记成 `recommend` 就把降级期间的数据算进效果里，
  记成 `chrono` 又把推荐序的首屏效果丢掉。
- `unknown` —— 服务端没下发（老客户端遇到新后端不会有这个问题；反过来会）。
  🛡 **不默认成任何一边**，猜错哪边都会污染归因。

分析时：算推荐序效果只取 `rank_mode=recommend`；`mixed` 与 `unknown` **整批筛掉**。

**带这两个属性的事件**

| 事件 | 为什么需要 |
|---|---|
| `post_like_tapped`（`source=feed` 时） | 首页点赞是效果归因的**主要信号**（无曝光埋点，见 G-6） |
| `social_pinned_slot_viewed` / `_tapped` | 顶置位在两条排序路径下的表现不同 |
| `social_pinned_duplicate_viewed` | 🔴 尤其需要：降级到时间倒序时 `serp_position` 根本不是算法排的，混在一起看会得出错误结论 |

🛡 **详情页的点赞不带这两个属性**（`source=detail`）—— 那里没有「哪个 Tab、哪条排序路径」
这回事，硬填会在看板上造出不存在的 Feed 会话。

🛡 **「我的发布」不带 `rankMode`**（服务端返回 null，Jackson 省略）——
给它填个 `chrono` 会让它被算进首页排序的分母里。

### 10.9 `id_card_share_*`（Story 18.2 · FR-96 身份证分享奖励）

三个事件，命名按 `模块_对象_动作`：`id_card`（模块）+ `share`（对象）+ 动作。

| 事件 | 何时报 | 属性 | 判读 |
|---|---|---|---|
| `id_card_share_tapped` | 点卡面页的「分享」按钮（截图之前） | `card_style`（0=KTP / 1=护照 / 2=学生卡） | 漏斗起点；顺带看哪种卡面更愿意被分享 |
| `id_card_share_sent` | 系统分享面板回调 **success** | `channel` | 真的分享出去多少、走哪个渠道 |
| `id_card_share_rewarded` | 上报发放结果之后 | `rewarded`（bool） | 发放命中率 |

**判读要点：**

- `tapped → sent` 的落差就是「打开了面板又取消」。⚠️ 这个落差**不能当流失率看**：
  部分平台压根不回调 success（`CardExport.shareImage` 那段注释记了这件事），
  所以 `sent` 是**低估**的。
- `rewarded=false` 的占比会**很快趋近 100%**，这是设计使然而不是故障：
  奖励按**宠物档案**去重、一个档案一辈子只发一次（Story 18.2 · AC4）。
  所以这个属性要看的是**新用户首次分享**那一段，不是全时段平均。
- 🛡 **刻意没有「为什么没发」这个属性**。服务端也不返回原因（档案已拿过 / 日上限 /
  月度上限 / 总开关关闭对客户端是同一件事）。
  🔴 有了原因就会有人把它做成「你的额度用完了」的文案，而那会诱导
  「攒着别分享」或「月初集中刷满」——这正是 18.1 AC3 明令不告知的原因。

**⚠️ 不要与这两个事件混：**

| 别混的 | 是什么 |
|---|---|
| `pet_card_share_tapped` | **宠物主页名片**（FR-92 的对外 H5），不是身份证卡面 |
| `post_share_card_tapped` | **单条内容分享卡**（FR-73） |

三者都是"分享"，但对象、落地页、付费边界完全不同。
