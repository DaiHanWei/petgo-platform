# Spec：日报接入 Firebase（GA4）日活 + 改 13:00 推送

> 版本线：V1.3.2（随 1.3.2 发版）｜状态：**implemented（L0）**｜日期：2026-10-06｜分支 `feat/1.3.2-ga4-dau`
> 来源：2026-10-06 与 Dai 讨论。AppsFlyer 试用到期（会清历史数据并收费），日活改由 Firebase 统计；归因另议，**不在本 spec 范围**。

## 1. 背景与目标

- 日报（飞书群，`admin/dailyreport`）现有「昨日日活」= 服务器口径：**登录用户**调过任意 `/api/v1/` 接口（`user_active_days`，按 WIB 自然日）。**不含游客**。
- 目标：日报增加「**日活（含游客）**」，数据源 = Firebase / GA4 的 `activeUsers`（iOS + Android 合计，含未登录设备）。
- 日报只给内部看；对投资人的可信口径是让其直接看 Firebase / 商店后台，**不是**日报。
- GA4 普通版「完整日数据」处理约 12 小时（官方 Data freshness：Standard intraday 2–6h、Daily ~12h、24–48h 内可能修正）。09:00 推送拿不到定稿 ⇒ **日报推送改到 13:00 WIB**（Dai 2026-10-06 拍板）。

## 2. 范围

| 侧 | 做什么 |
|---|---|
| App | 接入 `firebase_core` + `firebase_analytics`（只开自动采集，不加自定义事件、不设 userId） |
| 后端 | 日报新增 GA4 日活字段；推送时间默认改 13:00 WIB |
| 人工（Dai） | Firebase / GA4 / Google Cloud 后台配置，见 §6 |

**不做**：归因（Meta/TikTok/Google Ads）、移除 AppsFlyer SDK、改评论率/like 率分母。

## 3. App 端

### 3.1 依赖（已实测）
- `firebase_core: ^4.15.0`、`firebase_analytics: ^12.6.0`（2026-10-06 在 dev_1.3.2 上 `flutter pub add` 可解析、release 构建通过）。
- **包体实测（Android arm64 release）**：42.82 MB → 43.27 MB，**+0.45 MB（≈1%）**。Android 已有 Firebase 基础（FCM，项目 `tailtopia-ba4f7`，`google-services.json` 已入库、gradle 插件已接）。
- iOS 目前**没有** Firebase：需要 Dai 提供 `ios/Runner/GoogleService-Info.plist`（§6），预估 +1–2 MB（未实测）。
- 🔴 **iOS 最低版本 14.0 → 15.0**（Dai 2026-10-06 拍板）：`firebase_core` 4.x（Firebase iOS SDK 12）要求 iOS 15。iOS 15 与 iOS 14 支持的机型完全相同（iPhone 6s / SE1 起），只影响「能升没升系统」的用户；他们保留已装版本、收不到新版。已改 `ios/Podfile` 与 `project.pbxproj` 三处 `IPHONEOS_DEPLOYMENT_TARGET`。iOS 插件走 SPM（`Package.resolved` 入库）。
- `pubspec.lock`：本机 Flutter SDK 会把 intl/meta/test 等 7 个包降级，已手工还原为仓库原版本，lock 只新增 firebase 相关 7 个包。

### 3.2 初始化
- 在 `main.dart` 首帧后的 `addPostFrameCallback` 里（与 AppsFlyer 同处），**不阻塞首帧**：
  `Firebase.initializeApp()` 包 `timeout(3s)` + `try/catch` 吞错，失败只 `debugPrint`，绝不影响启动（同 `AppsFlyerClient.init` 口径）。
- ⚠️ 不要放在 `runApp` 之前 await（2026-08-07 冷启动治理已把 SDK 初始化全部移到首帧后）。
- 与 TIMPush 的原生 FCM 共存：TIMPush 用原生 Firebase 默认 App；Flutter 侧 `initializeApp()` 拿到的是同一个默认 App，**必须真机验证推送不受影响**（L2）。

