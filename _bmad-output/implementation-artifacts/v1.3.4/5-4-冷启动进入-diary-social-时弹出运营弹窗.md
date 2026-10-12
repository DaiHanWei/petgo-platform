# Story 5.4: 冷启动进入 Diary / Social 时弹出运营弹窗

Status: ready-for-dev

## Story

As a 用户,
I want 打开 App 时看到运营的活动弹窗（每次最多一个、不重复打扰）,
so that 我不会错过活动。

> 本 story **只做 App**：数据层（读 Story 5.1 的两个接口）、本地频次、弹窗组件、判定协调器、埋点。跳转一律调 Story 5.2 的 `openOpsTarget`；「本次冷启动是否出现过功能弹窗」与「启动链是否结束」一律读 Story 5.3 的 `StartupPopupLedger`。后端无改动。

## Acceptance Criteria

**AC1 — 何时判定（D-8 / D-11 / D-22 / AD-13）** `[L0]`
1. 「落地页」= Diary 或 Social 的 Tab 根，即当前路由路径（`routerDelegate.currentConfiguration.uri.path`）恰为 `/profile` 或 `/home`（D-8：冷启动后落在哪个就是哪个，不限 Social）。
2. 每当路由变化且当前路径是落地页、且本次运行「尚未定局」（见 AC1.5）、且没有判定在进行中 → 发起一次判定。冷启动由推送 / 外部链接打开、落在其他页面时**当时不判定**；本次运行内之后第一次回到 / 进入 Diary 或 Social 时再判定（D-11）。
3. 判定流程（严格按序）：
   1. `await StartupPopupLedger.instance.startupChainDone`（启动链 ATT → 推送权限 → … → 版本检查结束，Story 5.3 AC3）；
   2. `await WidgetsBinding.instance.endOfFrame;` 再 `await Future.delayed(const Duration(milliseconds: 500));`（D-22：首次可见后延一帧再加约 500ms，让 Diary 首帧后的引导蒙层先登记）；
   3. **空闲检查**（不通过 → 本次不弹、**不定局**，等下一次路由回到落地页再试）：当前路径仍是 `/profile` 或 `/home`；`rootNavigatorKey.currentState?.canPop() != true`（根 Navigator 上没有压着对话框 / 底部弹层 / 顶层页面）；`WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed`；
   4. 当前账号是兽医（`isVet`）→ **定局**、不弹；
   5. `StartupPopupLedger.instance.hasAny` → **定局**、不弹（本次冷启动出现过 ATT / 推送权限 / 强制更新 / 新功能蒙层 / 里程碑庆祝，PRD AC2，D-6）；
   6. 取候选列表（5 秒超时；异常 / 超时 / 非 2xx → 视为空，NFR5）→ 剔除本地频次已满的（AC3）→ 取**第一个**；没有 → **定局**、不弹；
   7. 按 App 当前语言取图片 URL（AC4.2），预加载，**5 秒内**未成功（加载失败或超时）→ **定局**、本次不弹、**不计频次**（D-21，PRD AC7）；**不**退而取第二个候选；
   8. **show 之前再查一次**（D-22）：`ledger.hasAny` → **定局**、不弹；空闲检查（同第 3 步）不通过 → 不弹、**不定局**；
   9. 弹出（AC2），**定局**。
4. 同一次冷启动只要定局就不再判定：已弹过一个则不再弹第二个（PRD AC1）。
5. 「定局」状态只在内存（进程内），不持久化；下次冷启动重新判定。
6. 候选接口异常、超时、图片失败都不得抛出到框架、不得出现任何错误提示、不影响启动（NFR5）。

**AC2 — 弹窗样式（UI 稿 D1 / D2，UX-DR8）** `[L0]` `[L2]`
1. `showDialog` 挂在根 Navigator（`rootNavigatorKey.currentContext`，`useRootNavigator: true`）；`barrierColor = Color(0x9914101E)`（UI 稿 `.op-ov` `rgba(20,16,30,.6)`）；`barrierDismissible: false`（点遮罩不关）。
2. 卡片：水平居中、垂直居中；宽 `min(288, 屏宽 − 48)`；白底；圆角 24；投影 `BoxShadow(color: Color(0x4714101E), blurRadius: 48, offset: Offset(0, 20))`（UI 稿 `0 20px 48px rgba(20,16,30,.28)`）；内容 `ClipRRect` 同圆角。
3. 图片区：`AspectRatio(4 / 5)`，`BoxFit.cover`，`foregroundDecoration` 画 1px `Color(0x14000000)`（8% 黑）内描边；`Key('opsPopupImage')`。
4. ✕：压在图片右上角——视觉圆 32×32、距图片上 / 右各 10、底色 `Color(0x59000000)`（35% 黑）、白色 `Icons.close_rounded` 16；**点击热区 44×44**（以圆心为中心，即热区距上 / 右各 4）；`Semantics(label: l10n.commonClose, button: true)`；`Key('opsPopupClose')`。
5. 文案区 `Padding(EdgeInsets.all(16))`（UI 稿 `.op-b{padding:16px}`），左对齐：
   - 标题（有才渲染）：16 / w700 / `AppColors.ink`，最多 2 行省略；
   - 正文（有才渲染）：13 / `AppColors.ink2` / 行高 1.5，最多 4 行省略；标题与正文间距 6；
   - 按钮前间距：有标题或正文时 14，都没有时 0（D2 形态：图片即主体，下方只剩按钮）；
   - 按钮：整行，高 46，圆角 12，底色 `AppColors.mint`（#845EC9），文字白色 14.5 / w700，单行省略；`Key('opsPopupButton')`。
