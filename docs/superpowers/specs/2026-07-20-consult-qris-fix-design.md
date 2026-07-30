# 付费问诊 QRIS 支付链路修复 + 后台印尼时间 — 设计

> 日期：2026-07-20 · 分支：v1.1-dev · 状态：设计定稿待实现
> 范围决策（更新）：**二维码修问诊 + AI解锁 + 身份证HD 三处**；pending 分场景——问诊 cancel/超时清理（5min 窗），AI/HD **可复用重复支付、不清理**（不设 ttl，同充值），充值不动。

## 根因

1. **QRIS 不显示二维码**：`ConsultPayService.createCashPay` 只 `createIntent`（建 PENDING），**从不调 `gateway.createCharge`**，DTO 无二维码串；前端 `vet_timed_pay_page` 选 QRIS 后只显示 spinner，也没解析二维码。（正确范式 = `PawCoinTopupService.create`）
2. **cancel 后残留 in-process**：`cancelRequest` 只删 `consult_request`、**不碰 intent**；且问诊 intent 建单时 `expires_at=null` → 过期扫描器够不着 → PENDING 永久残留。
3. **后台时间是 UTC**：`payments.html` 直接输出 `Instant`。

## 后端改动

### A. `ConsultPayService.createCashPay` 补 createCharge（问题1核心）
- 注入 `PaymentGateway gateway`；加常量 `PAY_WINDOW = Duration.ofMinutes(5)`。
- 照 `PawCoinTopupService.create` 范式重写：
  ```
  createIntent(userId, VET_CONSULT, channel, price, IDR, "vet-consult:"+token, PAY_WINDOW)  // 带 5min TTL
  → findByToken → 若 gatewayRef==null: gateway.createCharge(ChargeRequest(publicToken, price, IDR, channel.name, VET_CONSULT.name))
  → attachCharge(publicToken, gatewayRef, meta{payload}) → payload=charge.payload()
  → ConsultPayResponse.paymentRequired(intent, payload)
  ```
- 幂等：同 requestToken 重复 pay 时 `gatewayRef!=null` 走 else 返回既有 payload（不重复下单）。

### B. `ConsultPayResponse` 加 `payload` 字段（问题1）
- record 加 `String payload`；`paymentRequired(payment, payload)`；`done(order)` 传 payload=null。Jackson NON_NULL 省略空 payload。

### C. cancel 联动置 intent FAILED（问题2）
- `PaymentIntentRepository` 加 `findFirstByUserIdAndPurposeAndStatusOrderByCreatedAtDesc(userId, purpose, status)`。
- `PaymentIntentService` 加 `failPending(userId, purpose)`：找该用户该 purpose 最新 PENDING intent → `markFailed` + saveAndFlush（无则 no-op）。
- `ConsultRequestService.cancelRequest`：CAS 删 request 成功后，调 `paymentIntents.failPending(userId, VET_CONSULT)`（需注入 `PaymentIntentService`）。接单前 cancel 时无 intent → no-op，安全。

### D. 5min `expires_at` 让扫描器回收超时/中断（问题2）
- 已在 A 的 `createIntent` 带 `PAY_WINDOW`。`PaymentIntentExpiryScanner`（每 60s）自动把超时 PENDING 置 EXPIRED。cancel→FAILED / 超时→EXPIRED，都不再残留。

## 前端改动

### E. `consult_pay_result.dart` 加 `payload`
- `ConsultPayResult` 加 `String? payload`，`fromJson` 解析 `json['payload']`（QRIS EMVCo 串）。

### F. `vet_timed_pay_page` QRIS 渲染二维码（问题1）
- 选 QRIS `_pay()` 成功后，若 `payload!=null` → 渲染 `QrImageView(data: payload)`（照 `recharge_page.dart:310` 范式）替换纯 spinner；保留 3s 轮询侦测到账（404→跳会话）。
- 依赖 `qr_flutter`（已在 pubspec）。

### G. `vet_timed_pay_page` 加「取消支付」按钮（问题2）
- QRIS 二维码态下加取消按钮 → `cancelRequest(token)` → `context.go('/triage')`。
- ⚠️ 这改变老 UX-DR14「接单后不能取消、只能超时」——按用户明确要求，QRIS 支付页允许主动取消。

## 后台改动

### H. `payments.html` 时间显示印尼时间（问题3）
- `AdminPaymentRow` 加 `String createdAtLabel`；`AdminPaymentQueryService.toRow` 用
  `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Jakarta"))` 格式化 + " WIB"。
- `payments.html` 时间列改显示 `createdAtLabel`。

## 边缘风险（V1 可接受）
QRIS 用户已扫码付款后又点 cancel：cancel 置 intent FAILED，之后 GemPay 回调 PAID 进来 → `applyCallback` 见 intent 已终态 → 幂等拒绝（钱已付但无服务）。属小概率竞态，V1 stag 测试可接受；真钱退款留 Epic4。

## 追加实现（AI解锁 + 身份证HD 二维码）

范式与问诊一致（补 `gateway.createCharge` → attachCharge → payload 透传），但 pending **不设 ttl、不清理、可复用重复支付**（同充值；取消=纯关闭不调后端）。

- **后端**：`AiUnlockService.createCashUnlock` / `IdCardHdService.purchase` QRIS 分支补 `ensureCharge`（各自私有方法，照 PawCoin 范式）+ 注入 `PaymentGateway`；`UnlockResponse` / `HdPurchaseResponse` 加 `payload`。
- **前端**：新增通用 `showQrPaymentSheet`（`shared/widgets/qr_payment_sheet.dart`）——二维码 + 3s 轮询到账 + 取消纯关闭，AI/HD 复用。
  - AI：`UnlockResult` / `TriageUnlockState` 加 `payload`；抽 `runAiUnlockFlow`（结果页 CTA + paywall 两入口复用），`waitingPayment` → 弹面板轮询 `pollTriage` 至 `locked==false` → `markUnlocked`。
  - HD：`HdPurchaseResult` 加 `payload`；`_purchaseHd` QRIS 分支把 toast 换成面板，轮询 `idCardProvider` 至 `hdUnlocked` → 自动导出。
- **测试**：`AiUnlockServiceTest` QRIS 用例补 createCharge 桩 + payload 断言；`qr_payment_sheet_test` 渲染二维码+取消。

## 执行顺序 & 验证
1. **后端**（A/B/C/D）→ L0：`mvn -B compile` + ConsultPayService/cancel 单测。
2. **前端**（E/F/G）→ L0：`flutter analyze` + vet_timed_pay_page 测试。
3. **后台**（H）→ L0：`mvn compile` + AdminPaymentQueryService 测试。
4. **联调**（L2）：stag 部署后，真机走「排队→接单→QRIS→扫码/模拟回调→到账」+「cancel→列表不残留」+ 后台看 WIB 时间。
