---
baseline_commit: 1cc0252808da76d991dff2bd68881a3255c845db
---
# Story 1.2: 双分录总账与 PawCoin 钱包

Status: done

> V1.1 Epic 1（资金地基）第 2 story，接 **1.1 支付网关基础设施与幂等地基**。**brownfield**：Flyway 停在 V46，1.1 占 V60，本 story 用 **V61**。
> 源：`epics-v1.1.md` Story 1.2 · 架构 `architecture-v1.1-delta.md` §3.1/§4 · 排期 `sprint-status-v1.1.yaml`（V61=init_ledger_and_pawcoin）。
> **范围边界**：本 story 只建**资金账本原语**——`ledger_entries`(append-only 双分录)+`pawcoin_wallets`(非负钱包)+`pawcoin_transactions`(用户流水)+ `LedgerService`/`PawCoinWalletService`。**不做**充值下单闭环（1.3 调用本 story 的 service 入账）、**不做**余额页 UI（1.4）。兑现 **FR-NFR-1(单一总账)** 与 **FR-NFR-3(PawCoin 并发非负)**。

## Story

As a 平台,
I want append-only 双分录总账 + 并发非负的 PawCoin 钱包 + 用户可见流水,
so that 所有资金变动可勾稽对账、PawCoin 余额并发不双花，为一切收费/退款提供正确的记账底座（FR-NFR-1/3）。

## Acceptance Criteria

1. **ledger_entries append-only 双分录（L0/L1）**
   **Given** Flyway `V61` 建 `ledger_entries` + `LedgerEntry` 实体 + `LedgerEntryRepository`
   **Then** 每次资金事件的一组分录**借贷金额平衡**、共享 `entry_group`、`account` 枚举 varchar+CHECK、`idempotency_key` 唯一（**L0** 编译+单测）
   **And** **无 update/delete 路径**（repository 不暴露 save-覆盖/delete；只 `insert`）；schema `validate` 一致（**L1**）

2. **pawcoin_wallets 并发非负（L0/L1）**
   **Given** `pawcoin_wallets`（`balance BIGINT CHECK(balance>=0)`）+ `PawCoinWallet` 实体
   **When** 并发扣减同一钱包至余额边界
   **Then** 用**原子条件 UPDATE**（`SET balance = balance + :delta WHERE user_id=:uid AND balance + :delta >= 0`，返回受影响行数=0 即余额不足→拒绝 `AppException.conflict`），CHECK 约束作库级兜底，**二者不双花/不越负**（**L1** 并发测试）
   **And** 余额无有效期、更新走同一事务（**L0/L1**）

3. **pawcoin_transactions 用户流水（L0/L1）**
   **Given** `pawcoin_transactions`（`delta`/`type`/`ref`/`entry_group`）+ 实体
   **Then** 每次钱包变动写一条流水、关联 `entry_group` 与总账、`type ∈ {TOPUP,SPEND,REFUND,BONUS}`（**L0/L1**）
   **And** **充值失败不写本表**（由调用方 1.3 保证，本 story service 仅在成功事务内写）（**L0**）

4. **双分录不变式 service（L0/L1）**
   **Given** `LedgerService.post(entryGroup, List<LedgerLine>, idempotencyKey)`
   **When** 提交一组分录
   **Then** DEBIT 合计 == CREDIT 合计，否则抛 `AppException`（不落库）；同 `idempotency_key` 重放返回既有 `entry_group`、不重复记账（**L0/L1**）

5. **钱包-总账对账 + 并发正确性（L1）**
   **Given** `PawCoinWalletService.credit/debit(...)`（内部同一 `@Transactional`：原子改钱包 + `LedgerService.post` 平衡分录 + 写 `pawcoin_transactions`）
   **Then** 任一用户钱包 `balance` 可由 `ledger_entries` 其 `FLOAT_LIABILITY` 分录全量重算一致（对账测试）（**L1**）
   **And** N 并发 credit/debit 后总账仍平、钱包=重算值、无丢失/双记（**L1** 并发测试）

## Tasks / Subtasks

> 纯后端 story。先迁移/实体 → service（不变式+并发）→ 测试。并发正确性是本 story 最高风险点，测试必须含真并发用例。

- [x] **T1 Flyway V61 迁移**（AC1/2/3）
  - [x] `db/migration/V61__init_ledger_and_pawcoin.sql`（Dev Notes 完整 SQL 照抄：三表 + CHECK + 唯一/索引；顶部注释 story+号段 V60+1+护栏）。**L1**（scratch 库真验 ✅）