6. 不新增任何 l10n key（文案全部来自后端；✕ 的无障碍标签复用既有 `commonClose`：id「Tutup」/ en「Close」）。

**AC3 — 本地频次（AD-15，游客与登录用户都写）** `[L0]`
1. `AppPrefs` 新增键 `petgo.ops_popup_shown`：JSON 对象字符串 `{ "<popup token>": "once" | "yyyy-MM-dd" }`；新增 `Map<String, String> opsPopupShown()`（缺失 / 损坏 → 空 Map，不抛）与 `Future<void> markOpsPopupShown(String token, String value)`（覆盖同 token 旧值并移到末尾；超过 **200** 条丢最早的）。
2. WIB 日期：`String wibDateOf(DateTime now)` = `now.toUtc().add(const Duration(hours: 7))` 的 `yyyy-MM-dd`（零填充），**不用手机本地时区**（PRD 补充细则）。
3. 剔除规则：`ONCE` → 本地有该 token 的任何记录即剔除（含游客期间写下的，PRD AC4）；`DAILY` → 本地记录值等于今天的 `wibDateOf` 才剔除（值为 `once` 或昨天 → 不剔除）。
4. 写入时机：弹窗**真正显示后**（弹窗组件首帧回调）写入——`ONCE` 写 `"once"`，`DAILY` 写当天 `wibDateOf(now)`。图片失败、判定中止、空闲检查不通过都**不写**。
5. 登录用户（`isLoggedIn && !isVet`）同一时刻另调 `POST /api/v1/ops-popups/{token}/impressions`（Story 5.1 AC4），失败静默、不重试；游客不调。

**AC4 — 数据层与语言** `[L0]`
1. `ApiPaths` 新增 `opsPopupCandidates = '$base/ops-popups/candidates'` 与 `static String opsPopupImpressions(String token) => '$base/ops-popups/$token/impressions'`。
2. 语言：`isEn = Localizations.localeOf(根 context).languageCode == 'en'`（App 当前语言：用户手动设置 > 设备语言 > 英文兜底，PRD）。`isEn` 时：图片 `imageUrlEn ?? imageUrlId`、标题 `titleEn ?? titleId`、正文 `bodyEn ?? bodyId`、按钮 `buttonTextEn ?? buttonTextId`；否则全取 `*Id`（PRD AC6，D-23）。
3. 模型 `OpsPopupCandidate.fromJson` 对单条做容错：缺 `token` / `imageUrlId` / `buttonTextId`、`frequency` 不是 `ONCE` / `DAILY` → 该条丢弃（不影响其他条）；`target` 交给 `OpsTarget.fromJson`（Story 5.2）。
4. 图片 URL 统一经 `AppImage.ossResized(url, width: 1080)`（非阿里云 OSS 域名原样返回）；预加载与展示用**同一个** `NetworkImage(该 URL)`，保证展示时直接命中缓存。

**AC5 — 点击与关闭（PRD AC5 / FR-137.1）** `[L0]`
弹窗以结果值关闭：`OpsPopupOutcome.tap` / `OpsPopupOutcome.close`；系统返回键关闭的结果为 `null`，按 `close` 处理。
1. 点 ✕ / 系统返回键 → 关闭，上报 `ops_popup_closed`。
2. 点按钮：目标有效（`resolveOpsTarget(target)` 不是 `OpsNoAction`）→ 关闭后经 `openOpsTarget(根 context, ref, target)` 跳转；目标为 `NONE` / 无效 → 只关闭。两种情况都上报 `ops_popup_tapped`。
3. 点图片：目标有效 → 同按钮（关闭 + 跳转 + `ops_popup_tapped`）；目标为 `NONE` / 无效 → **无反应**（不关、不报）。
4. 跳转在弹窗 `pop` 之后执行（`showDialog` 的 future 完成后），避免在已卸载的弹窗 context 上导航。

