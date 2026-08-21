# stag 专用「后台手动模拟支付回调」工具 — 设计

> 日期：2026-07-20 · 分支落点：见 §6 执行顺序 · 状态：设计定稿待实现
> 目的：在 stag 环境（真 live GemPay，没法真付钱等回调）里，让运营在后台**手动把支付订单推成 成功/失败/过时**，以便端到端测付费链路（充值/AI解锁/付费问诊/身份证HD）。

## 1. 背景与约束

- stag 后端跑 **`SPRING_PROFILES_ACTIVE=prod` + `PAY_MODE=live` + `PAY_PROVIDER=gempay`（sandbox）**（见 `docs/deploy-record-2026-07-15-gempay-staging.md`）。真 live 网关 → 无法真付款触发回调 → 需要手动模拟。
- ⚠️ **stag 与真 prod 都是 `prod` profile**。因此**任何 gate 都不能靠 profile 区分 stag / prod**，否则会连 stag 一起挡掉。区分二者的**唯一**手段是 **env flag**（只在 stag 的 `~/.env.petgo-stag` 里开）。
- 安全定性：本工具能**凭空发币 / 解锁 / 建单 / 建 IM 会话**，属安全攸关操作，**绝不能在真 prod 生效**。

## 2. 隔离策略（三保险）

| 层 | 手段 | 效果 |
|---|---|---|
| ① 物理隔离（主） | 「改状态」相关代码**只 commit 在 stag 分支**，不 cherry-pick / 不回流 v1.1-dev/main | 真 prod 镜像（出自 main）**根本不含这段代码** |
| ② 运行时 flag（保险） | `@Value("${petgo.pay.simulator-enabled:false}")`，仅 stag env `PETGO_PAY_SIMULATOR_ENABLED=true` | 即使 stag 镜像误部署到 prod 容器，prod env 没开 flag → 按钮不渲染、POST 端点拒绝 |
| ③ 权限门控 | `@PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('payment.view')")`（复用已有权限点） | 非授权后台账号进不来 |

> ❌ 不用 `@Profile("dev")`、不用 `Environment.matchesProfiles("prod")` 拒绝 —— stag 是 prod profile，用这些会把 stag 自己挡死。

## 3. 落点：复用支付记录页 `/admin/payments`

不新建页、不加新 nav 项。复用现有「支付记录」侧栏入口（`layout.html:88`，门控 `payment.view`）。

- **v1.1-dev / 主线 / prod**：支付记录页**纯只读**（按 created_at 倒序分页每页 20 + 按 userId 过滤，已实现）。**无任何操作代码。**
- **stag 分支叠加**：每行 `PENDING` 订单渲染【成功】【失败】【过时】三个 POST 按钮；终态订单只读、无按钮。

## 4. 组件（stag-only 叠加部分）

1. **`AdminPaymentController`（stag 增量修改）**
   - 注入 `@Value("${petgo.pay.simulator-enabled:false}") boolean simulatorEnabled`，`GET` 时 `model.addAttribute("simulatorEnabled", simulatorEnabled)`。
   - 新增 `@PostMapping("/admin/payments/{publicToken}/simulate")`，参数 `target ∈ {SUCCESS, FAILED, EXPIRED}`：
     - flag 关 → 直接 403/404（fail-closed）。
     - 调 `AdminPaySimulatorService.simulate(...)`，`redirect:/admin/payments` + flash 提示结果。
   - 门控 `@PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('payment.view')")`。
2. **`AdminPaySimulatorService`（stag-only 新文件，`admin/paysim/service/`）**
   - 普通 `@Service`（**不用** `@ConditionalOnProperty` —— 否则 flag 关时 bean 缺失会让 Controller 注入失败、启动崩）；运行时门控统一收在 Controller 的 flag（GET 不渲染按钮 / POST fail-closed 拒绝）。
   - 注入 `PaymentIntentService` + `AdminAuditService` + `PaymentIntentRepository`（按 token 定位 intent）；订单不存在抛 `IllegalArgumentException`。
   - 核心：
     ```
     GatewayStatus gs = switch(target){ SUCCESS->PAID; FAILED->FAILED; EXPIRED->EXPIRED; };
     paymentIntentService.applyCallback(new PaymentCallback(
         publicToken, "SIM-"+target+"-"+publicToken, gs,
         Map.of("simulated", true, "source", "admin-pay-simulator", "actor", adminId)));
     auditService.record(adminId, "PAYMENT_CALLBACK_SIMULATED", "PAYMENT_INTENT",
         publicToken, "模拟支付回调 目标="+gs+" 用途="+purpose);
     ```
   - actionType 传**字符串字面量**（不碰共享 `AuditActions`）。
3. **`payments.html`（stag 增量修改）**
   - 表格加一列「操作」；`th:if="${simulatorEnabled and p.status == 'PENDING'}"` 渲染三个 POST 表单按钮；否则显示状态徽章。
   - 需要行里带 `publicToken`（已有）。

## 5. 行为 · 幂等 · 三态

- 三态 ↔ `PAID / FAILED / EXPIRED`。走**单一收口** `PaymentIntentService.applyCallback`：
  - `PAID` → 发 `PaymentIntentPaidEvent` → 下游全链路（发币/解锁AI/建问诊单+IM会话/身份证HD解锁）。
  - `FAILED` / `EXPIRED` → 只推意图状态，无业务副作用。
- **幂等**：Redis 去重 + 终态守卫 + `@Version` 乐观锁。已终态订单再点任何按钮 → 直接返回、无副作用，flash 提示「已是终态 X」。
- 只对 `PENDING` 显示按钮 → 天然避开对终态的误操作。

## 6. 执行顺序（物理隔离流）

1. **v1.1-dev**：提交支付记录页只读改动（时间倒序分页每页 20 + userId 列）。← 已实现，L0 绿，待提交。
2. **stag**：`merge v1.1-dev`（拿到只读改动）→ 叠加 §4 的 stag-only commit（操作按钮/端点/service + spec）。
3. **部署**：改 `~/.env.petgo-stag` 加 `PETGO_PAY_SIMULATOR_ENABLED=true` + 重建 `petgo-server-stag` 容器（动线上主机，实施前先跟用户确认）。
4. **merge 摩擦提示**：今后每次 v1.1-dev → stag merge，`payments.html` / `AdminPaymentController.java` 会冲突（两边都改过），需手工保留 stag 的操作增量。这是物理隔离的已知代价。

## 7. 验证

- **L0**：`mvn -B compile` + `AdminPaySimulatorService` 单测（mock 收口，验三态构造 `GatewayStatus` + 审计调用 + flag 关时 bean 不存在）。
- **L1/L2**：部署 stag 后，对一笔真实 PENDING 充值/问诊点【成功】，验证 PawCoin 到账 / 问诊单+IM 会话建立等下游。