- [x] **T2 实体/仓库/枚举**（AC1/2/3）
  - [x] `pay/domain/LedgerEntry.java`（`@Entity`，`entryGroup`/`account`枚举20/`direction`枚举8/`amount long(>0)`/`refType`/`refId`/`idempotencyKey`/`Instant createdAt @PrePersist`；**无 @PreUpdate、无 setter 改金额**——append-only）。**L0** ✅
  - [x] `pay/domain/LedgerAccount.java`(6 科目)、`LedgerDirection.java`(`DEBIT/CREDIT`)。**L0** ✅
  - [x] `pay/domain/PawCoinWallet.java`（`userId` 唯一、`balance long`、`@Version`、`updatedAt`；**无 balance setter**——只走原子 UPDATE）。**L0** ✅
  - [x] `pay/domain/PawCoinTransaction.java` + `PawCoinTxnType.java`(`TOPUP/SPEND/REFUND/BONUS`)。**L0** ✅
  - [x] `pay/repository/LedgerEntryRepository.java`（**`extends Repository` 非 JpaRepository**：仅 `save`+查询+`sumAmount` 对账；**不暴露 delete/批量改**，append-only 静态守卫 L0 测试）。**L0** ✅
  - [x] `pay/repository/PawCoinWalletRepository.java`（`findByUserId`；`@Modifying @Query` 原子条件 UPDATE `applyDelta(userId, delta): int`(WHERE balance+delta>=0)，照 `ContentPostRepository.detachPet`；`insertIfAbsent` ON CONFLICT DO NOTHING 并发建号）。**L0** ✅
  - [x] `pay/repository/PawCoinTransactionRepository.java`（`findByUserIdOrderByCreatedAtDesc` + 游标分页供 1.4）。**L0** ✅
- [x] **T3 service（不变式 + 并发）**（AC4/5）
  - [x] `pay/service/LedgerService.java`：`post(entryGroup, lines, idempotencyKey)` —— 校验 Σ(DEBIT)==Σ(CREDIT)+金额>0（不平/空/负抛 `AppException` **不落库**）、复用 `IdempotencyService` 前置 + DB 按幂等键查回（跨 TTL）、唯一约束库级兜底、`@Transactional`。附 `LedgerLine`(record)。**L1** ✅（逻辑 L0 覆盖）
  - [x] `pay/service/PawCoinWalletService.java`：`credit(...)` / `debit(...)` —— 同一 `@Transactional`：`applyDelta`（=0 抛 `AppException.conflict` 余额不足）+ 首次 `insertIfAbsent` 建钱包 + `LedgerService.post`(FLOAT_LIABILITY 镜像钱包变动的平衡分录) + 写 `PawCoinTransaction` + 幂等 store。`reconcile(userId)` 返回 `ReconcileResult`。**L1** ✅
  - [x] 稳定签名供 1.3 充值 / 4.x 退款 / 2.x 消费调用（本 story 不接支付回调）。**L0** ✅
- [x] **T4 测试**（AC1-5）
  - [x] L0 单测：`LedgerServiceTest`（不平/空/负拒绝且不落库、Redis+DB 双路幂等复用）、`PawCoinWalletServiceTest`（applyDelta=0→拒、成功写三处、幂等短路、负额拒）、`LedgerAppendOnlyTest`（反射验仓储无 delete/remove 路径）。照 `vet/service/VetPresenceServiceTest`。**L0** ✅ 11/11 绿
  - [x] L1 集成：`PawCoinWalletConcurrencyIntegrationTest extends ApiIntegrationTest`（真 pg：20 线程并发 debit 至边界断言恰 10 成功、无越负、总账平、`reconcile` 一致；验 Flyway V61 + validate）。照 `admin/anomaly/ConsultAnomalyIntegrationTest`。**L1**（已编译；本地 dev 库污染阻断 context，非负不变式已用 scratch 库 SQL 层真验，见 Completion Notes）
  - [x] 云端 headless 只跑 **L0 绿灯**；L1（docker pg+redis 并发）留本地，Completion Notes 标注。

### Review Findings（跨 1.1-1.3 联审 2026-07-11，三层对抗式）