**AC6 — 埋点（FR-137.11 · D-24）** `[L0]`
1. 事件名（PRD §4 定死，不改名）与属性：`ops_popup_shown{popup_id}`（弹窗首帧后报一次）、`ops_popup_tapped{popup_id}`、`ops_popup_closed{popup_id}`；`popup_id` = 候选的 `token`（即 `public_token`，D-24），**不带**任何文案、URL、用户信息。
2. 调用写成字面量形式 `Analytics.capture('ops_popup_shown', {...})`（清单测试按该字面量从源码提取）。
3. `test/analytics/v112_events_test.dart` 的命名守卫：`allowedPrefixes` 追加 `'ops_'`（注释：运营触达模块 FR-137 / FR-138，横跨弹窗与通知，名字由 PRD §4 定死，新模块入表、非放宽）；`allowedSuffixes` 追加 `'_closed'`（注释：PRD §4 定死 `ops_popup_closed`；与 `_dismissed` 区分——运营弹窗没有「未完成的流程」，关闭是中性结果，看板按 shown / tapped / closed 三分）。**不得**改正则、不得把事件加进 `legacyEvents`。

**AC7 — 视觉验收** `[L2]`
真机 / 模拟器在 stag 用 Dev Notes 的 SQL 灌数据后逐项过：D1（图 + 标题正文 + 按钮）、D2（仅图 + 按钮）形态；EN / ID 切换文案与图片；EN 图为空回落 ID 图；✕ 位置与热区；背后压暗；点按钮 / 图片跳商品、品类、5 个 App 页面、外链（App 内浏览器、系统默认配色）；无跳转时按钮关闭、图片无反应；每人一次 / 每天一次；游客只见「全部用户」、游客弹过的每人一次登录后不再弹；首装 ATT / 推送权限 / Diary 蒙层 / 强制更新 / 里程碑庆祝出现的那次冷启动不弹；图片 404 不弹且下次还会弹；推送冷启动落通知中心时不弹、返回 Social 后弹。

## Tasks / Subtasks

- [ ] **T1 数据层**（AC4）
  - [ ] `lib/core/network/api_paths.dart` 两个常量
  - [ ] `lib/features/ops/domain/ops_popup.dart`：`enum OpsPopupFrequency { once, daily }`、`class OpsPopupCandidate`（字段同 Story 5.1 AC3.5 JSON）+ `fromJson`（返回 `OpsPopupCandidate?`）+ `imageUrlFor(bool isEn)` / `titleFor` / `bodyFor` / `buttonTextFor`
  - [ ] `lib/features/ops/data/ops_popup_repository.dart`：`Future<List<OpsPopupCandidate>> fetchCandidates()`（内部 `.timeout(const Duration(seconds: 5))`，任何异常返回 `const []`）、`Future<void> reportImpression(String token)`（吞异常）；`opsPopupRepositoryProvider`
  - [ ] `test/ops/ops_popup_model_test.dart`、`test/ops/ops_popup_repository_test.dart`（dio 假适配器照 `test/shop/shop_product_paging_test.dart` L274 `_BodyAdapter`）
- [ ] **T2 本地频次**（AC3）
  - [ ] `lib/core/storage/prefs.dart`：`kOpsPopupShown` + 两个方法
  - [ ] `lib/features/ops/domain/ops_popup_frequency.dart`：`String wibDateOf(DateTime now)`、`OpsPopupCandidate? pickOpsPopup(List<OpsPopupCandidate> candidates, Map<String, String> shown, String todayWib)`
  - [ ] `test/ops/ops_popup_frequency_test.dart`
- [ ] **T3 弹窗组件**（AC2 / AC5）
  - [ ] `lib/features/ops/presentation/ops_popup_dialog.dart`：`enum OpsPopupOutcome { tap, close }`、`class OpsPopupDialog extends StatefulWidget`（入参：`ImageProvider image`、`String? title`、`String? body`、`String buttonText`、`bool imageTappable`、`VoidCallback onShown`）、`Future<OpsPopupOutcome?> showOpsPopupDialog(BuildContext rootContext, OpsPopupDialog dialog)`
  - [ ] `test/ops/ops_popup_dialog_test.dart`（结构断言，不写 golden）
