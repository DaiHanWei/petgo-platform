-- Story 1.6（V1.1 Epic 1 收官）：注销 PawCoin 余额作废需要一个独立作废/沉没科目。
-- 接 1.2 的 V61（六科目 + ck_ledger_entries_account）。新增 FORFEITURE，不混入 PLATFORM_REVENUE
-- （用户放弃的余额是合规义务性作废，非平台营收；9-4 营收统计可拆——本 story 用户拍板 2026-07-12）。
-- 决策 E2：加东西一律新迁移 ALTER，不动 V61 旧迁移。纯 CHECK 重建，无数据迁移、无长度=1 列风险（CHAR(1) 坑）。
-- 号：原规划 V62 给 2-1，因 1-6 需迁移先占 V62，2-1+ 规划号顺延（sprint-status Flyway 段已注）。
ALTER TABLE ledger_entries DROP CONSTRAINT ck_ledger_entries_account;
ALTER TABLE ledger_entries ADD CONSTRAINT ck_ledger_entries_account
    CHECK (account IN ('CASH_IN','FLOAT_LIABILITY','VET_PAYABLE','VET_PAID','PLATFORM_REVENUE','REFUND_OUT','FORFEITURE'));