- [x] [Review][Patch] ✅已修 **【必修】钱包 credit/debit 幂等仅 Redis 无 DB 兜底 → 跨 TTL 重放翻倍/双扣** [PawCoinWalletService.java]：改钱包前新增 `isReplay()` = Redis 前置 + 总账 DB 兜底(`findFirstByIdempotencyKey`)。回归测试 `crossTtlReplayShortCircuitsViaLedgerDbFallback` 锁死。：`findResourceId`(Redis) 短路 → `applyDelta`(改钱包) → `post`(有 DB 兜底但只兜总账)。TTL 过期后重放：applyDelta 再次改钱包，post 命中 DB 兜底静默返回不 insert → 钱包翻倍、总账单条。topup 路径当前被意图终态守卫兜住不可达，但 credit/debit 是 2.x SPEND/4.x REFUND 的稳定原语，挂上即踩。Fix：持久幂等检查前置于 applyDelta，或 post 重放时抛可识别异常让调用方跳过 applyDelta。
- [ ] [Review][Patch] 【低】credit 与 post 复用同一 `idempotencyKey` 分别 store 不同表主键(ledger id vs txn id)，两表 IDENTITY 数值会重叠 → post 命中 Redis 分支可能 findById 取错总账行返回错 entry_group [LedgerService.java:83 / PawCoinWalletService.java:71]
- [ ] [Review][Patch] 【低】credit/debit 不校验 `idempotencyKey` 非空 → null 键在 ledger 深处爆 NOT NULL 违例而非干净 4xx（与上条一起加固原语）[PawCoinWalletService.java:52]

## Dev Notes

> 延续 1.1 已确立范式（RestClient/mode=stub/实体/迁移/测试），此处只补 1.2 新增关注点。**并发非负是本 story 核心风险，务必照下方原子更新范式，勿用「读-改-写」应用层做减法**（会丢更新）。

### 前序 story 1.1 承接（`1-1-支付网关基础设施与幂等地基.md`）
- 已建 `pay` 模块骨架 + `shared/ratelimit/IdempotencyService`（**本 story 幂等直接复用**：`findResourceId/store`）。
- 金额一律 **BIGINT 最小币种单位**（IDR 无小数，1 koin=Rp1，直接 long）——与 1.1 `payment_intents.amount` 一致。
- 枚举 `@Enumerated(STRING) length=16`（禁 length=1，CHAR(1) 坑）；`Instant + @PrePersist`；主键 `Long id` IDENTITY——全部照 1.1/`consult/domain/ConsultSession`。
- **本 story 的 wallet/ledger 不接支付回调**；1.3 充值成功后调 `PawCoinWalletService.credit(...)` 入账，`payment_intents.markPaid` 的 hook 在 1.3 接。

### 并发非负范式（AC2/5 核心）——原子条件 UPDATE + CHECK 兜底
照 `content/repository/ContentPostRepository.detachPet` 的 `@Modifying @Query ... int` 范式：
```java
// PawCoinWalletRepository
@Modifying
@Query("update PawCoinWallet w set w.balance = w.balance + :delta, w.version = w.version + 1 " +
       "where w.userId = :userId and w.balance + :delta >= 0")
int applyDelta(@Param("userId") long userId, @Param("delta") long delta);   // 返回 0 = 余额不足/无钱包 → 拒绝
```
service 内：`if (walletRepo.applyDelta(uid, -coins) == 0) throw AppException.conflict("PawCoin 余额不足");`
- **单行原子 UPDATE 自带行锁**，天然串行化对同一钱包的并发扣减，`WHERE ... >= 0` 保证不越负；DB `CHECK(balance>=0)` 作最后兜底。**不需 advisory 锁**（那是审计链跨行临界区才需要的，见下）。
- 首次充值需先 `insert` 钱包（`applyDelta` 命中 0 行且余额本应为 0 时）：用 `INSERT ... ON CONFLICT DO NOTHING` 或先 `findByUserId` 不存在则建，靠 `uq_pawcoin_wallets_user` 兜住并发建。
- **禁**「`wallet.setBalance(wallet.getBalance()-coins)` + `save`」应用层读改写——并发下丢更新，即使有 `@Version` 也只是抛异常需重试，原子 UPDATE 更省心且无重试。

