---
baseline_commit: 1a08e5a
---
# Story 1.3: PawCoin 充值下单与到账

Status: review

> V1.1 Epic 1（资金地基）第 3 story。**接 1.1（支付网关/payment_intents/回调）+ 1.2（LedgerService/PawCoinWalletService）**，把两者接成充值闭环。**brownfield**：Flyway V46 冻结，1.1=V47、1.2=V48；**本 story 无新迁移**（复用 V47/V48 表）。
> 源：`epics-v1.1.md` Story 1.3 · 架构 §2/§3.1/§4 · 排期 `sprint-status-v1.1.yaml`。
> **范围边界**：用户下单充值 → 网关支付 → 回调**原子入账**。**不做**余额/流水页 UI（1.4）、失败/暂停态前端（1.5）、后台档位配置（9.2，本 story 用内置默认档位）。

## Story

As a 用户,
I want 选固定档位用 QRIS/DANA 充值 PawCoin，支付成功后自动到账,
so that 我能获得可消费的 PawCoin 余额（FR-50 / FR-50A）。

## Acceptance Criteria

1. **充值下单端点（L0/L1）**
   **Given** `POST /api/v1/me/pawcoin/topups`（JWT `role=user`，`Idempotency-Key` 头 + 写端点限流）
   **When** 用户选一个合法档位（QRIS/DANA）下单
   **Then** 经 1.1 `PaymentIntentService.createIntent(PAWCOIN_TOPUP, amount, channel, idemKey)` 建意图，返回**支付载荷**（QRIS 二维码串/DANA deeplink）+ `intentToken`（对外 token，非自增 id）（**L1**）
   **And** 非法档位/金额 → `AppException.validation`（422）；未登录 → 401（**L0/L1**）

2. **固定档位（L0）**
   **Given** 内置默认档位 `Rp10k/25k/50k/100k`（`coins = amount` 1:1）
   **Then** 档位以枚举/常量内置于 `pay` 模块；**后台可配是 Story 9.2**，本 story 留 `TopupTierProvider` 接口便于 9.2 替换为 DB 档位（**L0**）

3. **回调原子入账（L1，最高风险）**
   **Given** 1.1 `PayCallbackController` 验签+去重后按 `purpose` 分派
   **When** `purpose=PAWCOIN_TOPUP` 的意图被标记支付成功
   **Then** 在**同一 `@Transactional`** 内：`markPaid(intent)` + `PawCoinWalletService.credit(userId, coins, TOPUP, ref=intent, idempotencyKey=intentToken)` + 写 `pawcoin_transactions`（**L1**）
   **And** 回调重放/双通道 → 幂等：**绝不重复入账**（`markPaid` 已终态即返回 + credit 幂等键=intentToken）（**L1**）
   **And** **禁用 AFTER_COMMIT 异步入账**（资金必须同事务，见 Dev Notes 事务坑）（**L0** 代码审查项）

4. **失败/取消不入账（L1）**
   **Given** 网关回调失败或超时
   **When** 意图置 `FAILED/EXPIRED`
   **Then** **不 credit、不写 `pawcoin_transactions`、余额不变**（失败态 UI 属 1.5，本 story 只保证不入账）（**L1**）

5. **端到端真充值到账（L2）**
   **Given** `mode=live` + Midtrans **sandbox** 凭证
   **When** 真实走一笔 QRIS 充值并回调
   **Then** 余额增加对应 koin、`pawcoin_transactions` 一条 `TOPUP`、总账 `DEBIT CASH_IN / CREDIT FLOAT_LIABILITY` 平（**L2**，留本地/带凭证环境，Completion Notes 标注）

## Tasks / Subtasks

> 纯后端 story，无迁移。核心是「回调→入账」的同事务原子性与幂等（AC3）。

- [x] **T1 档位**（AC2）
  - [x] `pay/domain/TopupTier.java`（枚举 10k/25k/50k/100k，`amountIdr`=`coins` 1:1）+ `pay/service/TopupTierProvider.java`（接口 + 内置 `Default` 实现；9.2 换 DB）。**L0** ✅
- [x] **T2 充值下单**（AC1）
  - [x] `pay/dto/CreateTopupRequest.java`（record：`tierId`/`channel` + `@NotBlank`）+ `TopupResponse.java`（record：`intentToken` + 载荷；**不含自增 id**）。**L0** ✅
  - [x] `pay/web/PawCoinTopupController.java`（`@RequestMapping("/api/v1/me")`，`@AuthenticationPrincipal Jwt` + `currentUserId(jwt)`，`POST /pawcoin/topups`，`Idempotency-Key` 头）。照 `auth/web/MeController`（C1，仅作用当前 sub 防越权）。**L1** ✅
  - [x] `pay/service/PawCoinTopupService.java`：校验档位/渠道(QRIS/DANA) → `createIntent(PAWCOIN_TOPUP,...)`(幂等+写限流在其内) → `gateway.createCharge` 取载荷 + `attachCharge` 回填(幂等，重放不重复 charge) → 返回 `TopupResponse`。**L1** ✅
