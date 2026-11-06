-- V1.4.2 shop-admin Story 6.1 · AD-14（G10 / G11 / G19）：发货操作人、索赔结案（6.2 写入）、重发运单标识。
-- 🔴 shipments.insurance_cost 已在 V20261106_1020 建，这里不再建。

ALTER TABLE shop_orders ADD COLUMN shipped_by_admin_id BIGINT REFERENCES admin_accounts (id) ON DELETE SET NULL;
ALTER TABLE shop_orders ADD COLUMN claim_result VARCHAR(16);
ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_claim_result
    CHECK (claim_result IS NULL OR claim_result IN ('PAID', 'PARTIAL', 'REJECTED'));
ALTER TABLE shop_orders ADD COLUMN claim_amount BIGINT;
ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_claim_amount CHECK (claim_amount IS NULL OR claim_amount >= 0);
ALTER TABLE shop_orders ADD COLUMN claim_closed_at TIMESTAMPTZ;
ALTER TABLE shop_orders ADD COLUMN claim_closed_by_admin_id BIGINT REFERENCES admin_accounts (id) ON DELETE SET NULL;

ALTER TABLE shipments ADD COLUMN is_reship BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN shop_orders.shipped_by_admin_id IS '标记已发货 / 手工运单的后台账号（首次转已发货时写入，一单多包不改写）';
COMMENT ON COLUMN shop_orders.claim_result IS '向快递索赔的结案结果 PAID / PARTIAL / REJECTED（6.2）';
COMMENT ON COLUMN shop_orders.claim_amount IS '索赔到账金额（IDR，6.2；进对账）';
COMMENT ON COLUMN shop_orders.claim_closed_at IS '索赔结案时刻';
COMMENT ON COLUMN shop_orders.claim_closed_by_admin_id IS '索赔结案的后台账号';
COMMENT ON COLUMN shipments.is_reship IS '快递原因重发生成的运单（G19）';