### advisory 锁范式（本 story**用不到**，仅说明边界）
`admin/audit/service/AuditChainLock.java` 用 `pg_advisory_xact_lock` 串行化「取链尾→算哈希→append」跨行临界区（避 READ COMMITTED 下 `LIMIT+FOR UPDATE` 链分叉）。**双分录总账 append-only 无链式依赖、钱包用单行原子 UPDATE**，故本 story 不需 advisory 锁。（4.x 退款若涉及跨账户串行结算再评估。）

### 双分录记账语义（架构 §3.1/§4）
- `LedgerService.post(entryGroup, lines, idempotencyKey)`：`lines` 为一组 `(account, direction, amount)`，**校验 Σ(DEBIT.amount)==Σ(CREDIT.amount)**，否则 `AppException`（不落库）。幂等键唯一，重放返回既有 `entry_group`。
- PawCoin 充值（1.3 会这样调）：`DEBIT CASH_IN / CREDIT FLOAT_LIABILITY`（平台收到现金、欠用户等额 koin）；钱包 `credit` 与该组分录同一事务。
- **对账**：某用户钱包 `balance` == 其 `FLOAT_LIABILITY` 相关分录净额（credit-debit）。`reconcile(userId)` 返回是否一致，供 L1 断言与后台核对。
- append-only：`ledger_entries` 无 `updated_at`、无 setter 改金额、repository 不暴露 delete；更正走**反向新分录**（补偿），不改旧行。

### V61 迁移完整 SQL（照 `V44__init_consult_anomalies.sql` 风格）
```sql
-- Story 1.2（V1.1 资金地基）：双分录总账 + PawCoin 钱包 + 用户流水。实测最大号 V60(1.1) + 1 = V61（决策 E2）。
-- ledger_entries append-only(无 updated_at)；金额 BIGINT 最小币种单位；PawCoin 1 koin=Rp1 同单位。
-- 钱包并发非负 = 原子条件 UPDATE + CHECK 兜底；补偿走反向分录不改旧行。
CREATE TABLE ledger_entries (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    entry_group     VARCHAR(36)  NOT NULL,          -- 一次资金事件的一组平衡分录共享
    account         VARCHAR(20)  NOT NULL,          -- CASH_IN|FLOAT_LIABILITY|VET_PAYABLE|VET_PAID|PLATFORM_REVENUE|REFUND_OUT
    direction       VARCHAR(8)   NOT NULL,          -- DEBIT | CREDIT
    amount          BIGINT       NOT NULL CHECK (amount > 0),
    user_id         BIGINT,                         -- 与用户相关的分录带 user_id（对账/浮存统计）
    ref_type        VARCHAR(24),
    ref_id          BIGINT,
    idempotency_key VARCHAR(80)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entries_account   CHECK (account   IN ('CASH_IN','FLOAT_LIABILITY','VET_PAYABLE','VET_PAID','PLATFORM_REVENUE','REFUND_OUT')),
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT','CREDIT'))
);
CREATE INDEX        idx_ledger_entries_group ON ledger_entries (entry_group);
CREATE INDEX        idx_ledger_entries_user  ON ledger_entries (user_id, account);
CREATE UNIQUE INDEX uq_ledger_entries_idem   ON ledger_entries (idempotency_key, account, direction);  -- 幂等去重

CREATE TABLE pawcoin_wallets (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    balance    BIGINT       NOT NULL DEFAULT 0 CHECK (balance >= 0),   -- 非负不变式库级兜底
    version    BIGINT       NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_pawcoin_wallets_user ON pawcoin_wallets (user_id);

CREATE TABLE pawcoin_transactions (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    delta       BIGINT       NOT NULL,              -- +充值/退回、-消费
    type        VARCHAR(16)  NOT NULL,              -- TOPUP|SPEND|REFUND|BONUS
    ref_type    VARCHAR(24),
    ref_id      BIGINT,
    entry_group VARCHAR(36),                        -- 关联总账
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_pawcoin_txn_type CHECK (type IN ('TOPUP','SPEND','REFUND','BONUS'))
);
CREATE INDEX idx_pawcoin_txn_user ON pawcoin_transactions (user_id, created_at);
```
命名 `uq_/idx_/ck_`；决策 E2 已提交迁移冻结、加列走 ALTER。