- [ ] **T4 协调器**（AC1 / AC3.4-3.5 / AC5 / AC6）
  - [ ] `lib/features/ops/presentation/ops_popup_coordinator.dart`：`class OpsPopupCoordinator`（依赖全部可注入，见 Dev Notes「协调器骨架」）+ `opsPopupCoordinatorProvider`
  - [ ] `lib/shared/widgets/app_shell.dart`：新增 `initState`，首帧后 `ref.read(opsPopupCoordinatorProvider).attach(ref.read(routerProvider))`（`attach` 幂等）
  - [ ] `test/ops/ops_popup_coordinator_test.dart`（`fakeAsync` / `tester` 驱动，全部依赖注入假实现）
- [ ] **T5 埋点守卫**（AC6.3）：改 `test/analytics/v112_events_test.dart` 两个白名单
- [ ] **T6 L0 门禁**：`cd petgo_app && flutter analyze && flutter test`
- [ ] **T7 L2 本地**（AC7）：按 Dev Notes「stag L2 灌数据 SQL」与「L2 走查清单」

## Dev Notes

> 🔴 **开工前先对照前面 story 实际提交的代码核对本文件引用的名字，有出入先改本文件并在 Completion Notes 记一句。** 重点核对：Story 5.1 候选接口 JSON 字段名与路径；Story 5.2 的 `OpsTarget` / `resolveOpsTarget` / `OpsNoAction` / `openOpsTarget` 签名；Story 5.3 的 `StartupPopupLedger.instance` / `startupChainDone` / `hasAny` / `resetForTest`。

### 必读：会被本 story 改到的现有代码

| 文件 | 现状（已核实） | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `petgo_app/lib/shared/widgets/app_shell.dart` | `_AppShellState`（L55）无 `initState`；L62 `didUpdateWidget`、L71 `dispose`；Tab 门控 `kUngatedTabs`（L39） | 加 `initState` 挂协调器 | 门控、埋点、发布入口一字不改 |
| `petgo_app/lib/core/storage/prefs.dart` | `AppPrefs`（L6）；集合型键范例 `kTailsonalityRetentionShown` + cap（L69-83）；L93 已废弃键清单 | 加 `kOpsPopupShown` 与两个方法 | 既有键与 `_kRemovedKeys` |
| `petgo_app/lib/core/network/api_paths.dart` | 集中路径常量（`shopBanner` 在 L372） | 末尾加两条 | — |
| `petgo_app/test/analytics/v112_events_test.dart` | L213 起命名守卫：`allowedPrefixes`（L217-）、`allowedSuffixes`（L330-），L380 注释写「刻意不用 `_closed`」（那是针对支付面板） | 两个白名单各加一项 + 注释 | 正则、`legacyEvents`、其余条目 |

### 依赖的既有 / 前序代码

| 用途 | 符号 | 位置 |
|---|---|---|
| 根 Navigator | `rootNavigatorKey` | `core/router/app_router.dart` L119 |
| 路由对象 / 路由变化监听 | `routerProvider`（L363）；`router.routerDelegate.addListener(...)`（既有用法 L1200 `onNavigationAwayFromFallback`）；当前路径 `router.routerDelegate.currentConfiguration.uri.path`（L385 / L416 同写法） | `core/router/app_router.dart` |
| Diary / Social 路径 | `AppTab.profile.location == '/profile'`、`AppTab.home.location == '/home'` | `shared/widgets/bottom_tab_bar.dart` L19-23 |
| 登录态 / 角色 | `authControllerProvider`；`isLoggedIn`（L20）/ `isVet`（L23） | `features/auth/domain/auth_state.dart` |
| 功能弹窗记录 / 启动链结束 | `StartupPopupLedger.instance`（Story 5.3） | `core/startup/startup_popup_ledger.dart` |
| 跳转 | `OpsTarget`、`resolveOpsTarget`、`OpsNoAction`、`openOpsTarget`（Story 5.2） | `features/ops/domain/*`、`features/ops/presentation/open_ops_target.dart` |
| OSS 缩图 | `AppImage.ossResized(url, width:)`（仅 `aliyuncs.com` 域名生效） | `shared/widgets/app_image.dart` L24 |
| dio | `dioProvider`；repository 范式与「失败即空」写法 | `features/shop/data/shop_repository.dart` L110-125 / L137 |
| 颜色 | `AppColors.mint`（#845EC9）、`ink`（#2E2A45）、`ink2`（#544864） | `core/theme/colors.dart` L19 / L35 / L36 |
| 关闭按钮无障碍文案 | `commonClose`（id「Tutup」/ en「Close」） | `l10n/app_id.arb` L30、`app_en.arb` L63 |
| 埋点 | `Analytics.capture`；测试抓取 `Analytics.debugCaptureSink` | `core/analytics/analytics.dart` L163 / L159 |
| 迟到纠正不误伤弹窗 | `lateCorrect` 发现根 Navigator `canPop()` 就放手（`app_router.dart` L405）——运营弹窗挂在根 Navigator 上，天然受保护 | 不改 |

