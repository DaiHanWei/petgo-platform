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

## 8. V1.1.2 新增埋点（Story 6.1 · 2026-08）

> 回写依据：V1.1.2 Story 6.1（Epic 6 唯一 story）。代码位置见下表。
> ⚠️ **产品文档同步**：Story 的 D1 要求把本节并入产品侧全局清单 `3.数据埋点/数据v100-v110.md`。
> 那份文档**不在本仓库**（在产品文档空间），本节是工程侧的权威副本，需人工镜像过去。

### 8.1 本版本修掉的 P0 缺口：Tab 切换此前完全无浏览事件

底部 Tab 走 `StatefulShellRoute.goBranch` 切分支，**不 push 根路由** → §2 里那个 `PosthogObserver`
收不到 `didPush`，**四个 Tab 根页此前一个 `$screen` 都没有**。后果是「落地页分流是否生效」
无法验证 —— 而落地页矩阵正是 V1.1.2 的核心改动。

修法：`Analytics.screen(name)` 显式补一条，屏名用受控字面量 `tab_<AppTab.name>`；
冷启动落地页用**同一套字面量**（`_landingScreenNames`），否则「冷启动落在 Diary」与
「切到 Diary」在看板上会被算成两个不同页面。详情页仍由 observer 自动上报，两者不重复。

### 8.2 事件清单（T-1~T-12，**T-5 已删且编号不重分配**）

| # | 事件 | 属性 | 触发点 | 代码位置 |
|---|---|---|---|---|
| T-1 | `app_landing_tab` | `tab`（落地路径）、`user_state` | 冷启动 splash 完成、落地前 | `app_router.dart` splash 回调 |
| T-2 | `tab_switched` | `from_tab`、`to_tab`、`user_state` | 点任一底部 Tab | `app_shell.dart _onTabSelected` |
| T-3 | `diary_guest_view` | `session_first` | 游客态 Diary 曝光（`initState`） | `diary_guest_page.dart` |
| T-4 | `diary_guest_cta_tapped` | `source` | 游客态四类引导入口 | `diary_guest_page.dart` / `diary_demo_detail_page.dart` |
| T-6 | `soft_login_prompt_shown` / `soft_login_prompt_tapped` | 后者带 `method`（google/apple） | FR-0B 软登录浮层曝光 / 点主 CTA | `login_guide_controller.dart` |
| T-7 | `signup_completed` | `entry_source` | **注册真正成功**（`isNewUser=true`） | `login_guide_controller.dart` / `login_page.dart` |
| T-8 | `publish_type_selected` | `type`、`is_default`、`has_pet_profile` | 发布页切内容类型 | `publish_compose_page.dart` |
| T-9 | `diary_sync_toggled` | `enabled` | 切「同步到 Moment」开关 | `publish_compose_page.dart` |
| T-10 | `timeline_item_tapped` | `item_type` | 点时间线任一条目 | `growth_archive_page.dart _realTapFor` |
| T-11 | `archive_view_switched` | `to_view`（timeline/calendar） | 时间线 ⇄ 日历 | `growth_archive_page.dart _switchView` |
| T-12 | `milestone_completed` | `code`、`level`、`path` | 里程碑完成 | **未实现**，见 §8.5 |

外加两条 `$screen`（§8.1）：`tab_profile` / `tab_triage` / `tab_home` / `tab_me`、`vet_workbench`。

### 8.3 三条口径约定（改动前先读）

1. **`user_state` 只有一个判定源**：`AppUserState`（`features/auth/domain/user_state.dart`）。
   落地分流与埋点共用它，取值即 `AppUserState.wire`：
   `guest` / `vet` / `owner_with_profile` / `owner_without_profile` / `planning` / `enthusiast`。
   ⚠️ Story 6.1 AC3 原表写的是 `A_with_profile`/`A_no_profile`/`B`/`C` —— 实现取了枚举的自述名
   （语义等价、可读性更好、且天然与落地矩阵同源）。**看板配置以本节为准**。
2. **T-4 是「一个事件 + `source`」，不拆成多个事件**。拆了转化率的分母就碎了。
   取值：`main_cta`（常驻主 CTA）、`timeline_item`（示例时间线非图条目 + 金徽章）、
   `detail_interaction`（示例详情页点赞/评论/举报）、`header_entry`（页头四个入口 ——
   Story 2.2 列举三类入口时漏了它，实际是第四个引导点）。
3. **T-10 的 `item_type` 直取后端下发的 `itemType`**（Story 3.2 / AD-2，五值：
   `HAPPY_MOMENT` / `HAPPY_MOMENT_MILESTONE` / `MILESTONE_BANNER` / `HEALTH_EVENT` / `ID_CARD_ISSUED`），
   前端**不另行推断**，否则埋点口径与展示口径会飘。

### 8.4 已下线

- **FR-0H 首页建档提示条**的曝光 / 点击 / 关闭事件：提示条本体已在 Story 2.3 整条废止
  （AD-15 Rule 3），相关看板指标一并下线。核查结论：代码里无残留事件。

### 8.5 未实现 / 需决策

- **T-12 `milestone_completed`（`path` 区分 health_record / consult / checkin / system_auto）**：
  里程碑达成判定全在后端（`MilestoneAutoCompleteListener` 等），而**后端至今零埋点 SDK**（见 §7.6）。
  引入服务端 PostHog 客户端属于「新增依赖」，需产品/技术负责人拍板 —— 本 story 未做。
  受影响的还有 Story 6.1 AC5 的线上校验（「健康类四条不应再出现 `path=checkin`」）：
  该校验依赖 T-12，因此**暂时无法作为线上信号**，Epic 5 的护栏目前只由后端单测把守
  （`HealthMilestoneCheckInRefusedTest`，那是更强的保证：请求直接被拒）。
- **T-7 的 `entry_source` 由前端上报**（不是 story 里写的后端 B1，同上原因）。
  取值：`diary_cta`（游客态 Diary 引导）、`soft_login`（FR-0B 浮层）、`other`（登录页直登）。

### 8.6 PRD §3 指标的失效标注（AD-6）

不取改版前基线 → PRD §3.3 五项核心指标里**三项拿不到**，随之 §3.3「唯一裁决指标」与
§3.4 处置原则**一并失效**：

| 指标 | 状态 |
|---|---|
| 游客→注册总转化率（改版前后对比） | ❌ 不可得（无改版前基线） |
| FR-0B 曝光量变化 | ❌ 不可得（同上） |
| B/C 用户留存（改版前后对比） | ❌ 不可得（同上） |
| 转化路径构成（`entry_source` 占比） | ✅ 可用（绝对值） |
| Diary 主动转私密率（`diary_sync_toggled` enabled=false 占比） | ✅ 可用（绝对值）——本版本最关键的产品假设验证 |

埋点仍要做（为以后攒数据），但**别指望它回答「这次改版是对是错」**。

### 8.7 补上了 §7.5 的缺口：埋点有单测了

`petgo_app/test/analytics/v112_events_test.dart`（14 例）。观察手段是
`Analytics.debugCaptureSink`（`@visibleForTesting`，挂在 `scrub()` **之后**），
所以断言看到的就是端上真正发出的形态。锁住：事件名/属性名 snake_case、
`user_state` 取自枚举、`item_type` 与后端词表逐字一致、T-4 不被拆成多个事件、
曝光埋点位于 session 去重之后、登录取消不算注册成功。