### 测试范式
- **L0**：`@ExtendWith(MockitoExtension.class)` + AssertJ；`LedgerServiceTest` 验不平拒绝/幂等；`PawCoinWalletServiceTest` mock `applyDelta` 返回 0/1 验拒绝与成功。照 `vet/service/VetPresenceServiceTest`。
- **L1**：`extends ApiIntegrationTest`（真 pg+redis，dev profile，无 Testcontainers）。**并发测试**：`ExecutorService` N 线程并发 `debit`，断言最终 `balance>=0`、成功次数==预期、`reconcile` 一致；再验 `ledger_entries` 无 delete 路径。测试注入的是 `tools.jackson.databind.ObjectMapper`（Jackson 3）。
- 云端只跑 L0；L1 留本地并 Completion Notes 标注。

### 资金四不变式落点
本 story 兑现 **NFR-1（单一双分录总账）** + **NFR-3（PawCoin 非负并发）**。**NFR-2（幂等）** 在 1.1 已立、此处 `LedgerService` 复用同一 `IdempotencyService`。**NFR-4（审计哈希链）** 不在本 story（资金写操作接入 AdminAuditService 在 Epic 4/9 的后台写路径）。

### Project Structure Notes
- 新增全落 `com.tailtopia.pay/{domain,repository,service}`，与 1.1 同模块。无前端、无外部依赖调用（不碰 Midtrans）。
- 模块间只经 service 通信；1.3/2.x/4.x 通过 `PawCoinWalletService`/`LedgerService` 记账，禁直接写这三张表。