### 协调器骨架（依赖全部注入，便于 L0）

```dart
class OpsPopupCoordinator {
  OpsPopupCoordinator({
    required Future<List<OpsPopupCandidate>> Function() fetchCandidates,
    required Future<void> Function(String token) reportImpression,
    required Future<AppPrefs> Function() prefs,
    required AuthState Function() auth,
    StartupPopupLedger? ledger,                                   // 默认 StartupPopupLedger.instance
    DateTime Function()? now,                                     // 默认 DateTime.now
    Future<bool> Function(BuildContext ctx, ImageProvider image)? precache, // 默认 precacheImage + 5s 超时
    Future<OpsPopupOutcome?> Function(BuildContext ctx, OpsPopupDialog dialog)? show, // 默认 showOpsPopupDialog
    Future<void> Function(BuildContext ctx, OpsTarget target)? open,          // 默认 openOpsTarget
    BuildContext? Function()? rootContext,                        // 默认 rootNavigatorKey.currentContext
    bool Function()? rootCanPop,                                  // 默认 rootNavigatorKey.currentState?.canPop() ?? false
    bool Function()? isResumed,                                   // 默认 lifecycleState == resumed
  });

  void attach(GoRouter router);   // 幂等；addListener + 立即按当前路径评估一次
  void dispose();                 // removeListener
  @visibleForTesting bool get settled;
}
```
- 内部状态只有三个布尔：`_attached`、`_inFlight`、`_settled`（= AC1 的「定局」）。
- 默认 `precache`：`var failed = false; await precacheImage(image, ctx, onError: (_, _) => failed = true).timeout(const Duration(seconds: 5)); return !failed;`，`TimeoutException` / 任何异常 → `false`。
- `openOpsTarget` 需要 `WidgetRef`：provider 里用 `ref` 包一层闭包传给 `open`（`openOpsTarget` 只读 `authControllerProvider` 与 `loginGuideControllerProvider`；若 5.2 实际签名要求 `WidgetRef`，在 `AppShell.attach` 时把 `ref` 一起传入，按 5.2 实际提交取舍并记 Completion Notes）。
- **既有 widget 测试零副作用的保证**：`main.dart` 的首帧回调在 widget 测试里不执行 ⇒ `startupChainDone` 永不完成 ⇒ 判定停在第 1 步，不发请求、不起 Timer（`test/widget_test.dart` 等整 App 测试不受影响）。本 story 的测试用 `StartupPopupLedger.instance.resetForTest()` + `markStartupChainDone()` 手动放行。

### 关键设计点

- **为什么用路由监听而不是挂在两个页面的 `initState`**：两个 Tab 根是 `StatefulShellRoute.indexedStack` 的保活分支，`initState` 只跑一次；推送冷启动时先 `go(落地页)` 再下一帧 `push(目标)`（`app_router.dart` `_goDeepLinkWith` L278-296），落地页其实「挂上过一帧」——靠页面生命周期判断不出「用户真的回到了 Diary / Social」。路由路径是唯一可信信号（与迟到纠正同一判据）。
- **空闲检查不定局、功能弹窗检查定局**：前者是「此刻不方便」（用户已划走、上面压着登录框），等他回来再试；后者是 PRD 规则「本次冷启动出现过功能弹窗就顺延到下次冷启动」。
- **图片失败不取第二个**：PRD「取优先级最高的一个，加载失败本次不弹、下次冷启动再试」。退而取次优会让运营看到的曝光与配置的优先级对不上。
- **频次写在「真正显示后」**：图片超时 / 空闲检查不通过都没显示，不计频次（D-21）。服务端曝光同理只在显示后报。
- **游客继承**：游客期间写的本地 `once` 记录在登录后照样剔除（PRD AC4）；登录用户另有服务端记录，换设备也不重复弹 ONCE。
- **点遮罩不关**：UI 稿只给了 ✕ 与按钮两个出口；运营弹窗的关闭要计入 `ops_popup_closed`，误触遮罩会把「无意关闭」混进去。系统返回键仍可关（Android 用户的基本预期）。
- **与 Diary 蒙层（D-22）**：蒙层在 Diary 首帧后等 `onboardingMarksProvider` 网络返回才插入；延一帧 + 500ms 让它大多数情况下先登记，show 前再查一次兜住「候选请求期间蒙层才出来」。极端情况下（蒙层晚于弹窗出现）二者叠加是已接受的残余风险，见「待确认」。
- **VET 不弹**：兽医落 `/vet/workbench`，不经 AppShell；协调器里再兜一层。