- [x] **T3 回调入账接线**（AC3/4 — 最高风险）
  - [x] 分派点：复用 1.1 `applyCallback` 在 `markPaid` 后于**同 `@Transactional`** 内 `publishEvent(PaymentIntentPaidEvent)`；`TopupPaidHandler` 以**同步 `@EventListener` + `Propagation.MANDATORY`** 按 `purpose` 分派（非充值意图忽略，未接 purpose 不 crash）。**L0** ✅
  - [x] `pay/service/TopupPaidHandler.java`：**同一事务**内 `PawCoinWalletService.credit(userId, amount, TOPUP, refType=PAYMENT_INTENT, refId=intentId, idempotencyKey=publicToken)`。幂等：1.1 对已 PAID 意图 applyCallback 早返回不再发事件 + credit 幂等键=publicToken。**禁 AFTER_COMMIT**（记忆库血泪，代码注释显式钉死）。**L1** ✅
  - [x] `FAILED/EXPIRED` 分支：1.1 只 `markFailed/markExpired`、**不发 PaymentIntentPaidEvent**，故 handler 不触发、**不 credit**。**L1** ✅
- [x] **T4 测试**（AC1-5）
  - [x] L0 单测：`PawCoinTopupServiceTest`（非法档位/渠道拒绝、下单调 createIntent+charge+attachCharge、幂等重放不重复 charge）、`TopupPaidHandlerTest`（PAWCOIN_TOPUP→credit 一次、其余 purpose 不 credit）。**L0** ✅ 6/6 绿
  - [x] L1 集成：`PawCoinTopupIntegrationTest extends ApiIntegrationTest`（stub gateway：下单→settlement 回调→断言余额+流水+`reconcile` 平；回调重放断言不双入账；deny 回调断言余额不变）。**L1**（已编译；本地 dev 库污染阻断 context，见 Completion Notes）
  - [x] 云端只跑 **L0 绿灯**；L1/L2 留本地，Completion Notes 标注「L1 docker pg+redis / L2 Midtrans sandbox 待本地」。

## Dev Notes

> 本 story 是**接线 story**，几乎不新建原语——重活在 1.1（支付/回调/幂等）与 1.2（钱包/总账）已完成。风险集中在 AC3「回调→入账」的**同事务原子性**。

### 承接 1.1 + 1.2（务必先读这两个 spec）
- 1.1 `1-1-支付网关基础设施与幂等地基.md`：`PaymentIntentService.createIntent(...)`、`PaymentIntent`(purpose/channel/status/publicToken/amount)、`PayCallbackController`（验签+`gateway_ref` 去重）、`markPaid/markFailed`。
- 1.2 `1-2-双分录总账与pawcoin钱包.md`：`PawCoinWalletService.credit(userId, coins, type, ref, idempotencyKey)`（内部同事务：原子改钱包 + `LedgerService.post` 平衡分录 + 写 `pawcoin_transactions`）。
- **本 story 不新建表**（无 Flyway）；只加 controller/service/handler + 档位。

### 当前用户范式（照 `auth/web/MeController`，决策 C1）
```java
@RestController @RequestMapping("/api/v1/me")
public class PawCoinTopupController {
  @PostMapping("/pawcoin/topups")
  public TopupResponse topup(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTopupRequest req) {
    return topupService.create(currentUserId(jwt), req);
  }
  private static long currentUserId(Jwt jwt){ /* parse jwt.getSubject()，null/非数字→AppException.unauthorized */ }
}
```
**仅作用当前 JWT `sub`，绝不接受任意 userId（防越权）**——与 MeController 一致。

### ⚠️ 资金入账的事务坑（AC3 核心，务必遵守）
记忆库血泪：`notify` 曾用**同步 `@TransactionalEventListener(AFTER_COMMIT)` + `send()` 默认 REQUIRED** 导致 INSERT 静默不提交（通知只涨角标不进中心）。**资金入账绝不能重蹈**：
- **正确**：回调处理器 `TopupPaidHandler` 用**单个 `@Transactional`（REQUIRED，同一事务）** 顺序执行 `markPaid` + `credit`——两者要么一起提交要么一起回滚。**不拆成 AFTER_COMMIT 异步**。
- credit 幂等键 = `intent.publicToken`（1.2 的 `IdempotencyService` 去重）；`markPaid` 对已 PAID 意图幂等返回。双通道回调/重放安全。
- 若坚持用事件解耦，事件监听必须 `@EventListener`（同步、同事务）或 `AFTER_COMMIT + REQUIRES_NEW + 自身幂等`——但资金场景**首选同事务直调**，最简最稳。