### 3.3 采集开关（防测试数据污染生产口径）
- 新增 dart-define `FIREBASE_ANALYTICS_ENABLED`（`bool.fromEnvironment`，**默认 true**）。
- `kDebugMode` 下一律 `setAnalyticsCollectionEnabled(false)`（开发自测不进生产统计）。
- Android debug manifest（`src/debug/AndroidManifest.xml`）默认 `firebase_analytics_collection_enabled=false`，兜住 Dart 初始化之前的原生自动采集。
- 实现：`lib/core/analytics/firebase_stats.dart`（`FirebaseStats.init()`，`main.dart` 首帧后独立 `unawaited` 调用，与 AppsFlyer/ATT 链路互不等待）。
- stag 包：在 **stag 分支专属**改动里让 `scripts/build-stag-apk.sh` 传 `--dart-define=FIREBASE_ANALYTICS_ENABLED=false`（与 AppsFlyer/PostHog 的 stag 关停同口径，**勿合回 dev/main**）。
- 不调用 `setUserId`、不传任何用户属性（隐私红线 TT-DPR-2026-001；日活只需设备维度）。

### 3.4 合规（非代码，发版前必须完成）
- 隐私政策（legal.tailtopia.id）补充 Google Analytics for Firebase。
- App Store 隐私标签 / Play 数据安全表单：补「应用互动 / 设备 ID 用于分析」。
- 不需要 ATT（不用 IDFA 做广告）。

## 4. 后端

### 4.1 GA4 客户端（新类，`admin/dailyreport/Ga4ActiveUsersClient`）
- 接口：`POST https://analyticsdata.googleapis.com/v1beta/properties/{propertyId}:runReport`
  ```json
  { "dateRanges": [{ "startDate": "2026-10-05", "endDate": "2026-10-05" }],
    "metrics": [{ "name": "activeUsers" }] }
  ```
  返回 `rows[0].metricValues[0].value`（字符串整数）；无 rows ⇒ 0。
- 鉴权：服务账号 JSON → OAuth2 access token，scope `https://www.googleapis.com/auth/analytics.readonly`。
  **实现取舍**：不引 google-auth-library，JWT bearer（RS256）用 JDK `Signature` 自签换 token，HTTP 用 JDK `HttpClient`（与 `LarkWebhookClient` 同款，零新依赖）。token 有效期内复用（提前 60s 刷新），401 清缓存。
- 日期按 **GA4 属性时区**解释 ⇒ 属性时区必须设为 `Asia/Jakarta`（§6），与日报 WIB 自然日对齐。
- 超时 10s；不重试风暴（失败即返回 null）；日志**不得**打印 token / 密钥 / 完整响应体。
- 未配置（propertyId 或凭证为空）⇒ 直接返回 null，不报错（本地/测试天然关闭）。

### 4.2 配置（env 注入，凭证不入库）
```yaml
petgo:
  daily-report:
    cron: ${LARK_DAILY_REPORT_CRON:0 0 13 * * *}   # 原 0 0 9 * * *
    ga4:
      property-id: ${GA4_PROPERTY_ID:}
      credentials-b64: ${GA4_SA_KEY_B64:}          # 服务账号 JSON 的 base64（env-file 不便放多行 JSON）
```
- `.env.example` 只放占位。
- ⚠️ 部署时检查 prod / stag env 文件里**是否覆盖了** `LARK_DAILY_REPORT_CRON`（若有 9 点的覆盖值要一并改，否则改默认值不生效）。
- `DailyReportJob` 的 `@Scheduled(zone = "Asia/Jakarta")` 不变。

### 4.3 日报数据与卡片
- `DailyReport` 增加 `Long ga4Dau, Long ga4DauPrevious`（可空，挂在 DailyReport 而非 Metrics——Metrics 是服务器 SQL 口径，GA4 是外部源；保留 3 参构造兼容）；`DailyReportService.getDailyReport` 对 `day` 与 `day-1` 各查一次 GA4（环比）。
- GA4 调用异常 / 未配置 ⇒ `ga4Dau = null`，卡片显示「—」，**日报照常推送**（与现有 `dau == null` 口径一致）。
- 卡片「👥 用户」段：
  | 字段 | 来源 |
  |---|---|
  | 昨日新增 | 不变 |
  | **日活（含游客）** | GA4，带环比 |
  | **登录用户日活** | 原「昨日日活」改名，服务器口径，带环比 |
