---
baseline_commit: c041e41cc20b4c5355d850044bc7a81cfe24abbc
---
# Story 1.6: 注销时 PawCoin 余额作废

Status: in-progress

> V1.1 Epic 1（资金地基）**收官** story，接已 done 的 1.1-1.5。**后端为主**（作废服务 + 接入既有账号注销级联 + 一处 Flyway 扩科目）+ **前端一条二次告知**（复用 7.3 已有整页注销 `delete_account_page.dart`）。**brownfield**：给既有 `AccountDeletionService` 编排**加一步**、给总账**加一个科目**，不重写注销流程。
> 源：`epics-v1.1.md` Story 1.6（FR-50D「不提现/不转赠/注销余额作废」，延续 FR-20 级联）· 架构 `architecture-v1.1-delta.md` §3.1（双分录总账）· 决策 D1/D2（注销数据生命周期）+ **本 story 拍板：作废走独立 `FORFEITURE` 科目**（2026-07-12，不混入 `PLATFORM_REVENUE`，保 9-4 营收统计可拆）。
> **范围边界**：注销时把用户 PawCoin 余额**写一条终结双分录归零 + 物理删钱包/流水**，并在注销确认页**二次告知余额作废**。**不做**：主动退款/转赠（FR-50D 明令禁止）、余额到期作废（无 TTL 概念，仅注销触发）、后台手动作废（非本 story）。

## Story

As a 用户,
I want 注销账号时被明确告知我的 PawCoin 余额会作废，且账号删除后余额随之清零、总账留痕,
so that 符合「不提现 / 不转赠 / 注销余额作废」的合规约定（FR-50D），我删号前心里有数、财务侧也对得平账（FR-20 级联）。

## Acceptance Criteria

1. **后端：总账新增作废科目（L0/L1）**
   **Given** 既有 `ledger_entries.account` 六科目（`CASH_IN`/`FLOAT_LIABILITY`/`VET_PAYABLE`/`VET_PAID`/`PLATFORM_REVENUE`/`REFUND_OUT`）与 `ck_ledger_entries_account` CHECK（V61）
   **When** 引入注销作废
   **Then** 新增 `FORFEITURE`（作废/沉没）科目：`LedgerAccount` 枚举加 `FORFEITURE`，并**新 Flyway V62 `ALTER` 重建 `ck_ledger_entries_account` CHECK 纳入 `FORFEITURE`**（决策 E2：加东西一律新迁移 ALTER，**不动 V61**）；作废余额独立成科目、**不混入 `PLATFORM_REVENUE`**（9-4 营收统计可拆分）（**L0/L1**）

2. **后端：PawCoin 作废服务（写终结分录 + 归零 + 物理清理）（L0/L1）**
   **Given** 用户 `userId` 注销，其钱包余额 `b`
   **When** 调 `PawCoinAccountDeletionService.voidBalanceAndPurge(userId)`（`@Transactional`）
   **Then** 若 `b > 0`：经 **`LedgerService.post`** 写一组平衡终结分录 `FLOAT_LIABILITY DEBIT b`（带 `userId`，冲平钱包镜像）/ `FORFEITURE CREDIT b`（`ref_type="account_deletion"`，幂等键 `acct-del-forfeit:{userId}`），并 `wallets.applyDelta(userId, -b)` 把余额归零（`WHERE balance + (-b) >= 0` 恰为 0，满足 `balance>=0` CHECK）（**L0/L1**）
   **And** 随后**物理删除** `pawcoin_transactions`（`deleteByUserId`）+ `pawcoin_wallets`（`deleteByUserId`）——个人钱包/流水纳入级联删除路径；**`ledger_entries` 终结分录保留**（append-only 总账铁律，对账/浮存审计留痕，`user_id` 成为纯审计数值、user 行删后无 FK 悬空是有意设计）（**L1**）
   **And** `b == 0`（无钱包 / 空钱包）时**不写分录、不报错**，仅幂等物理清理（可能删 0 行）（**L0/L1**）

