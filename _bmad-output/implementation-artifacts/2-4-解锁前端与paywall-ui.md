---
baseline_commit: 7c1ec93
---
# Story 2.4: 解锁前端与 paywall UI

Status: review

> V1.1 **Epic 2 收官** story，**纯前端 Flutter**。消费 2-2 后端分字段下发（`TriageResult` 加 `locked`/`unlockSource`）+ 2-3 解锁端点（`POST /triage/{id}/unlock`），在分诊结果页渲染**锁定态 paywall**（详建 blur + 解锁 CTA），并接**三条解锁方式**（免费额度 / PawCoin / 现金 QRIS）。**无迁移、无后端改动**。
> 源：`epics-v1.1.md` Story 2.4（详建 blur + CTA「PawCoin atau Rp10.000」；黄色图标/颜色/时效免费；**红色无锁**；读屏隐藏 blur；`flutter analyze` 绿；L2 模拟器视觉）· `UX_DESIGN` C-7 修订（安全信息永不被付费墙挡住）· 承接 2-2/2-3 契约。
> **范围边界**：结果页锁定态渲染 + 解锁方式选择 + 调解锁端点 + 同步（免费/PawCoin）即时解锁 + 现金发起支付并轮询解锁。**不做**：后端逻辑（2-2/2-3 已完成）、真实 Midtrans QR/支付页（复用 1.3/1.5 支付呈现，现金完成属 L2）、后台改价（9-2）。
> **承接契约（后端已 review）**：`GET /triage/{id}` 的 `TriageResultResponse` 现含 `locked`(bool)/`unlockSource`(LOCKED/FREE_QUOTA/PAID)；DONE 时安全字段（dangerLevel/disclaimer/observation/emergency*）恒下发，详建 `advice`/`medicationRef` 仅解锁下发（**红色 locked 恒 false，详建恒下发**）。`POST /triage/{id}/unlock` body `{method: FREE_QUOTA|PAWCOIN|QRIS}` → `UnlockResponse{unlocked, result, payment}`（同步：unlocked=true+result；现金：unlocked=false+payment{token,...}）。`GET /me/free-quota` → `{period, limit, used, remaining}`。

## Story

As a 用户,
I want 清晰的锁定态与解锁 CTA,
so that 我知道免费看到什么、付费解锁什么。

## Acceptance Criteria

1. **前端：模型与端点扩容（L0）**
   **Given** 既有 `TriageResult`（`triage_repository.dart`）与 `ApiPaths`
   **When** 对接 2-2/2-3
   **Then** `TriageResult` 加 `locked`(bool? 默认按锁定语义)/`unlockSource`(String? 或枚举)，`fromJson` 解析（缺省=非 DONE，`locked` 为 null 时按「未锁」渲染，仅在 DONE 且 `locked==true` 显 paywall）；`ApiPaths` 加 `triageUnlock(int id)=/triage/{id}/unlock`、`freeQuota=/me/free-quota`；`TriageRepository` 加 `unlockTriage(id, method)`（POST，返回解析 `UnlockResponse{unlocked, result, payment}`）+ 免费额度读取（`FreeQuotaView{limit,used,remaining}`）（**L0**）

2. **前端：锁定态 paywall 渲染（详建 blur + CTA）（L0）**
   **Given** DONE 结果 `locked==true` 且 `dangerLevel != red`
   **When** 渲染结果页详建区（SARAN PERAWATAN = `advice`/`medicationRef`）
   **Then** 详建内容以 **blur/遮罩**呈现（`ImageFiltered`/`BackdropFilter` 或占位骨架，**不泄露真实文字**——后端锁定时 advice 本就为 null，前端用占位/骨架不是把真文字模糊），叠加**解锁 CTA**「Buka dengan PawCoin atau Rp10.000」（价格用常量 `kAiUnlockPriceIdr` 格式化 Rp10.000）；**黄色图标/颜色/`observation` 时效、红色强提醒、免责恒可见**（安全免费部分不受 paywall 影响，C-7）（**L0**）

