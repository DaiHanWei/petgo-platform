-- V1.4.2 shop-admin Story 5.1 · AD-9（G16 / O-3 / O-15 / O-28）：退回原因落库 + 用户原因退回按行入库。
-- return_cause：confirmReturned 写入，本轮异常内不可改；快递原因重发成功后清空（O-28），再次退回可重新判定。
-- return_inbound_*：用户原因退回先按行确认入库（库存只在这里加一次），全部收齐写 return_inbound_at，之后才能整单取消出退款单。

ALTER TABLE shop_orders ADD COLUMN return_cause VARCHAR(16);
ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_return_cause
    CHECK (return_cause IS NULL OR return_cause IN ('COURIER_FAULT', 'USER_FAULT'));
ALTER TABLE shop_orders ADD COLUMN return_inbound_at TIMESTAMPTZ;
ALTER TABLE shop_orders ADD COLUMN return_inbound_by BIGINT REFERENCES admin_accounts (id) ON DELETE SET NULL;

ALTER TABLE shop_order_lines ADD COLUMN return_inbound_qty INT NOT NULL DEFAULT 0;
ALTER TABLE shop_order_lines ADD CONSTRAINT ck_shop_order_lines_return_inbound_qty
    CHECK (return_inbound_qty >= 0 AND return_inbound_qty <= qty);

COMMENT ON COLUMN shop_orders.return_cause IS '包裹退回原因 COURIER_FAULT / USER_FAULT（V1.4.2 shop-admin 5.1）；本轮异常内不可改，快递原因重发成功后清空';
COMMENT ON COLUMN shop_orders.return_inbound_at IS '用户原因退回的商品全部按行确认入库的时刻；非空才允许 USER_FAULT_RETURNED 整单取消';
COMMENT ON COLUMN shop_orders.return_inbound_by IS '最后一次确认入库（收齐）的后台账号；账号删除置空';
COMMENT ON COLUMN shop_order_lines.return_inbound_qty IS '用户原因退回已确认入库件数（累计，确认时 ≤ qty − refunded_qty；取消后 refunded_qty 补齐，本列保留）；库存只在确认时回补一次';
