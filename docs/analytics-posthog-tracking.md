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

**distinctId = `sha256("tailtopia-user-" + 内部用户id)`**（`analytics.dart:115`），不传任何 `userProperties`。

已知取舍（代码注释里写明）：这是**无盐** sha256，持 PostHog 读权限的人可以暴力反推回内部自增 id。V1 接受——该 id 既非 PII 也非健康数据，且 distinctId 不是对外 API 面无枚举风险。若要真正不可枚举，应由后端下发不透明分析 token。

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

- 模块前缀：`app_` `bottom_nav_` `diary_` `discovery_` `health_` `publish_` `me_` `signup_` `milestone_`
- 动作后缀：`_viewed` `_shown` `_tapped` `_selected` `_toggled` `_switched` `_succeeded` `_completed` `_achieved`

目的：产品在看板上**一眼看出这是哪个页面的哪个按钮**，不必回来问工程。
反例（本轮改掉的）：`tab_switched` 分不清底部导航还是页内 Tab；`diary_sync_toggled`
听着像 Diary 页上的开关、其实在发布页。约定由
`petgo_app/test/analytics/v112_events_test.dart` 的断言守着，起名不合规会红。

⚠️ **Tab 的枚举名与产品叫法不一致**，埋点一律用产品叫法（`AppTab.analyticsName`）：

| 代码里的枚举 | 埋点/看板里的名字 | 产品叫法 |
|---|---|---|
| `AppTab.profile` | `diary` | Diary（成长日记） |
| `AppTab.triage` | `health` | Health（健康/问诊） |
| `AppTab.home` | `discovery` | Discovery（发现） |
| `AppTab.me` | `me` | Me（我的） |

### 8.2 本版本修掉的 P0 缺口：Tab 切换此前完全没有浏览事件

底部 Tab 走 `StatefulShellRoute.goBranch` 切分支、**不 push 根路由** → §2 里的
`PosthogObserver` 收不到 `didPush`，四个 Tab 根页一个 `$screen` 都没有。后果是
「落地页分流是否生效」无法验证 —— 而落地页矩阵正是 V1.1.2 的核心改动。

修法：`Analytics.screen()` 显式补一条，屏名 `<产品名>_page`（`diary_page` / `health_page` /
`discovery_page` / `me_page` / `vet_workbench_page`）。冷启动落地页用**同一套字面量**，
否则「冷启动落在 Diary」与「切到 Diary」会被算成两个不同页面。详情页仍由 observer 自动上报。

### 8.3 事件清单（T-1~T-12，**T-5 已删且编号不重分配**）

| # | 事件名 | 一句话（产品视角） | 属性 | 代码位置 |
|---|---|---|---|---|
| T-1 | `app_launch_landed_on_tab` | 冷启动后落在了哪个 Tab | `tab`（diary/discovery/vet_workbench）、`user_state` | `app_router.dart` splash 回调 |
| T-2 | `bottom_nav_tab_switched` | 点了底部导航切 Tab | `from_tab`、`to_tab`、`user_state` | `app_shell.dart` |
| T-3 | `diary_guest_page_viewed` | 未登录用户看到了 Diary 游客种草页 | `session_first`（是否本次启动首次看到） | `diary_guest_page.dart` |
| T-4 | `diary_guest_create_profile_cta_tapped` | 游客点了任一「建档引导」入口 | `source` | `diary_guest_page.dart` / `diary_demo_detail_page.dart` |
| T-6 | `discovery_soft_login_sheet_shown` / `..._login_tapped` | Discovery 刷到第 3 页弹的软登录浮层：曝光 / 点了登录 | 后者带 `method`（google/apple） | `login_guide_controller.dart` |
| T-7 | `signup_succeeded` | **注册真正成功**（不是点了按钮） | `entry_source` | `login_guide_controller.dart` / `login_page.dart` |
| T-8 | `publish_page_content_type_selected` | 发布页选了内容类型 | `type`、`is_default`、`has_pet_profile` | `publish_compose_page.dart` |
| T-9 | `publish_page_sync_to_moment_toggled` | 发布页拨了「同步到 Moment」开关 | `enabled` | `publish_compose_page.dart` |
| T-10 | `diary_timeline_item_tapped` | 点了 Diary 时间线上的某条 | `item_type` | `growth_archive_page.dart` |
| T-11 | `diary_view_mode_switched` | Diary 在时间线 ⇄ 日历之间切换 | `to_view`（timeline/calendar） | `growth_archive_page.dart` |
| T-12 | `milestone_achieved` | 里程碑达成（**后端上报**） | `code`、`level`、`path` | `MilestoneAnalyticsListener.java` |

属性取值词表：

- `user_state`：`guest` / `vet` / `owner_with_profile` / `owner_without_profile` / `planning` / `enthusiast`
  （即 `AppUserState.wire`。⚠️ Story 6.1 AC3 原表写的是 `A_with_profile`/`B`/`C` —— 实现取了
  枚举自述名：语义等价、可读性更好，且与落地矩阵同源。**看板以此为准**。）
- `source`（T-4）：`bottom_sticky_cta`（底部常驻主按钮）/ `timeline_item`（示例时间线条目与金徽章）/
  `demo_detail_interaction`（示例详情页点赞·评论·举报）/ `header_entry`（页头四个入口）
- `entry_source`（T-7）：`diary_cta`（游客态 Diary 引导）/ `discovery_soft_login`（软登录浮层）/ `login_page`（登录页直登）
- `item_type`（T-10）：`HAPPY_MOMENT` / `HAPPY_MOMENT_MILESTONE` / `MILESTONE_BANNER` / `HEALTH_EVENT` / `ID_CARD_ISSUED`
  —— **直取后端下发的 `itemType`**（AD-2），前端不自行推断
- `path`（T-12）：`health_record`（疫苗/驱虫/绝育记录触发）/ `consult`（真人兽医问诊结束触发）/
  `checkin`（用户手动打卡）/ `publish`（发布内容回填）/ `system_auto`（计数、组合、档案创建等）

### 8.4 服务端埋点（本版本首次出现）

§7.6 记的「后端零埋点」这一条本版本**局部打通**：里程碑达成的判定全在服务端
（健康记录事件、兽医问诊关闭、计数阈值、组合解锁），客户端看不到「这次是走哪条路径点亮的」，
前端补不了这一环。

- 实现：`shared/analytics/AnalyticsClient`（接口）+ `PostHogAnalyticsClient`（HTTP `POST /i/v0/e/`）。
  **没有引入第三方 SDK** —— capture 就是一个 HTTP POST，用既有 `RestClient` 十几行够了；
  引 SDK 要多背一条供应链依赖 + 它自带的线程池，与「异步只用 `@Async`、不加中间件」的护栏也别扭。
- 触发：订阅既有领域事件 `MilestoneCompletedEvent`（`@TransactionalEventListener` + `@Async`）。
  **提交后才上报**：事务回滚了看板上却多一条达成，比没有更糟。
- 配置：`POSTHOG_SERVER_KEY`（env，留空 = 整个上报静默关闭、不出网）、`POSTHOG_HOST`。
  必须与 App 端同一个 project，否则前后端事件落在两个项目里、漏斗拼不起来。
- `distinctId` = `sha256("tailtopia-user-" + 内部用户id)`，**与客户端逐字一致**，两端各有一条
  已知向量断言（差一个字节，同一个人会被算成两个人）。
- 失败即放弃，不重试：埋点是可损数据，为它加重试/补偿会引入状态机与新表，代价远大于收益。

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