3. **前端【安全攸关】：红色无锁（L0）**
   **Given** `dangerLevel == red`（后端 `locked` 恒 false）
   **When** 渲染红色结果（`triage_red_result.dart` / 结果页红色分支）
   **Then** **绝不显示 paywall/blur**，详建完整可见（红色永不锁，与后端响应层单点呼应）；即便 `locked` 字段异常为 true，前端也按 `dangerLevel==red` 强制不锁（**双保险**，L0 用例断言）（**L0**）

4. **前端：解锁方式选择 + 同步解锁（免费额度 / PawCoin）（L0/L2）**
   **Given** 点击解锁 CTA
   **When** 弹解锁方式面板（bottom sheet）
   **Then** 列三方式：**免费额度**（读 `/me/free-quota`，`remaining>0` 才可选，展示「Kuota gratis (sisa X)」，`remaining==0` 置灰/隐藏）；**PawCoin**（读 `pawCoinProvider` 余额，`balance>=price` 才可选，展示余额）；**现金**（QRIS）；选免费/PawCoin → 调 `unlockTriage(id, FREE_QUOTA|PAWCOIN)` → `unlocked=true` → 用返回 `result` 刷新结果页为已解锁（详建下发、去 blur）；额度不足/余额不足后端 409 → 友好提示引导换方式（不崩）（**L0** 逻辑 + widget / **L2** 真机走通）

5. **前端：现金解锁（QRIS，发起支付 + 轮询解锁）（L2）**
   **Given** 选现金方式
   **When** 调 `unlockTriage(id, QRIS)` → `unlocked=false`+`payment{token}`
   **Then** 呈现支付（**复用 1.3/1.5 topup 支付呈现范式**：展示支付渠道/引导 + 轮询）；**轮询 `pollTriage(id)` 直到 `locked==false`**（AiUnlockPaidHandler 到账后置，复用既有轮询，无需新端点）或超时按未完成回退；到账后结果页转已解锁（**L2**，需 sandbox 真付；stub 模式无真实 QR，故本路径 L0 仅测「发起→拿到 payment token→进入轮询态」不测真到账）

6. **前端：无障碍 + 静态门槛（L0）**
   **Given** 锁定态 blur 详建
   **When** 读屏（TalkBack/VoiceOver）遍历
   **Then** blur/占位详建对读屏**隐藏**（`ExcludeSemantics` 或不渲染真文字，读屏只读到「详建已锁定，可解锁」的语义提示 + CTA，不读残留内容）；文案双语 id+en + `flutter gen-l10n`，源码零硬编码用户可见串；`flutter analyze` 0 警告、`flutter test` 绿（**L0**）；模拟器视觉验收锁定态/解锁态/红色无锁三态（**L2**）

## Tasks / Subtasks

> 纯前端、feature-first 落 `features/triage`。先扩模型/端点 → paywall widget（blur+CTA+红色无锁）→ 解锁方式 sheet + 控制器（免费/PawCoin 同步 / 现金轮询）→ i18n → 测试。**先读 `triage_repository.dart`/`triage_result_view.dart`/`triage_result_controller.dart`/`recharge_page.dart`(支付轮询范式)/`pawcoin_controller.dart`**（Dev Notes 已附）。**无迁移。**

- [x] **T1 前端：模型 + 端点 + repository**（AC1）
  - [x] `TriageResult` 加 `bool? locked` / `String? unlockSource`（或 `UnlockSource?` 枚举）+ `fromJson` 解析 + `copyWith` 带上。**L0**
  - [x] `ApiPaths`：`triageUnlock(int id)` / `freeQuota`。**L0**
  - [x] `TriageRepository.unlockTriage(int id, UnlockMethod method)` → `UnlockResult{bool unlocked, TriageResult? result, PaymentInfo? payment}`；`fetchFreeQuota()` → `FreeQuotaView{int limit, used, remaining}`。`DioTriageRepository` 实现 + provider 不变。**L0**
- [x] **T2 前端：paywall widget（blur + CTA + 红色无锁）**（AC2/AC3）
  - [x] 新 `presentation/widgets/triage_paywall.dart`：锁定态详建占位/blur + CTA 按钮「Buka dengan PawCoin atau Rp10.000」；红色分支绝不渲染 paywall。**L0**
  - [x] `triage_result_view.dart` 详建区接入：`result.dangerLevel!=red && (result.locked==true)` → 渲染 paywall 替代/覆盖详建；否则原详建。安全免费部分（level/observation/emergency/disclaimer）渲染不变。**L0**