3. **后端：接入既有账号注销级联（L1）**
   **Given** `AccountDeletionService.execute(deletionId)` 现有编排（profile/triage/consult/notification → **authDeletion 删 user 行** → OSS/IM 媒体清理）
   **When** 注销作业运行
   **Then** 构造器注入 `PawCoinAccountDeletionService`，在 `execute()` 里**于 `authDeletion.deleteByUserId`（删 user 行）之前**插入一步 `voidBalanceAndPurge(userId)`（照既有「各 owning 模块自暴露 `XxxDeletionService`、account 编排只调接口、禁 account 直 join pay 表」的模式）；作废步骤走各自独立事务，不破坏现有分步状态机（PENDING→PROCESSING→DONE/FAILED + 启动重扫）（**L1**）

4. **后端：级联幂等与对账不变式（L1）**
   **Given** 注销作业可能因 `rescanOnStartup` 残留重跑
   **Then** 作废步骤**幂等收敛**：重跑时钱包已删 → `balanceOf=0` → 跳过分录 → 物理删命中 0 行；若上次已写分录未删行崩溃，重跑时余额已归 0 → 跳分录 → 删行（`LedgerService.post` 的幂等键 + 唯一约束兜底重放）（**L1**）
   **And** 作废后 **`reconcile(userId)` 仍一致**：钱包已删 `balance=0`，总账 `FLOAT_LIABILITY` 净额（CREDIT−DEBIT，含终结 DEBIT）= 0，`0 == 0` 成立（**L1**）

5. **前端：注销前二次告知余额作废（L0）**
   **Given** 既有注销整页 `delete_account_page.dart`（7.3，输 `DELETE` 激活 → `DELETE /api/v1/me`）的「将被永久删除」清单（现 5 项：宠物/成长档案/帖子/问诊/Google）
   **When** 用户进入注销页
   **Then** 清单**新增一条 PawCoin 作废告知**（`deletionItemPawcoin`）：读当前余额（复用 1.4 `pawCoinProvider`），有余额时展示带 koin 数的文案「Saldo PawCoin (X koin) akan hangus permanen」，余额 0/加载中也展示不带数字的通用作废告知（**告知不可依赖余额成功加载才出现**，作废是既定事实）；双语 id+en + `flutter gen-l10n`，源码零硬编码用户可见串（**L0**）

6. **端到端（L2）**
   **Given** Android 模拟器 + 本地后端 + 一个有正余额的测试账号（先充值/或直接 seed 余额）
   **When** 进入注销页 → 清单显示「PawCoin X koin 作废」→ 输 `DELETE` 确认 → 注销
   **Then** DB 验：`pawcoin_wallets`/`pawcoin_transactions` 该用户行已删；`ledger_entries` 有 `FORFEITURE` 终结分录（`FLOAT_LIABILITY DEBIT b`/`FORFEITURE CREDIT b`）；`reconcile` 一致；App 回游客态首页（**L2**，留本地）

## Tasks / Subtasks

> 后端为主：先扩科目（迁移+枚举）→ 作废服务 → 接入注销编排 → 前端一条告知 → 联调。**唯一新迁移 = V62（扩 CHECK）。**

- [x] **T1 后端：新增 FORFEITURE 科目**（AC1）
  - [x] `com.tailtopia.pay.domain.LedgerAccount` 枚举加 `FORFEITURE`。**L0**
  - [x] Flyway **`V62__extend_ledger_forfeiture.sql`**：`ALTER TABLE ledger_entries DROP CONSTRAINT ck_ledger_entries_account;` + `ADD CONSTRAINT ck_ledger_entries_account CHECK (account IN ('CASH_IN','FLOAT_LIABILITY','VET_PAYABLE','VET_PAID','PLATFORM_REVENUE','REFUND_OUT','FORFEITURE'));`（**不动 V61**；注释标 story 1.6 + 决策 E2 让号来由）。**L1**
- [x] **T2 后端：PawCoin 作废服务 + repo 删除方法**（AC2/AC4）
  - [x] `PawCoinWalletRepository` 加 `@Modifying @Query("delete from PawCoinWallet w where w.userId = :userId") int deleteByUserId(userId)`；`PawCoinTransactionRepository` 加 `deleteByUserId(userId)`（照既有 `@Modifying` 范式）。**L0**
  - [x] 新建 `com.tailtopia.pay.service.PawCoinAccountDeletionService.voidBalanceAndPurge(long userId)`（`@Transactional`）：读余额 `b`（`wallets.findByUserId` / `balanceOf`）→ 若 `b>0` `ledgerService.post(group, [debit(FLOAT_LIABILITY,b,userId,"account_deletion",null), credit(FORFEITURE,b,null,"account_deletion",null)], "acct-del-forfeit:"+userId)` + `wallets.applyDelta(userId,-b)` → `txns.deleteByUserId(userId)` + `wallets.deleteByUserId(userId)`。**不复用 `PawCoinWalletService.debit`**（其贷方硬编码 `PLATFORM_REVENUE` 且会写一条随即被删的 SPEND 流水）。返回 `void`（无个人媒体，不参与 `PersonalMedia.merge`）。**L0/L1**
