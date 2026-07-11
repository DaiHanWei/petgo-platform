# Story 1.3: PawCoin 充值下单与到账

Status: ready-for-dev

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

- [ ] **T1 档位**（AC2）
  - [ ] `pay/domain/TopupTier.java`（枚举或常量：10k/25k/50k/100k，`amountIdr`=`coins`）+ `pay/service/TopupTierProvider.java`（接口，本 story 内置实现；9.2 换 DB）。**L0**
- [ ] **T2 充值下单**（AC1）
  - [ ] `pay/dto/CreateTopupRequest.java`（record：`tierId`/`channel`，Bean Validation）+ `TopupResponse.java`（record：`intentToken` + 支付载荷；**不含自增 id**）。**L0**
  - [ ] `pay/web/PawCoinTopupController.java`（`@RestController @RequestMapping("/api/v1/me")`，`@AuthenticationPrincipal Jwt jwt` + `currentUserId(jwt)`，`POST /pawcoin/topups`）。照 `auth/web/MeController`（决策 C1）。**L1**
  - [ ] `pay/service/PawCoinTopupService.java`：校验档位 → `PaymentIntentService.createIntent(PAWCOIN_TOPUP,...)` → 返回载荷。写端点限流 `RedisRateLimiter` + `Idempotency-Key`。**L1**
- [ ] **T3 回调入账接线**（AC3/4 — 最高风险）
  - [ ] 在 1.1 `PayCallbackController`/`PaymentIntentService.markPaid` 的**按 purpose 分派**点，为 `PAWCOIN_TOPUP` 注册处理器 `TopupPaidHandler`。**L0**
  - [ ] `pay/service/TopupPaidHandler.java`：**同一 `@Transactional`** 内 `markPaid(intent)` + `PawCoinWalletService.credit(intent.userId, intent.amount, TOPUP, refType=PAYMENT_INTENT, refId=intent.id, idempotencyKey=intent.publicToken)`。幂等：已 PAID 直接返回。**L1**
  - [ ] `FAILED/EXPIRED` 分支：只 `markFailed`，**不调 credit**。**L1**
- [ ] **T4 测试**（AC1-5）
  - [ ] L0 单测：`PawCoinTopupServiceTest`（非法档位拒绝、下单调 createIntent）、`TopupPaidHandlerTest`（mock：PAID→credit 一次、重放→不再 credit、FAILED→不 credit）。**L0**
  - [ ] L1 集成：`PawCoinTopupIntegrationTest extends ApiIntegrationTest`（stub gateway：下单→模拟回调→断言余额+流水+总账平；回调重放断言不双入账；失败回调断言余额不变）。**L1**
  - [ ] 云端只跑 **L0 绿灯**；L1/L2 留本地，Completion Notes 标注「L1 docker pg+redis / L2 Midtrans sandbox 待本地」。

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

### Debug Log References

### Completion Notes List

### File List