- 评论活跃率、like 活跃率**分母不变**（登录用户日活——只有登录用户能评论/点赞）。
- 底部 `NOTE` 追加：「日活（含游客）来自 Firebase（GA4），按设备计、含未登录用户，GA4 数据 24–48 小时内可能小幅修正；登录用户日活为服务器口径」。
- 卡片文案是 `DailyReportCard` 里的中文常量（不走 i18n）；`AdminDailyReportController` 只返回 pushed/date，无需改。

## 5. 分支

- ⚠️ `dev_1.3.2` 落后 `main` 7 个提交，其中 `efe72499` 改了日报（新增「自动评论成功数」，动了 `DailyReport` / `DailyReportCard` / `DailyReportQuery`）。**先把 main 合进 dev_1.3.2**，再从 dev_1.3.2 切 `feat/1.3.2-ga4-dau`，否则必冲突。
- 无 Flyway 迁移。

## 6. 人工前置（Dai，在 Google 后台做）

1. Firebase 控制台（项目 `tailtopia-ba4f7`）→ 启用 / 关联 **Google Analytics**（新建 GA4 属性即可）。
2. GA4「管理 → 媒体资源设置」：**时区 = 雅加达**，币种 IDR。记下**媒体资源 ID**（纯数字）→ `GA4_PROPERTY_ID`。
3. Firebase 添加 **iOS 应用**（Bundle ID `com.tailtopia.app`）→ 下载 `GoogleService-Info.plist` 交给开发。
4. Google Cloud（同项目）→ 启用 **Google Analytics Data API** → 新建服务账号 → 生成 JSON 密钥。
5. GA4「管理 → 媒体资源访问管理」→ 把服务账号邮箱加为**查看者**。
6. JSON 密钥 base64 后写入服务器 `~/.env.petgo` / `~/.env.petgo-stag` 的 `GA4_SA_KEY_B64`（不经聊天、不入库）。

## 7. 验收

| AC | 层级 | 环境 |
|---|---|---|
| `Ga4ActiveUsersClient` 解析正常响应 / 空 rows=0 / 4xx·5xx·超时 → null（MockRestServiceServer） | L0 | 无 |
| 未配置时不发请求、返回 null | L0 | 无 |
| 卡片：两个日活字段与环比；ga4Dau=null 显示「—」；活跃率分母仍为登录日活 | L0 | 无 |
| cron 默认值为 `0 0 13 * * *` | L0 | 无 |
| i18n 四语齐、`AdminMessagesParityTest` 绿 | L0 | 无 |
| `flutter analyze` / `flutter test` 绿；Firebase 初始化失败不影响启动（测试环境无原生插件天然走降级） | L0 | 无 |
| stag 用真实凭证手动推一次日报，GA4 日活为真实数字 | L1 | stag + §6 完成 |
| 真机 release 包：Firebase DebugView 收到 `first_open` / `session_start`；**推送（TIMPush/FCM）仍正常** | L2 | 真机（Android + iOS） |
| debug 包 / stag 包不向生产 GA4 上报 | L2 | 真机 |

## 8. 已知限制

- 统计从**装了新版**的用户开始；发版后前几周覆盖率爬升，GA4 日活会显著低于真实值，属预期。
- GA4 `activeUsers` = 有有效互动的用户（engaged），与 AppsFlyer「打开过」口径略有差异。
- 13:00 仍可能有小幅修正（官方 24–48h），日报数字以「当时值」为准，不回写。

## 9. 合 stag 时的待办（stag 分支专属，勿合回 dev/main）

- `scripts/build-stag-apk.sh` 两种模式都追加 `--dart-define=FIREBASE_ANALYTICS_ENABLED=false`（同 AppsFlyer / PostHog 的 stag 关停口径）。
- stag env（`~/.env.petgo-stag`）可配同一 GA4 凭证用于 L1 验收（读的是生产 GA4 数据，只读，无副作用）。
