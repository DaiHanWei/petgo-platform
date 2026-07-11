# Story 1.2: 双分录总账与 PawCoin 钱包

Status: ready-for-dev

> V1.1 Epic 1（资金地基）第 2 story，接 **1.1 支付网关基础设施与幂等地基**。**brownfield**：Flyway 停在 V46，1.1 占 V47，本 story 用 **V48**。
> 源：`epics-v1.1.md` Story 1.2 · 架构 `architecture-v1.1-delta.md` §3.1/§4 · 排期 `sprint-status-v1.1.yaml`（V48=init_ledger_and_pawcoin）。
> **范围边界**：本 story 只建**资金账本原语**——`ledger_entries`(append-only 双分录)+`pawcoin_wallets`(非负钱包)+`pawcoin_transactions`(用户流水)+ `LedgerService`/`PawCoinWalletService`。**不做**充值下单闭环（1.3 调用本 story 的 service 入账）、**不做**余额页 UI（1.4）。兑现 **FR-NFR-1(单一总账)** 与 **FR-NFR-3(PawCoin 并发非负)**。

## Story

As a 平台,
I want append-only 双分录总账 + 并发非负的 PawCoin 钱包 + 用户可见流水,
so that 所有资金变动可勾稽对账、PawCoin 余额并发不双花，为一切收费/退款提供正确的记账底座（FR-NFR-1/3）。

## Acceptance Criteria

1. **ledger_entries append-only 双分录（L0/L1）**
   **Given** Flyway `V48` 建 `ledger_entries` + `LedgerEntry` 实体 + `LedgerEntryRepository`
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

- [ ] **T1 Flyway V48 迁移**（AC1/2/3）
  - [ ] `db/migration/V48__init_ledger_and_pawcoin.sql`（见 Dev Notes 完整 SQL：三表 + CHECK + 唯一/索引；顶部注释写清 story+号段推导+护栏）。**L1**
- [ ] **T2 实体/仓库/枚举**（AC1/2/3）
  - [ ] `pay/domain/LedgerEntry.java`（`@Entity`，`entryGroup`/`account`枚举/`direction`枚举/`amount long`/`refType`/`refId`/`idempotencyKey`/`Instant createdAt @PrePersist`；**无 @PreUpdate、无 setter 改金额**——append-only）。**L0**
  - [ ] `pay/domain/LedgerAccount.java`(`CASH_IN/FLOAT_LIABILITY/VET_PAYABLE/VET_PAID/PLATFORM_REVENUE/REFUND_OUT`)、`LedgerDirection.java`(`DEBIT/CREDIT`)。**L0**
  - [ ] `pay/domain/PawCoinWallet.java`（`userId` 唯一、`balance long`、`@Version`、`updatedAt`）。**L0**
  - [ ] `pay/domain/PawCoinTransaction.java` + `PawCoinTxnType.java`(`TOPUP/SPEND/REFUND/BONUS`)。**L0**
  - [ ] `pay/repository/LedgerEntryRepository.java`（`insert`-only 语义：仅 `save` 新实体 + 查询；`sumByUserIdAndAccount(...)` 对账；**不提供 delete/批量改**）。**L0**
  - [ ] `pay/repository/PawCoinWalletRepository.java`（`findByUserId`；`@Modifying @Query` 原子条件 UPDATE `applyDelta(userId, delta): int`，照 `ContentPostRepository.detachPet` 范式）。**L0**
  - [ ] `pay/repository/PawCoinTransactionRepository.java`（`findByUserIdOrderByCreatedAtDesc` 游标分页供 1.4）。**L0**
- [ ] **T3 service（不变式 + 并发）**（AC4/5）
  - [ ] `pay/service/LedgerService.java`：`post(entryGroup, lines, idempotencyKey)` —— 校验 DEBIT合计==CREDIT合计（不平抛 `AppException`）、复用 `shared/ratelimit/IdempotencyService` 幂等、`@Transactional`。**L1**
  - [ ] `pay/service/PawCoinWalletService.java`：`credit(userId, coins, type, ref, idempotencyKey)` / `debit(...)` —— 同一 `@Transactional`：`walletRepo.applyDelta`（=0 抛 `AppException.conflict` 余额不足）+ 首次自动建钱包(insert，唯一约束兜底并发建)+ `LedgerService.post`(CASH_IN↔FLOAT_LIABILITY 等平衡分录) + 写 `PawCoinTransaction`。`reconcile(userId)` 对账方法。**L1**
  - [ ] 领域事件 hook 预留：`credit/debit` 对外暴露稳定签名供 1.3 充值 / 4.x 退款 / 2.x 消费调用（本 story 不接支付回调）。**L0**
- [ ] **T4 测试**（AC1-5）
  - [ ] L0 单测：`LedgerServiceTest`（不平拒绝、幂等复用 mock `IdempotencyService`）、`PawCoinWalletServiceTest`（mock repo：applyDelta=0→拒、成功路径写三处）。照 `vet/service/VetPresenceServiceTest`。**L0**
  - [ ] L1 集成：`PawCoinWalletConcurrencyIntegrationTest extends ApiIntegrationTest`（真 pg：N 线程并发 debit 至边界断言无越负、总账平、`reconcile` 一致；append-only 验 delete 不可达）。照 `admin/anomaly/ConsultAnomalyIntegrationTest`。**L1**
  - [ ] 云端 headless 只跑 **L0 绿灯**；L1（docker pg+redis 并发）留本地，Completion Notes 标注。

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

### V48 迁移完整 SQL（照 `V44__init_consult_anomalies.sql` 风格）
```sql
-- Story 1.2（V1.1 资金地基）：双分录总账 + PawCoin 钱包 + 用户流水。实测最大号 V47(1.1) + 1 = V48（决策 E2）。
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
- [Source: _bmad-output/implementation-artifacts/sprint-status-v1.1.yaml#FLYWAY V48]
- 代码范式：`content/repository/ContentPostRepository.java`（`@Modifying @Query int` 原子更新）、`admin/audit/service/AuditChainLock.java`（advisory 锁边界说明）、`shared/ratelimit/IdempotencyService.java`、`shared/error/AppException.java`、`consult/domain/ConsultSession.java`、`db/migration/V44__init_consult_anomalies.sql`、`test/.../support/ApiIntegrationTest.java`

### 待澄清（不阻塞）
- [OPEN] 兽医应付/已付分录的具体记账时点（会话完成计提 VET_PAYABLE、月结打款转 VET_PAID）——本 story 只需把这些 account 枚举建全，实际记账在 3.7/9.5；本 story 不实现。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