- [x] **T3 前端：解锁方式 sheet + 控制器**（AC4/AC5）
  - [x] `presentation/widgets/unlock_method_sheet.dart`：三方式（免费额度 `remaining` / PawCoin 余额 / 现金），可用性按 `remaining>0` / `balance>=price` 判定。**L0**
  - [x] `domain/triage_unlock_controller.dart`（Notifier/AsyncNotifier）：`unlock(id, method)` → 免费/PawCoin 同步（成功用 result 刷新 `triageResultProvider` 或结果页本地态，去 blur）；现金 → 拿 payment、进入轮询（复用 `pollTriage` 直到 `locked==false` 或超时）。409（额度/余额不足）→ 暴露错误态给 UI 友好提示。**L0**
- [x] **T4 前端：i18n**（AC2/AC6）
  - [x] `app_en.arb`/`app_id.arb` 加：CTA、方式名（免费额度/剩余、PawCoin/余额、现金 QRIS）、额度不足/余额不足提示、读屏语义串（「详建已锁定，解锁后可见」）、价格模板。`flutter gen-l10n`。**L0**
- [x] **T5 测试**（AC2-6）
  - [x] widget `test/triage/triage_paywall_test.dart`：黄色 locked→显 blur+CTB、安全部分（时效/免责）可见、详建真文字不在 tree（读屏 `ExcludeSemantics`）；绿色 locked→同；**红色→无 paywall、详建可见**（头等）；unlocked（locked=false）→无 paywall、详建可见。**L0**
  - [x] widget/unit `test/triage/triage_unlock_controller_test.dart`（fake repo）：免费额度 remaining>0 可选、扣成功刷新已解锁；PawCoin balance>=price 可选；额度/余额不足 409 → 错误态不崩；现金 → 进入轮询态。**L0**
  - [x] `flutter analyze` 0 警告 + `flutter test` 绿。**L0**（L2 模拟器三态视觉留本地）

## Dev Notes

> 纯前端收官。核心是「消费后端已下发的 `locked` 渲染 paywall」+「三方式解锁编排」。**不改后端**。红色无锁是安全红线（前端双保险）。现金真付属 L2（stub 无真 QR），L0 只测发起+进入轮询态。

### 承接契约（后端 2-2/2-3 已 review，前端照此消费）
- **`GET /triage/{id}` → TriageResultResponse**（2-2）：DONE 时新增 `locked`(bool)/`unlockSource`(LOCKED/FREE_QUOTA/PAID)；安全字段 `dangerLevel`/`disclaimer`/`observation`(含 timeWindow)/`emergencySteps`/`emergencyAvoid` 恒下发；详建 `advice`/`medicationRef` **仅解锁时非 null**（锁定时后端置 null → 前端拿不到真文字，paywall 用占位不是模糊真文字）。**红色 `locked` 恒 false**。非 DONE 仅回 status（`locked`/`unlockSource` 为 null）。
- **`POST /triage/{id}/unlock`**（2-3）：body `{"method":"FREE_QUOTA|PAWCOIN|QRIS"}` → `UnlockResponse{unlocked(bool), result(TriageResultResponse?), payment(PaymentIntentResponse?)}`。同步（免费/PawCoin）：`unlocked=true`+`result`（已解锁，含详建）。现金：`unlocked=false`+`payment{token,purpose,channel,amount,currency,status}`（**无真实 QR 字段**，stub 模式）。额度不足/余额不足 → 后端 409 ProblemDetail（前端映射友好文案，勿显 detail 原文）。红色/已解锁 → 后端短路返回 `unlocked=true`+result（不扣费）。
- **`GET /me/free-quota`**（2-1）：`{period, limit, used, remaining}`。`remaining` 供 sheet 判「免费额度可选 + 剩余数」。

