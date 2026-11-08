-- V1.4.2 shop-admin Story 5.2 · AD-10（O-8 / O-9 / O-12 / O-17 / O-26）：退款打款页 —— 打款回填、失败原因、线下结案。
-- 🔴 不重复建 paid_out_at / payout_failed_at / payout_fail_note / destination_* / payout_fee / compensation_amount（V20261105_1635）。
-- payout_txn_no 不设唯一：批量转账可共用一个流水号，重复时页面提示、不拦截。

ALTER TABLE shop_order_refunds ADD COLUMN payout_txn_no VARCHAR(64);
ALTER TABLE shop_order_refunds ADD COLUMN payout_proof_key VARCHAR(255);
ALTER TABLE shop_order_refunds ADD COLUMN paid_out_by_admin_id BIGINT REFERENCES admin_accounts (id) ON DELETE SET NULL;
ALTER TABLE shop_order_refunds ADD COLUMN payout_fail_reason VARCHAR(24);
ALTER TABLE shop_order_refunds ADD CONSTRAINT ck_shop_order_refunds_payout_fail_reason
    CHECK (payout_fail_reason IS NULL OR payout_fail_reason IN ('ACCOUNT_INVALID', 'TRANSFER_RETURNED', 'OTHER'));
ALTER TABLE shop_order_refunds ADD COLUMN payout_failed_by_admin_id BIGINT REFERENCES admin_accounts (id) ON DELETE SET NULL;
ALTER TABLE shop_order_refunds ADD COLUMN offline_closed_at TIMESTAMPTZ;
ALTER TABLE shop_order_refunds ADD COLUMN offline_closed_by_admin_id BIGINT REFERENCES admin_accounts (id) ON DELETE SET NULL;
ALTER TABLE shop_order_refunds ADD COLUMN offline_close_note VARCHAR(200);

CREATE INDEX ix_shop_order_refunds_payout_txn_no ON shop_order_refunds (payout_txn_no) WHERE payout_txn_no IS NOT NULL;

COMMENT ON COLUMN shop_order_refunds.payout_txn_no IS '财务回填的转账流水号（不唯一，批量转账可共用）';
COMMENT ON COLUMN shop_order_refunds.payout_proof_key IS '转账截图私有对象 key（查看走短时效签名 URL，限 refund.payout）';
COMMENT ON COLUMN shop_order_refunds.paid_out_by_admin_id IS '标记已打款的后台账号';
COMMENT ON COLUMN shop_order_refunds.payout_fail_reason IS '打款失败原因 ACCOUNT_INVALID / TRANSFER_RETURNED / OTHER';
COMMENT ON COLUMN shop_order_refunds.payout_failed_by_admin_id IS '标记打款失败的后台账号';
COMMENT ON COLUMN shop_order_refunds.offline_closed_at IS '注销用户线下处理结案时刻（O-17；cash_destination 保持为空）';
COMMENT ON COLUMN shop_order_refunds.offline_closed_by_admin_id IS '线下结案的后台账号';
COMMENT ON COLUMN shop_order_refunds.offline_close_note IS '线下结案说明（必填，≤ 200 字，不得含账户）';