### 回调按 purpose 分派
1.1 的 `PayCallbackController` 收口验签+去重后，按 `intent.purpose` 分派到对应 handler：
```
PAWCOIN_TOPUP → TopupPaidHandler（本 story）
VET_CONSULT   → （Story 3.4）
AI_UNLOCK     → （Story 2.3）
ID_HD         → （Story 6.3）
```
本 story 只实现 `PAWCOIN_TOPUP` 分支；其余 purpose 由各自 story 接（分派表留扩展点，未接的 purpose 记 WARN 日志不 crash）。

### 记账语义（1:1）
充值 `Rp{amount}` → `coins = amount`（1 koin=Rp1）。总账：`DEBIT CASH_IN amount / CREDIT FLOAT_LIABILITY amount`（平台收现金、欠用户等额 koin）；`credit` 同事务写 `pawcoin_wallets`(+amount) 与 `pawcoin_transactions`(TOPUP)。对账见 1.2 `reconcile`。

### 测试范式
- L0：`@ExtendWith(MockitoExtension.class)`；`TopupPaidHandlerTest` mock `PawCoinWalletService`，验 PAID 调 credit 一次、重放 markPaid 幂等不再 credit、FAILED 不调。
- L1：`extends ApiIntegrationTest`（stub gateway，dev profile 真 pg+redis）；下单 → 手工触发回调（stub 的 `verifyCallback` 固定 token）→ 断言 `pawcoin_wallets.balance`、`pawcoin_transactions` 一条、`ledger_entries` 平；重复回调断言余额不变（幂等）；失败回调断言不入账。
- 云端只跑 L0；L1/L2 留本地。

### 资金四不变式落点
NFR-1/2/3 由 1.1（幂等）+1.2（总账/非负）已保证，本 story 复用不重造。**AC3 的同事务原子性 = NFR-2 幂等在充值路径的兑现**。NFR-4 审计不在本 story。

### Project Structure Notes
- 新增落 `com.tailtopia.pay/{web,service,dto,domain}`；改动仅在 1.1 `PayCallbackController`/`markPaid` 的 purpose 分派扩展点（**新增分支、不改既有 1.1 语义**）。
- 无前端（1.4/1.5 做 UI）；`mode=stub` 可跑通 L0/L1，`mode=live` 才打真实 Midtrans（L2）。