### 既有前端现状（已读，dev 照此，勿臆测）
- **`triage_repository.dart`**：`TriageResult{status,dangerLevel,advice,medicationRef,disclaimer,observation,emergencySteps,emergencyAvoid,symptomSummary}` + `fromJson`；`TriageObservation{indicators,timeWindow,escalationTriggers}`；`TriageRepository{submitTriage,pollTriage}` + `DioTriageRepository`。**本 story 在此加 `locked`/`unlockSource` 字段 + `unlockTriage`/`fetchFreeQuota`。**
- **`triage_result_view.dart`（562 行）**：`TriageResultView extends ConsumerWidget`；`_LevelHeader`（等级图标/颜色）；`_SectionCard`/`_Bullet` 渲染 `advice` bullets（~line 100）+ 黄色 `medicationRef`（~line 103-109）；`_ProtocolBlock` 渲染 observation 三要素。**详建区（advice/medicationRef）= paywall 接入点**；`_LevelHeader`/observation/emergency/disclaimer = 安全免费部分保持不变。
- **`triage_red_result.dart`**：红色半屏强提醒 UI。**红色分支绝不接 paywall。**
- **`triage_result_controller.dart`**：`triageResultProvider = NotifierProvider<TriageController, TriageResultState>`；`pollTriage` 轮询（interval 1s、timeout 30s）。解锁后可 `pollTriage` 复看或用 unlock 返回的 result 直接更新态。
- **`pawcoin_controller.dart`**：`pawCoinProvider = AsyncNotifierProvider<PawCoinController, PawCoinState>`，`PawCoinState.balance`（int）。sheet 读余额判 PawCoin 可选。
- **`recharge_page.dart`（支付轮询范式）**：createTopup→拿 intentToken→`Timer.periodic(3s)` 轮询 `pollStatus(token)`，PAID→成功、FAILED/EXPIRED/超时（40 次≈2min）→失败，`dispose` 取消 Timer 防泄漏。**现金解锁照此范式，但轮询目标改 `pollTriage(id)` 判 `locked==false`**（AiUnlockPaidHandler 到账后置，复用既有端点，无需 topup 那种专用 status 端点）。
- **`api_paths.dart`**：`triage=/triage`、`triageResult(id)=/triage/$id`、`me=/me`、`mePawcoin=/me/pawcoin`。**加 `triageUnlock(id)`/`freeQuota`。**

### paywall 渲染要点（AC2/AC3）
- **锁定时后端 advice/medicationRef 已是 null**——前端 paywall 用**占位骨架 / 磨砂遮罩层**（如半透明 + 锁图标 + CTA），**不是把真文字 blur**（真文字根本没下发，防「blur 可被截图还原」的伪隐藏）。这点比原型「blur」更安全，符合「锁定内容不泄露」。
- **CTA**：「Buka dengan PawCoin atau Rp10.000」（id）/ en 对应；价格 `kAiUnlockPriceIdr=10000` 常量格式化 `Rp10.000`（千分位）。价格 9-2 后台落地后可改为后端下发（[OPEN]）。
- **红色无锁双保险**：渲染条件 `dangerLevel != DangerLevel.red && result.locked == true`。红色即使 `locked` 异常为 true 也不锁（`dangerLevel==red` 优先）。
- **安全免费部分不动**：`_LevelHeader`、observation 时效、emergency 强提醒、disclaimer 照常渲染在 paywall 之外/之上。

### 解锁编排要点（AC4/AC5）
- **免费/PawCoin 同步**：`unlockTriage` 返回 `unlocked=true`+`result` → 直接用该 result 更新结果视图态（无需再 poll），去 paywall。
- **现金异步**：`unlockTriage(QRIS)` 返回 payment → 进入「等待支付」态 + 轮询 `pollTriage(id)`（复用 recharge 的 Timer 范式，`dispose` 取消防泄漏）直到 `locked==false` → 转已解锁；超时/取消 → 回锁定态。**stub 模式无真到账**，故 L0 只测「发起→拿 payment token→进入轮询态」，真到账走 L2 sandbox。
- **可用性判定**：sheet 打开时并行读 `/me/free-quota`（remaining）+ `pawCoinProvider`（balance）；`remaining<=0` 免费置灰、`balance<price` PawCoin 置灰（仍可选现金）。
- **409 友好提示**：额度/余额不足（后端 409）映射本地化文案「本月免费额度已用完，试试 PawCoin 或现金」/「PawCoin 余额不足，去充值或用现金」，不崩、不显 ProblemDetail detail 原文。