- [x] **T3 后端：接入注销编排**（AC3）
  - [x] `AccountDeletionService` 构造器注入 `PawCoinAccountDeletionService`；在 `execute()` 里 `authDeletion.deleteByUserId(userId)` **之前**加 `pawCoinDeletion.voidBalanceAndPurge(userId);`（紧邻 notification 删除一带，删 user 行前）。**不改**状态机/事务边界/rescan。**L1**
- [x] **T4 前端：注销页二次告知**（AC5）
  - [x] `delete_account_page.dart` 清单加一条 `_item(...)`：`ref.watch(pawCoinProvider)` 取余额，`when(data: 有余额→"Saldo PawCoin (X koin) akan hangus permanen" / 余额0→通用作废串, loading/error→通用作废串)`；始终渲染（不因加载失败而缺项）。**L0**
  - [x] `app_en.arb` + `app_id.arb` 加 `deletionItemPawcoin`（带占位 `{coins}` 版）+ `deletionItemPawcoinGeneric`（无数字版）→ `flutter gen-l10n`；套 theme token（危险区红系已有 `_danger`）。**L0**
- [x] **T5 测试**（AC1-5）
  - [x] 后端 L0：`PawCoinAccountDeletionServiceTest`（mock repo/ledgerService）：`b>0`→post 一组平衡分录 + applyDelta(-b) + 两 delete 各一次；`b==0`→不 post、仅 delete；`FORFEITURE` 科目正确。**L0**
  - [x] 后端 L1：`AccountDeletionForfeitIntegrationTest`（净库 petgo_l1）：seed 用户 + 余额 → 触发注销作业 → 断言钱包/流水行删净、`ledger_entries` 有 FORFEITURE 终结分录、`reconcile` 一致；**零余额**用户注销不炸；**幂等**（手动二次调 `voidBalanceAndPurge` 或重跑 execute 不重复扣/不报错）。**L1**（净库见记忆库清库法）
  - [x] 前端 L0：`test/me/delete_account_page_test.dart`（Fake pawCoinProvider override）：有余额→清单含「X koin 作废」；余额加载失败/为 0→仍含通用作废项（**告知不缺席**）。**L0**
  - [x] `flutter analyze` 0 警告 + `flutter test` 绿；后端 `mvn -B package`。云端只跑 L0；L1 净库、L2 模拟器留本地。

## Dev Notes

> 收官 story。绝大部分是「往既有账号注销编排里加一步」+「往总账加一个科目」。**不要重写注销流程，不要绕过 LedgerService 直接 UPDATE 余额。**