### stag L2 灌数据 SQL（admin 主题未开始，AB-30 页面不可用）

> 由操作者在 **stag 库 `petgo_stag`** 执行（只碰 staging，见根 CLAUDE.md stag 小节）；**生产库一律不碰**。图片用 stag 后端已有的静态徽章（方图，按 4:5 cover 显示即可）；`__not_exist__` 那条用于验证图片失败。

```sql
-- 0) 清理上一轮（曝光级联删除）
DELETE FROM ops_popups WHERE public_token LIKE 'l2Pop%';

-- 1) 全部用户 · 每人一次 · 图+标题正文+按钮（D1）· 跳品类；EN 有独立图与文案
INSERT INTO ops_popups (public_token, name, status, starts_at, ends_at, priority, audience, new_user_days, frequency,
    image_url_id, image_url_en, title_id, title_en, body_id, body_en, button_text_id, button_text_en, target_type, target_value)
VALUES ('l2PopAllOnceCat0000001', 'L2·ALL·ONCE·品类', 'ACTIVE', now() - interval '1 hour', now() + interval '14 days', 300, 'ALL', 7, 'ONCE',
    'https://api-stag.tailtopia.id/milestone/cat_first_purr.webp', 'https://api-stag.tailtopia.id/milestone/dog_first_swim.webp',
    'Diskon 20% makanan kucing', '20% off cat food', 'Cuma sampai Minggu. Yuk, cek pilihannya di Toko!', 'Only until Sunday. Check the picks in Toko!',
    'Lihat sekarang', 'See now', 'CATEGORY', 'MAKANAN');

-- 2) 全部用户 · 每天一次 · 仅图+按钮（D2）· 外链（App 内浏览器）；EN 图为空 → 回落 ID 图；按钮 EN 为空 → 回落 ID
INSERT INTO ops_popups (public_token, name, status, starts_at, ends_at, priority, audience, new_user_days, frequency,
    image_url_id, button_text_id, target_type, target_value)
VALUES ('l2PopAllDailyExt000002', 'L2·ALL·DAILY·外链', 'ACTIVE', now() - interval '1 hour', NULL, 200, 'ALL', 7, 'DAILY',
    'https://api-stag.tailtopia.id/milestone/cat_box_dive.webp', 'Buka', 'EXTERNAL', 'https://tailtopia.id');

-- 3) 老用户 · 每人一次 · 无跳转（按钮=关闭，点图无反应）
INSERT INTO ops_popups (public_token, name, status, starts_at, ends_at, priority, audience, new_user_days, frequency,
    image_url_id, title_id, title_en, button_text_id, button_text_en, target_type, target_value)
VALUES ('l2PopOldOnceNone000003', 'L2·OLD·ONCE·无跳转', 'ACTIVE', now() - interval '1 hour', now() + interval '14 days', 100, 'OLD', 7, 'ONCE',
    'https://api-stag.tailtopia.id/milestone/dog_ball_play.webp', 'Halo, teman lama!', 'Hi, old friend!', 'Oke', 'OK', 'NONE', NULL);

-- 4) 新用户 · 每人一次 · App 内页面（KNOW_YOUR_PET，游客/未登录点了先登录）
INSERT INTO ops_popups (public_token, name, status, starts_at, ends_at, priority, audience, new_user_days, frequency,
    image_url_id, title_id, button_text_id, target_type, target_value)
VALUES ('l2PopNewOnceKyp000004', 'L2·NEW·ONCE·KYP', 'ACTIVE', now() - interval '1 hour', now() + interval '14 days', 400, 'NEW', 7, 'ONCE',
    'https://api-stag.tailtopia.id/milestone/dog_first_command.webp', 'Kenali anabulmu', 'Coba sekarang', 'APP_PAGE', 'KNOW_YOUR_PET');

-- 5) 商品跳转：先取一个在售商品 token 再插入
--    SELECT public_token FROM shop_products WHERE is_active LIMIT 1;
INSERT INTO ops_popups (public_token, name, status, starts_at, ends_at, priority, audience, new_user_days, frequency,
    image_url_id, title_id, button_text_id, target_type, target_value)
VALUES ('l2PopAllOnceProd00005', 'L2·ALL·ONCE·商品', 'DISABLED', now() - interval '1 hour', NULL, 500, 'ALL', 7, 'ONCE',
    'https://api-stag.tailtopia.id/milestone/cat_wand_play.webp', 'Produk pilihan', 'Lihat produk', 'PRODUCT', '<上一步取到的 public_token>');

-- 6) 图片失败（404）：本次不弹、不计频次、下次冷启动还会再试
INSERT INTO ops_popups (public_token, name, status, starts_at, ends_at, priority, audience, new_user_days, frequency,
    image_url_id, button_text_id, target_type, target_value)
VALUES ('l2PopAllImgFail000006', 'L2·ALL·图片失败', 'DISABLED', now() - interval '1 hour', NULL, 900, 'ALL', 7, 'ONCE',
    'https://api-stag.tailtopia.id/milestone/__not_exist__.webp', 'Tes', 'NONE', NULL);

-- 常用切换
UPDATE ops_popups SET status = 'ACTIVE'   WHERE public_token = 'l2PopAllOnceProd00005';   -- 启用某条
UPDATE ops_popups SET status = 'DISABLED' WHERE public_token LIKE 'l2Pop%';                -- 全部停用
DELETE FROM ops_popup_impressions WHERE user_id = <测试账号 users.id>;                       -- 重置服务端频次（本地频次需清 App 数据）
UPDATE users SET created_at = now() - interval '30 days' WHERE id = <测试账号 users.id>;     -- 把账号变成「老用户」
-- 强制更新场景（Story 3.1 的单行表）：验证该次冷启动不弹运营弹窗，验完恢复
UPDATE app_version_config SET min_supported_version = '9.9.9' WHERE id = 1;
UPDATE app_version_config SET min_supported_version = '1.0.0' WHERE id = 1;
```
注意：stag 后端须已部署含 Story 5.1 迁移的版本（部署等操作者指令，不随本 story 执行）。