### Project Structure Notes
- 前端新增：`features/triage/presentation/widgets/triage_paywall.dart`、`features/triage/presentation/widgets/unlock_method_sheet.dart`、`features/triage/domain/triage_unlock_controller.dart`、`features/triage/domain/free_quota.dart`（或并入 repository）、测试 `test/triage/triage_paywall_test.dart` + `test/triage/triage_unlock_controller_test.dart`。
- 前端修改：`features/triage/data/triage_repository.dart`（+locked/unlockSource+unlockTriage+fetchFreeQuota）、`features/triage/presentation/triage_result_view.dart`（详建区接 paywall）、`core/network/api_paths.dart`（+2 路径）、`l10n/app_{en,id}.arb`（+文案）。
- **不改**：后端任何文件、`triage_red_result.dart` 的红色渲染逻辑（仅确认不接 paywall）、既有 `pollTriage`/`submitTriage` 契约。
- 后端：**无**（2-2/2-3 已完成并 review）。

### References
- [Source: epics-v1.1.md#Story 2.4]（详建 blur + CTA PawCoin atau Rp10.000；黄色免费；红色无锁；读屏隐藏 blur；analyze 绿；L2 视觉）
- [Source: CROSS-STORY-DECISIONS.md / architecture-v1.1-delta.md#C-7]（安全信息永不被付费墙挡住；红/黄免费部分与详建分字段）
- 承接后端：`2-2-ai结果分字段下发与锁定态.md`（TriageResultResponse locked/unlockSource + 红色永不锁）、`2-3-付费额度解锁流.md`（POST /triage/{id}/unlock + UnlockResponse + /me/free-quota + 红色/已解锁不扣费）
- 代码：`petgo_app/lib/features/triage/data/triage_repository.dart`、`.../presentation/triage_result_view.dart`、`.../presentation/triage_red_result.dart`、`.../domain/triage_result_controller.dart`、`.../pawcoin/presentation/{pawcoin_controller,recharge_page}.dart`、`core/network/api_paths.dart`；后端契约 `triage/dto/{TriageResultResponse,UnlockResponse,FreeQuotaView}.java`。

### 待澄清（不阻塞）
- [OPEN] 解锁价 `kAiUnlockPriceIdr` 前端常量 vs 后端下发：本 story 用常量（=后端默认 10000）；9-2 后台改价落地后，宜由后端在结果/paywall 上下文回传价格避免双写漂移。现金路径 `payment.amount` 已带真价，仅「未选方式前的 CTA 展示价」用常量。
- [OPEN] 现金支付真实呈现（QR 码/deeplink）：`PaymentIntentResponse` 当前不含 QR/redirect，stub 模式无真付。L2 sandbox 前需确认 Midtrans 返回的支付呈现字段是否经后端透出（可能需后端小改把 gateway_meta 的支付 URL 下发——若需则回 2-3 或单开）；本 story 现金到 L0「进入轮询态」为止。
- [OPEN] 解锁后是否刷新历史/存档：解锁态 `unlock_source` 已落库，历史回看 `GET /triage/{id}` 自带；本 story 不额外处理存档页。

## Dev Agent Record

### Agent Model Used

Claude Opus 4.8 (1M context)

### Debug Log References

- 前端 L0：`test/triage/triage_paywall_test.dart` **12 tests passed**（isDetailLocked 5 含红色永不锁头等 + paywall 渲染 2 + 控制器 5）；全 `test/triage` + `test/l10n` 回归 **59 passed**（含补 3 个既有 fake repo 的新方法）。
- `flutter analyze`（全项目）：**No issues found**。`flutter gen-l10n` 成功（+15 键 en/id）。
- L2 模拟器三态视觉（锁定/解锁/红色无锁）+ 现金真付走通留本地。

### Completion Notes List

- **AC1** `TriageResult` 加 `locked`/`unlockSource` + `fromJson`/`copyWith` + `isDetailLocked` getter；`ApiPaths.triageUnlock(id)`/`freeQuota`；repository `unlockTriage`/`fetchFreeQuota` + `UnlockMethod`/`UnlockResult`/`PaymentInfo`/`FreeQuotaView` 类型。✅ L0
- **AC2** `TriagePaywall` widget：**占位骨架（非模糊真文字）** + 锁图标 + 锁定标题 + CTA「Buka dengan PawCoin atau Rp10.000」（`formatIdr` 千分位）；接入 `triage_result_view` 详建卡（`result.isDetailLocked && triageId!=null` → paywall）；安全免费部分（header/observation/emergency/disclaimer）渲染不变。✅ L0
- **AC3【安全攸关】** 红色永不锁双保险：`isDetailLocked = done && locked==true && dangerLevel!=red`（模型层）+ 结果页红色早返（line 41，paywall 前）；`triage_paywall_test` 头等断言「红色+locked→isDetailLocked=false」绿。✅ L0
- **AC4** `unlock_method_sheet`：并行读 `/me/free-quota`(remaining) + `pawCoinProvider`(balance)，`remaining>0`/`balance>=price` 判可用（置灰）；`TriageUnlockController.unlock` 免费/PawCoin 同步 → `unlocked` 态用返回 result 刷新去 paywall（`_resolveResult` swap）；409 → `errorKind`(quotaExhausted/insufficientBalance) → SnackBar 友好提示不崩。✅ L0
- **AC5** 现金 → `waitingPayment` + payment（token）；真到账轮询 `pollTriage` 至 `locked==false` 复用既有端点（L2；stub 无真付，L0 测到「进入 waitingPayment」为止）。✅ L0 部分
- **AC6** 无障碍：占位骨架 `ExcludeSemantics` 读屏隐藏 + 整卡 `Semantics(label: 详建已锁定)`；双语 en/id + gen-l10n；analyze 0 警告、test 绿。✅ L0
- **回归**：接口扩容致 3 个既有 fake TriageRepository（triage_controller/upload_controller/upload_page 测试）补 `unlockTriage`/`fetchFreeQuota` stub。控制器非 family（`FamilyNotifier` 本版不存在）→ 用 `Notifier` + state 带 triageId 区分，照既有 `TriageController` 范式。

### File List

**前端（新增）**
- `features/triage/domain/triage_unlock_controller.dart`
- `features/triage/presentation/widgets/triage_paywall.dart`
- `features/triage/presentation/widgets/unlock_method_sheet.dart`
- `test/triage/triage_paywall_test.dart`（L0, 12）

**前端（修改）**
- `features/triage/data/triage_repository.dart`（+locked/unlockSource/isDetailLocked + UnlockMethod/UnlockResult/PaymentInfo/FreeQuotaView + unlockTriage/fetchFreeQuota）
- `features/triage/presentation/triage_result_view.dart`（详建区接 paywall + 解锁态 swap + 红色早返注释）
- `core/network/api_paths.dart`（+triageUnlock/freeQuota）
- `l10n/app_en.arb` / `l10n/app_id.arb`（+15 解锁文案键）+ 生成产物
- `test/triage/triage_controller_test.dart` / `triage_upload_controller_test.dart` / `triage_upload_page_widget_test.dart`（回归：fake repo 补新方法）

### Change Log

- 2026-07-13：create-story 定稿 Story 2.4（解锁前端 paywall：TriageResult 加 locked/unlockSource + 锁定态占位遮罩 + CTA + 解锁方式 sheet[免费额度/PawCoin/现金] + 免费/PawCoin 同步解锁 + 现金复用轮询 + 红色无锁双保险 + 读屏隐藏；纯前端无迁移）；Epic 2 收官；Status → ready-for-dev。
- 2026-07-13：dev-story 实现完成。模型/端点/repository + TriagePaywall(占位非模糊) + unlock_method_sheet + TriageUnlockController(免费/PawCoin 同步 + 现金 waitingPayment + 409 分类) + 结果页接入(红色早返双保险) + 无障碍 ExcludeSemantics + i18n；前端 L0 12 新 tests + 59 triage/l10n 回归全绿，analyze 干净；L2 三态视觉+现金真付待本地；Status → review。