### 承接（已 done / 既有）
- **1.2（V61）**：`ledger_entries`（append-only，`entry_group`/`account`/`direction`/`amount>0 CHECK`/`user_id`/`idempotency_key`，`uq_ledger_entries_idem=(idempotency_key,account,direction)`）；`pawcoin_wallets`（`balance>=0 CHECK`，`uq_pawcoin_wallets_user`）；`pawcoin_transactions`（`type` CHECK `TOPUP|SPEND|REFUND|BONUS`）。
- **1.2 记账入口**：`LedgerService.post(entryGroup, List<LedgerLine>, idempotencyKey)`（`@Transactional`，校验 Σ(DEBIT)==Σ(CREDIT) 且每笔 `amount>0`，否则抛不落库；append-only + 幂等去重）；`LedgerLine.debit(account,amount,userId,refType,refId)`/`.credit(...)`；`PawCoinWalletService.debit/credit/balanceOf/reconcile`。
- **`PawCoinWalletRepository`**：`applyDelta(userId,delta)`（原子 `WHERE balance+delta>=0`，返回行数）、`insertIfAbsent`、`findByUserId`。
- **7.3 账号注销（既有）**：`DELETE /api/v1/me`（`AccountController`，`DeleteAccountRequest.confirmed()` 双确认）→ `AccountDeletionService.requestDeletion`（DB 状态机异步 PENDING→PROCESSING→DONE/FAILED + `rescanOnStartup` 续跑，**禁 MQ**）→ `execute()` 顺序调各 `XxxDeletionService.deleteByUserId/anonymizeByUserId`，`authDeletion` **最后删 user 行**，再 OSS/IM 媒体清理。**接线模式 = 各 owning 模块自暴露 deletion 接口、account 只调不 join。**
- **前端注销页（7.3）**：`features/me/presentation/delete_account_page.dart`（输 `DELETE` 短语激活红钮 → `authRepository.deleteAccount(phrase)` → `toGuest()` → `/home`）；「将被永久删除」清单 5 条 `_item(l10n.deletionItem*)`（Pets/Growth/Posts/Consults/Google）。
- **1.4 余额**：`features/pawcoin/` 的 `pawCoinProvider`（AsyncNotifier，读钱包余额+流水）。

### 后端要点（核心）
- **作废的会计口径**：作废 = 用户放弃余额，从「用户负债镜像」`FLOAT_LIABILITY` 转出到独立**作废科目 `FORFEITURE`**（沉没，**非平台营收**）。一组平衡终结分录：`FLOAT_LIABILITY DEBIT b`（带 `user_id`，冲平钱包镜像使 `reconcile` 归零）/ `FORFEITURE CREDIT b`。**这是 AC1「写终结分录」的会计意义**——不写就会让总账 `FLOAT_LIABILITY` 净额 ≠ 实际钱包和（删钱包后对账炸）。
- **为什么不复用 `PawCoinWalletService.debit`**：`debit` 贷方硬编码 `PLATFORM_REVENUE`（会污染营收）+ 会写一条 `SPEND` `pawcoin_transactions` 流水（随即被物理删，无意义）+ 无 `FORFEIT` 流水类型。故走独立 service 直接调 `LedgerService.post` 更干净，且**不需**动 `pawcoin_transactions` 的 `type` CHECK / `PawCoinTxnType` 枚举（少一处迁移风险）。
- **归零与 CHECK**：写正数 `DEBIT` 分录（总账要求 `amount>0`）+ `applyDelta(userId,-b)`；`WHERE balance+(-b)>=0` 在 `delta=-b` 时恰等 0，通过；归零后 `balance=0` 满足 `balance>=0 CHECK`。**并发注意**：同一 `@Transactional` 内先读 `b` 再 `applyDelta(-b)`；注销进行中该用户一般已无其它写路径（登录态即将失效），但仍以事务隔离兜底；若 `applyDelta` 返回 0（并发变动）按冲突处理（作业 FAILED 后 rescan 重跑，见幂等）。
- **物理删 vs 保留**：`pawcoin_wallets`/`pawcoin_transactions` = 个人钱包/流水 → **物理删**（AC2「纳入级联删除路径」）。`ledger_entries` = append-only 总账 → **保留**（对账/浮存审计基准，`user_id` 无 FK、user 行删后悬空为有意；符合 D1「保留对账数据」精神；**绝不 UPDATE 旧分录去匿名化 user_id**——违反 append-only 铁律）。
- **接入顺序**：`execute()` 里放 `authDeletion`（删 user 行）**之前**。作废需要 `userId` 存在语义清晰、且钱包/流水删除独立于 user 行。放在 notification 删除附近即可。
- **幂等（AC4）**：注销作业靠 `rescanOnStartup` 续跑残留，`voidBalanceAndPurge` 必须可重入 —— 钱包删后 `balanceOf=0` 天然跳过分录；`LedgerService.post` 幂等键 `acct-del-forfeit:{userId}` + `uq_ledger_entries_idem` 兜底重放。**不要**用 `Math.random`/时间戳做幂等键（要稳定可重放）。
- **不动**：`AccountController` 签名、状态机、`SecurityConfig`、V60/V61、`PawCoinWalletService` 既有方法语义。