### References
- [Source: _bmad-output/planning-artifacts/epics-v1.1.md#Story 1.3]
- [Source: _bmad-output/planning-artifacts/architecture-v1.1-delta.md#§2 支付聚合商][#§3.1 资金核心]
- [Source: _bmad-output/implementation-artifacts/1-1-支付网关基础设施与幂等地基.md]
- [Source: _bmad-output/implementation-artifacts/1-2-双分录总账与pawcoin钱包.md]
- 代码范式：`auth/web/MeController.java`（当前用户/`/api/v1/me`/C1）、`shared/ratelimit/{IdempotencyService,RedisRateLimiter}.java`、`shared/error/AppException.java`；事务坑记忆：`notify` AFTER_COMMIT 吞写 → 资金用同事务直调。

### 待澄清（不阻塞）
- [OPEN] Midtrans 具体 API（Snap redirect vs Core/Charge 直返二维码串）——影响 `TopupResponse` 支付载荷形态；接口先按「返回 payload map」容纳，拿到凭证后定型（同 1.1 [OPEN]）。
- [OPEN] 充值是否也走「记住上次渠道」——PRD 明确 V1.1 不做（OQ-8），本 story 每次显式选。

## Dev Agent Record

### Agent Model Used

claude-opus-4-8（bmad-dev-story 流程，本地 darwin）。

### Debug Log References

- `mvnw -B test -Dtest=PawCoinTopupServiceTest,TopupPaidHandlerTest` → **6/6 绿**（L0）。
- 回调→到账全链路逐步推演（无迁移，无 scratch DDL 可验）：resolve(by gatewayRef `stub-<token>`) → markPaid → 同步同事务 `credit(key=publicToken)` → 提交；重放 applyCallback 早返回（已终态）不再发事件；deny 只 markFailed 不发事件 → 不 credit。
- L1 `PawCoinTopupIntegrationTest` 已编译；运行时因本地 dev 库污染（同 1.1/1.2）阻断 context。

### Completion Notes List

- **接线 story，零新迁移**（复用 V47/V48）。重活在 1.1（支付/回调/幂等）+ 1.2（钱包/总账）已完成，本 story 把两者接成充值闭环。**L0 全绿**；`mvn -B compile` 通过。
- **AC1 下单**：`POST /api/v1/me/pawcoin/topups`（JWT，仅作用当前 `sub` 防越权，照 MeController/C1）→ `createIntent(PAWCOIN_TOPUP)`（1.1 幂等 + rl:pay:create 写限流）→ `gateway.createCharge` 取 QRIS/DANA 载荷 + `attachCharge` 回填网关订单号（幂等，同 Idempotency-Key 重放不重复 charge）→ 回 `TopupResponse`（对外 token + 载荷，**无自增 id**）。非法档位/渠道 → 422，未登录 → 401。
- **AC2 固定档位**：`TopupTier`（10k/25k/50k/100k，coins=amount 1:1）内置于 pay 模块；`TopupTierProvider` 接口 + `Default` 实现——**后台可配是 9.2**，届时换 DB 实现不动其余代码。
- **AC3 回调原子入账（最高风险，血泪护栏）**：`TopupPaidHandler` 用**同步 `@EventListener` + `@Transactional(Propagation.MANDATORY)`** 监听 1.1 在 `applyCallback` 内（markPaid 后、同一 `@Transactional`）发布的 `PaymentIntentPaidEvent`——`publishEvent` 同线程内联触发，handler **强制加入同一事务**（MANDATORY：无活动事务即抛，杜绝脱事务/异步误用），`markPaid` 与 `credit`（原子改钱包 + 平衡分录 + 写流水）**要么一起提交要么一起回滚**。**绝不用 `@TransactionalEventListener(AFTER_COMMIT)`**（记忆库血泪：notify AFTER_COMMIT+默认 REQUIRED 静默吞写；资金重蹈将丢账）——已在 handler 类注释显式钉死。
- **AC3 幂等（双通道/重放绝不重复入账）**：① 1.1 `applyCallback` 对已 PAID 意图早返回、**不再发事件** → handler 不重触发；② credit 幂等键 = `intent.publicToken`（1.2 IdempotencyService + 总账 `(idempotency_key,account,direction)` 唯一约束）双保险。
- **AC4 失败/取消不入账**：1.1 仅在 PAID 发 `PaymentIntentPaidEvent`；`FAILED/EXPIRED` 只 `markFailed/markExpired` 不发事件 → handler 不触发 → **不 credit、不写流水、余额不变**。
- **AC3 记账语义（1:1）**：充值 `Rp{amount}` → `coins=amount`；1.2 credit(TOPUP) 落 `DEBIT CASH_IN / CREDIT FLOAT_LIABILITY` 平衡分录 + `pawcoin_wallets(+amount)` + `pawcoin_transactions(TOPUP)`，同事务；对账见 1.2 `reconcile`。
- **对 1.1 的最小增量**：`PaymentIntentService` 新增 `findByToken`（读）+ `attachCharge`（下单回填网关订单号，幂等）——**新增方法、不改既有 applyCallback/markPaid 语义**（回调分派沿用已有 `PaymentIntentPaidEvent` hook，未动 1.1 收口）。
- **⚠️ L1（docker pg+redis）/ L2（Midtrans sandbox）待本地验收**：完整 `PawCoinTopupIntegrationTest`（stub gateway 下单→回调→到账/重放/失败三态）被本地 dev 库污染阻断（同 1.1/1.2，用户决策仅 L0）；同事务原子性属标准 Spring 同步事件语义，逻辑已推演验证。L2：`mode=live` + Midtrans sandbox 凭证跑通真实 QRIS 充值到账（`TopupResponse.payload` 形态随 Snap/Core API 定型，接口已容纳）。

### File List

**新增（后端 main）**
- `petgo-backend/src/main/java/com/tailtopia/pay/domain/TopupTier.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/service/TopupTierProvider.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/dto/CreateTopupRequest.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/dto/TopupResponse.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/service/PawCoinTopupService.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/web/PawCoinTopupController.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/service/TopupPaidHandler.java`

**修改（后端 main）**
- `petgo-backend/src/main/java/com/tailtopia/pay/service/PaymentIntentService.java`（新增 `findByToken` + `attachCharge`，供 1.3 下单；不改既有语义）

**新增（后端 test）**
- `petgo-backend/src/test/java/com/tailtopia/pay/service/PawCoinTopupServiceTest.java`
- `petgo-backend/src/test/java/com/tailtopia/pay/service/TopupPaidHandlerTest.java`
- `petgo-backend/src/test/java/com/tailtopia/pay/PawCoinTopupIntegrationTest.java`

### Change Log

- 2026-07-11：实现 Story 1.3 PawCoin 充值下单与到账（下单端点 + 固定档位 + 回调同事务原子到账 handler）。接 1.1/1.2 成闭环，零新迁移。L0 6/6 绿；同事务/幂等/失败不入账逻辑推演验证；L1/L2 待本地（dev 库污染，用户决策仅 L0）。