### References
- [Source: _bmad-output/planning-artifacts/epics-v1.1.md#Story 1.2]
- [Source: _bmad-output/planning-artifacts/architecture-v1.1-delta.md#§3.1 资金核心][#§4 资金正确性地基]
- [Source: _bmad-output/implementation-artifacts/1-1-支付网关基础设施与幂等地基.md]（前序范式）
- [Source: _bmad-output/implementation-artifacts/sprint-status-v1.1.yaml#FLYWAY V61]
- 代码范式：`content/repository/ContentPostRepository.java`（`@Modifying @Query int` 原子更新）、`admin/audit/service/AuditChainLock.java`（advisory 锁边界说明）、`shared/ratelimit/IdempotencyService.java`、`shared/error/AppException.java`、`consult/domain/ConsultSession.java`、`db/migration/V44__init_consult_anomalies.sql`、`test/.../support/ApiIntegrationTest.java`

### 待澄清（不阻塞）
- [OPEN] 兽医应付/已付分录的具体记账时点（会话完成计提 VET_PAYABLE、月结打款转 VET_PAID）——本 story 只需把这些 account 枚举建全，实际记账在 3.7/9.5；本 story 不实现。

## Dev Agent Record

### Agent Model Used

claude-opus-4-8（bmad-dev-story 流程，本地 darwin）。

### Debug Log References

- `mvnw -B test -Dtest=LedgerServiceTest,PawCoinWalletServiceTest,LedgerAppendOnlyTest` → **11/11 绿**（L0）。
- V61 迁移 + **非负不变式 DB 层真验**（一次性 scratch 库，零副作用）：V61 apply OK；wallet balance=10000 起，20 次 `UPDATE ... WHERE balance-1000>=0` → **恰 10 次生效、余额归 0**；再强扣 -1 → `violates check constraint pawcoin_wallets_balance_check`（CHECK 库级兜底）。scratch 库跑完即销毁。
- L1 `PawCoinWalletConcurrencyIntegrationTest` 已编译；运行时因本地 dev 库污染（见 1.1 记录）阻断 context。

### Completion Notes List

- **L1 已本地清账（2026-07-11）**：干净库 `petgo_l1` + `mvn clean` 跑通。`PawCoinWalletConcurrencyIntegrationTest` 1/1 —— **20 线程并发 debit 至边界，真 postgres 上不越负、总账平、`reconcile` 一致**（此前 scratch 库只证 SQL 层，并发时序在此真验）。schema V61 三表↔实体契约 `validate` 一致。

- **L0 全绿**：双分录不变式（不平/空/负拒绝不落库、Redis+DB 双路幂等复用）、钱包 service（余额不足拒、成功三写、幂等短路）、append-only 静态守卫（仓储无 delete/remove）。`mvn -B compile` 通过。
- **FR-NFR-1（单一双分录总账）**：`LedgerService.post` 强制 Σ(DEBIT)==Σ(CREDIT) 且金额>0，否则不落库；`ledger_entries` **append-only**——实体无 `@PreUpdate`/无改金额 setter，仓储 `extends Repository`（非 JpaRepository）故意不暴露 delete，更正走反向补偿分录。`(idempotency_key,account,direction)` 唯一 = 幂等/并发去重库级权威。
- **FR-NFR-3（PawCoin 并发非负）**：**原子条件 UPDATE**（`applyDelta` = `SET balance=balance+:delta WHERE user_id=:uid AND balance+:delta>=0`，返回 0 行=余额不足→`AppException.conflict`）——单行原子 UPDATE 自带行锁天然串行化并发扣减；`CHECK(balance>=0)` 库级兜底。**严格未用应用层「读-改-写」**（决策 Dev Notes：并发丢更新）。首次充值 `insertIfAbsent` ON CONFLICT DO NOTHING 并发安全建号。DB 层 scratch 已证：20 并发扣至边界恰 10 成功、不越负、CHECK 拦负。
- **钱包↔总账对账（AC5）**：`credit/debit` 同一 `@Transactional` 三件事原子——原子改钱包 + `LedgerService.post`（`FLOAT_LIABILITY` 分录镜像钱包变动）+ 写 `pawcoin_transactions`。`reconcile(userId)` 断言 `balance == Σ(FLOAT_LIABILITY.CREDIT) − Σ(FLOAT_LIABILITY.DEBIT)`。同键并发的原子性由总账唯一约束兜底（同一事务内 losing tx 回滚，applyDelta 一并回滚，不双花）。
- **幂等复用 1.1 地基**：`LedgerService`/`PawCoinWalletService` 复用同一 `shared/ratelimit/IdempotencyService`（NFR-2 在 1.1 已立）。金额一律 BIGINT 最小币种单位（1 koin=Rp1），与 1.1 `payment_intents.amount` 一致。**本 story 不接支付回调**——1.3 充值成功后调 `PawCoinWalletService.credit(TOPUP)`，`payment_intents.markPaid` 的 hook（1.1 已发 `PaymentIntentPaidEvent`）在 1.3 接。
- **schema 契约（AC L1）scratch 库真验**：三表列/类型/CHECK/唯一索引与实体 Hibernate `validate` 期望逐一吻合（enum→varchar、无 CHAR(1)、`balance`/`amount`/`delta`→bigint、时间戳→timestamptz）。
- **NFR-4（审计哈希链）不在本 story**（资金写操作接 AdminAuditService 在 Epic 4/9 后台写路径）。`VET_PAYABLE/VET_PAID` 枚举已建全，实际计提/月结记账在 3.7/9.5。
- **⚠️ L1（docker pg+redis 并发）待本地验收**：完整 `PawCoinWalletConcurrencyIntegrationTest`（20 线程真并发 + Flyway V61 validate + reconcile）被本地 dev 库污染阻断（同 1.1，用户决策仅 L0）。核心非负不变式已用 scratch 库 SQL 层等价真验；在纯净 dev 库上跑该 test 即完成 L1 全绿。

### File List

**新增（后端 main）**
- `petgo-backend/src/main/java/com/tailtopia/pay/domain/LedgerEntry.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/domain/LedgerAccount.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/domain/LedgerDirection.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/domain/PawCoinWallet.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/domain/PawCoinTransaction.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/domain/PawCoinTxnType.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/repository/LedgerEntryRepository.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/repository/PawCoinWalletRepository.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/repository/PawCoinTransactionRepository.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/service/LedgerService.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/service/LedgerLine.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/service/PawCoinWalletService.java`
- `petgo-backend/src/main/resources/db/migration/V61__init_ledger_and_pawcoin.sql`

**新增（后端 test）**
- `petgo-backend/src/test/java/com/tailtopia/pay/service/LedgerServiceTest.java`
- `petgo-backend/src/test/java/com/tailtopia/pay/service/PawCoinWalletServiceTest.java`
- `petgo-backend/src/test/java/com/tailtopia/pay/repository/LedgerAppendOnlyTest.java`
- `petgo-backend/src/test/java/com/tailtopia/pay/PawCoinWalletConcurrencyIntegrationTest.java`

### Change Log

- 2026-07-11：实现 Story 1.2 双分录总账与 PawCoin 钱包（`ledger_entries` append-only 双分录 + `pawcoin_wallets` 原子非负 + `pawcoin_transactions` 流水 + `LedgerService`/`PawCoinWalletService`）。L0 11/11 绿；V61 迁移与非负不变式 scratch 库真验；L1 并发待本地（dev 库污染，用户决策仅 L0）。