### L2 走查清单（AC7 细化）

1. 只启用 1 → 冷启动落 Diary / Social：弹出 D1；切 EN 后再冷启动（先清本地数据或换 token）→ 图与文案换 EN；点按钮落 Toko「MAKANAN」品类；再冷启动不再弹（ONCE）。
2. 只启用 2 → 弹出 D2（无标题正文）；EN 下仍显示 ID 图与「Buka」；点按钮在 App 内浏览器打开（iOS SFSafariViewController / Android Custom Tabs，**系统默认配色**），关闭回原页；同一 WIB 日再冷启动不弹；把手机时区改到别的时区不影响「WIB 午夜」边界。
3. 只启用 3 → 老账号弹、新账号不弹；点图无反应；点「Oke」关闭；点 ✕ 关闭（热区足 44）。
4. 只启用 4 → 新账号弹；游客不弹（游客只见 ALL）；登录新账号点按钮跳 Know Your Pet，返回键回原页。
5. 启用 5 → 跳商品详情；后台（SQL）`UPDATE shop_products SET is_active = false …` 后不再下发。
6. 启用 6（优先级最高）→ 本次不弹任何弹窗；修正图片后下次冷启动弹出（证明未计频次）。
7. 游客冷启动弹出 1 后登录 → 1 不再弹（PRD AC4）。
8. 功能弹窗优先：iOS 全新安装首启（ATT）、Android 13+ 全新安装首启（通知权限）、已建档账号首次进 Diary（KTP 蒙层）、强制更新（上面 SQL）、刚打卡触发里程碑庆祝后冷启动——这些冷启动都不弹运营弹窗，下一次冷启动才弹。
9. 杀进程 → 点系统推送冷启动落通知中心 → 不弹；返回到 Social → 弹。
10. 断网冷启动 → 不弹、启动无异常提示。

### l10n 新 key

无（全部文案来自后端；✕ 无障碍标签复用 `commonClose`）。

### 必须更新的既有测试

- `test/analytics/v112_events_test.dart`：`allowedPrefixes` + `'ops_'`，`allowedSuffixes` + `'_closed'`（AC6.3）。
- 其余既有测试不应变红；若有整 App 级测试因 `AppShell.initState` 新增的首帧回调出现 pending timer，说明协调器在 `startupChainDone` 之前起了 Timer——修协调器，不改测试。

### 测试要点（L0）