### 前端要点（一条告知）
- **只加一条清单项**，不改注销主流程/短语/跳转。在 `delete_account_page.dart` 的清单里 `ref.watch(pawCoinProvider)` 取余额：`data` 有余额→带 `{coins}` 文案；`data` 余额 0 / `loading` / `error`→通用作废文案。**关键：作废告知不可因余额加载失败而消失**（作废是既定事实，非依赖数据可用性）——用 `.maybeWhen`/兜底串保证始终有一条。
- **文案**（EXPERIENCE 语气，克制不吓人但明确不可恢复）：id 例「Saldo PawCoin ({coins} koin) akan hangus permanen dan tidak bisa dikembalikan」/ 通用「Saldo PawCoin kamu akan hangus permanen」；en 对应。放在现有清单同一红浅底盒内，样式复用 `_item()`。
- **不新增路由/接口**：DELETE /me 已承载级联，前端无需新调用；余额读取复用 1.4 provider。

### 数据生命周期决策关联
- **FR-50D**：不提现/不转赠/注销余额作废 —— 本 story 落「注销作废」这一面（提现/转赠本就无入口，无需额外拦截）。
- **D1/D2 邻接**：consult 匿名化保留 / triage 物理删 / IM 媒体清理已在 7.3；本 story 补 **PawCoin 个人钱包流水物理删 + 总账终结分录保留**，与 D1「个人数据删、对账数据留」同一原则。
- **[记录] 本 story 拍板**：作废科目 = 新增 `FORFEITURE`（非复用 `PLATFORM_REVENUE`），2026-07-12 用户确认。建议完成后回填 `CROSS-STORY-DECISIONS.md`（新增一行：注销作废科目 FORFEITURE，影响 9-4 营收统计口径）。

### 测试范式
- 后端 L0：mock `PawCoinWalletRepository`/`PawCoinTransactionRepository`/`LedgerService`，验分录借贷科目/金额、applyDelta 参数、两 delete 调用、零余额短路。
- 后端 L1：`extends ApiIntegrationTest` 净库 petgo_l1；seed 钱包余额（可直调 `PawCoinWalletService.credit` 或插库）→ 走 `AccountDeletionService`（`requestDeletion` + 等作业 / 直调 `execute`）→ 断言表行删净 + FORFEITURE 分录 + `reconcile`；零余额 + 幂等重跑两个边界。
- 前端 L0：`test/me/delete_account_page_test.dart`，`pawCoinProvider` override 三态（有余额/0/error），断言清单始终含作废项。

### Flyway 号
- **V62**（接 V61，当前全局 max+1）。原 sprint-status 规划 V62 给 2-1（`init_user_monthly_free_quota`）——**1-6 先落地占 V62，2-1 及其后规划号顺延 V63+**（决策 E2「实际 merge 时号继续单调顺延，规划号非最终」）。完成后更新 `sprint-status-v1.1.yaml` Flyway 段。
- ⚠️ 移动靶：merge 时按当时全局 max 再顺延；长度=1 列禁 VARCHAR(1)（CHAR(1) 坑）——本迁移无此风险（纯 ALTER CHECK）。

### Project Structure Notes
- 后端新增：`pay/service/PawCoinAccountDeletionService.java` + `db/migration/V62__extend_ledger_forfeiture.sql`；修改：`pay/domain/LedgerAccount.java`（+枚举）、`pay/repository/{PawCoinWalletRepository,PawCoinTransactionRepository}.java`（+`deleteByUserId`）、`account/service/AccountDeletionService.java`（+注入+一步调用）。
- 前端修改：`features/me/presentation/delete_account_page.dart`（+一条清单项）、`l10n/app_{en,id}.arb`（+2 键）。

