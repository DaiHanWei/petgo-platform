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