- `ops_popup_frequency_test`：`wibDateOf(DateTime.utc(2026,10,12,16,59,59)) == '2026-10-12'`、`wibDateOf(DateTime.utc(2026,10,12,17)) == '2026-10-13'`、本地时区 `DateTime` 输入结果与其 UTC 等价值一致；`pickOpsPopup`：ONCE 有记录剔除、DAILY 今天剔除 / 昨天与 `once` 不剔除、保持服务端顺序取第一个、全被剔除返回 null。
- `prefs`：`markOpsPopupShown` 覆盖与移到末尾、第 201 条挤掉最早一条、值损坏（非 JSON / 非对象）→ 空 Map。
- `ops_popup_dialog_test`：卡片宽在 360 屏 = 288、在 320 屏 = 272；圆角 24；图片 `AspectRatio` = 0.8 且 `BoxFit.cover`；✕ 的命中区域尺寸 44×44、`Semantics` 标签为 `commonClose`；无标题正文时文案区只有按钮；按钮高 46；`imageTappable: false` 时点图片无回调。
- `ops_popup_coordinator_test`（全部注入假实现）：①`startupChainDone` 未完成 → 不取候选；②放行后 500ms 内不取候选、之后取；③ledger 有记录 → 定局、不取候选；④候选为空 / 抛异常 → 定局、不 show；⑤图片 `precache` 返回 false / 超过 5s → 不 show、prefs 无写入、定局；⑥成功 → show 一次、`onShown` 后 prefs 写入（ONCE→`once`，DAILY→当天 WIB）、登录用户调 `reportImpression`、游客不调、`ops_popup_shown` 一次；⑦取候选期间 ledger 被登记（模拟蒙层）→ 第二次检查拦下、不 show；⑧初始路径 `/notifications` → 不判定；切到 `/home` → 判定；⑨空闲检查失败（`rootCanPop` 为 true）→ 不定局，再次路由到 `/profile` 后重新判定并 show；⑩同一运行已 show 后再切 Tab → 不再判定；⑪VET → 定局不取候选；⑫结果 `tap` + 有效目标 → `open` 被调一次且在 show future 完成之后、`ops_popup_tapped`；`close` / `null` → `ops_popup_closed`；NONE 目标点按钮 → 不调 `open`、报 `ops_popup_tapped`。

### 验证层级

- **L0（云端跑）**：AC1、AC3、AC4、AC5、AC6，以及 AC2 的结构断言。命令：`cd petgo_app && flutter analyze && flutter test`（不写 golden，视觉一致性用结构断言）。
- **L2（本地）**：AC2 视觉、AC7 全部；ATT / 推送权限首装须 iOS 真机 / Android 13+ 设备。云端写好测试但不跑视觉，Completion Notes 记「L2 待本地验收」。

### Project Structure Notes

- App 新文件全部在 `lib/features/ops/{domain,data,presentation}/`；测试在 `test/ops/`。
- 只改 `app_shell.dart`（加 `initState`）、`prefs.dart`、`api_paths.dart` 与埋点守卫测试；不改路由表、不改 Diary / Social 两个页面。
- 不对整个文件跑 `dart format`。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-app.md#Story 5.4]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-app-delta.md#AD-12, AD-13, AD-14, AD-15, AD-16]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-app.md#D-6, D-8, D-9, D-11, D-21, D-22, D-23, D-24]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-app.md#3.6 运营弹窗（FR-137）全部 · §4 埋点]
- [Source: _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-app.html#D1, D2（`.op` / `.op-img` / `.op-b` / `.op-btn` / `.op-x` / `.op-ov` 样式）]
- [Source: _bmad-output/planning-artifacts/v1.3.4/UI决策记录-app.md#2026-10-09 ✕ 压在图片右上角]
- [Source: _bmad-output/implementation-artifacts/v1.3.4/5-1-运营弹窗数据与-app-读取接口.md · 5-2-统一跳转目标-运营弹窗与运营通知共用.md · 5-3-记录本次冷启动出现过的功能弹窗.md]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

- **点遮罩不关闭**（`barrierDismissible: false`）：UI 稿 / PRD 只给了 ✕ 与按钮；为让 `ops_popup_closed` 只统计有意关闭而定。系统返回键仍可关。
- **点按钮且目标为 NONE 记 `ops_popup_tapped`**（不是 `closed`）：按钮是 CTA，点了就是点击；✕ / 返回键才记关闭。
- **EN 标题 / 正文为空时回落 ID**：D-23 只明写按钮回落；标题 / 正文按同一口径处理，避免 EN 用户看到「ID 有标题、EN 无标题」的残缺弹窗。
- **标题最多 2 行、正文最多 4 行**：UI 稿未给行数上限，按 288 宽卡片与 4:5 图在 640 高屏幕内完整可见推出。
- **空闲检查不通过后只在「下一次路由回到 Diary / Social」时重试**：若用户一直停在落地页、只是上面的登录框关掉了，本次运行不会再弹（不另起轮询）。
- **残余竞态（D-22 已接受）**：Diary 引导蒙层若晚于运营弹窗出现，两者会叠加；ledger 二次检查覆盖了「候选请求期间蒙层出现」的大多数情况。

### File List

### Change Log

- 2026-10-12：story 文件创建（ready-for-dev）。