### References
- [Source: epics-v1.1.md#Story 1.6]（FR-50D，延续 FR-20 级联）
- [Source: architecture-v1.1-delta.md#§3.1 双分录总账]
- [Source: CROSS-STORY-DECISIONS.md#D1/D2/F14/F18]（注销数据生命周期）
- [Source: 1-2-双分录总账与pawcoin钱包.md]（已 done，总账/钱包/记账入口）
- 代码范式：后端 `pay/service/{LedgerService,PawCoinWalletService}.java`、`pay/domain/LedgerAccount.java`、`pay/repository/PawCoinWalletRepository.java`（`@Modifying` 范式）、`account/service/AccountDeletionService.java`（编排/接线）、`account/web/AccountController.java`、`db/migration/V61__init_ledger_and_pawcoin.sql`；前端 `features/me/presentation/delete_account_page.dart`、`features/pawcoin/`（1.4 provider）。

### 待澄清（不阻塞）
- [OPEN] `FORFEITURE` 是否需进 9-4 营收看板作为独立「作废沉没」指标——本 story 只建科目，看板 Story 9.x 定；已保证科目可拆（不混 PLATFORM_REVENUE）。
- [OPEN] 前端余额数字展示：注销页读 `pawCoinProvider` 若正加载，是否短暂闪「通用串→带数字串」——可接受（告知内容不变，仅补充数字）；实现者按 provider 状态平滑处理。

## Dev Agent Record

### Agent Model Used

Claude Opus 4.8 (1M context)

### Debug Log References

- 后端 L0 unit：`PawCoinAccountDeletionServiceTest`(2) + `AccountDeletionServiceTest`(3) = 5 passed，BUILD SUCCESS。
- 前端 L0 widget：`delete_account_page_test.dart` 3 passed（含「余额>0 显示作废金额」「余额=0 显示通用作废告知」「余额加载失败告知仍不消失」）。
- `flutter analyze`（1-6 触达文件）：No issues found。

### Completion Notes List

- **AC1** `LedgerAccount` 加 `FORFEITURE` 科目；`V62__extend_ledger_forfeiture.sql` 用 DROP+ADD 重建 `ck_ledger_entries_account` CHECK 纳入 FORFEITURE，不动 V61；作废独立成科目、不混入 `PLATFORM_REVENUE`（9-4 营收可拆）。✅ L0
- **AC2** `PawCoinAccountDeletionService.voidBalanceAndPurge(userId)`：余额>0 → `LedgerService.post` 终结平衡分录（`FLOAT_LIABILITY DEBIT` + `FORFEITURE CREDIT`，幂等键 `acct-del-forfeit:{userId}`）→ `applyDelta(-b)` 归零 → 物理删 txn/wallet；余额=0 幂等清理不写分录不报错。✅ L0
- **AC3** `AccountDeletionService` 构造器注入，在 `authDeletion.deleteByUserId`（删 user 行）之前插入 `voidBalanceAndPurge(userId)`，走 owning 模块自暴露 DeletionService 模式，不破坏既有分步状态机。✅ L0
- **AC5** `delete_account_page.dart` 注销确认区加 PawCoin 余额作废二次告知 + en/id i18n；告知永不消失（余额加载失败也保留）。✅ L0
- **AC4（对账不变式）+ 级联幂等**：已落 `AccountDeletionForfeitIntegrationTest`（L1，需 Docker postgres），**待本地/CI 跑**；L2 真机注销页视觉**待本地**。

### File List

**后端**
- `pay/domain/LedgerAccount.java`（+FORFEITURE）
- `pay/service/PawCoinAccountDeletionService.java`（新增）
- `pay/repository/PawCoinWalletRepository.java`（+deleteByUserId / balanceOf）
- `pay/repository/PawCoinTransactionRepository.java`（+deleteByUserId）
- `account/service/AccountDeletionService.java`（接入作废步骤）
- `resources/db/migration/V62__extend_ledger_forfeiture.sql`（新增）
- `test/.../pay/service/PawCoinAccountDeletionServiceTest.java`（新增, L0）
- `test/.../account/service/AccountDeletionServiceTest.java`（+作废编排断言）
- `test/.../pay/AccountDeletionForfeitIntegrationTest.java`（新增, L1）

**前端**
- `features/me/presentation/delete_account_page.dart`（二次告知）
- `l10n/app_en.arb` / `l10n/app_id.arb`（作废文案键）
- `test/me/delete_account_page_test.dart`（新增, L0）

### Change Log

- 2026-07-12：create-story 定稿 Story 1.6（注销 PawCoin 作废：FORFEITURE 终结分录 + 物理清钱包流水 + 接入既有注销级联 + 注销页二次告知）；Status → ready-for-dev。
- 2026-07-13：dev-story 实现完成，后端 5 unit + 前端 3 widget L0 全绿，`flutter analyze` 干净；L1 集成测试已落待本地/CI，L2 真机待本地；Status → review。
