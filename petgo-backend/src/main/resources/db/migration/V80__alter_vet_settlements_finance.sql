-- Story 9.5（AB-8D）：兽医分成月结对账——状态对齐架构 3 态 + 打款凭证。号 V80（当前 max V79；决策 E2）。
-- 3-7（V68）建表用 PENDING/SETTLED（2 态）；架构 §3.4 为 PENDING_FINANCE/PAID/ARCHIVED（3 态 + payment_proof + paid_at）。
-- 本迁移对齐架构。⚠️ 顺序：先 DROP 旧 CHECK → UPDATE 值 → ADD 新 CHECK（否则 UPDATE 撞旧约束）。
--
-- vet 端零改：兽医 App 按 SETTLED 判已结算；VetIncomeResponse.ofSettlement 映射 finance 3 态→vet 2 态
-- （PENDING_FINANCE→PENDING、PAID/ARCHIVED→SETTLED），故 DB 存 3 态不影响兽医侧显示。

ALTER TABLE vet_settlements DROP CONSTRAINT ck_vet_settlements_status;

UPDATE vet_settlements SET status = 'PENDING_FINANCE' WHERE status = 'PENDING';
UPDATE vet_settlements SET status = 'PAID'            WHERE status = 'SETTLED';

ALTER TABLE vet_settlements
    ADD CONSTRAINT ck_vet_settlements_status
        CHECK (status IN ('PENDING_FINANCE', 'PAID', 'ARCHIVED'));

ALTER TABLE vet_settlements
    ADD COLUMN payment_proof VARCHAR(512),                 -- 打款凭证（参考号/说明/URL；财务回填）
    ADD COLUMN paid_at       TIMESTAMPTZ,                   -- 确认打款时刻
    ADD COLUMN archived_at   TIMESTAMPTZ,                   -- 归档时刻
    ADD COLUMN settled_by    BIGINT;                        -- 确认打款的 admin_accounts.id
