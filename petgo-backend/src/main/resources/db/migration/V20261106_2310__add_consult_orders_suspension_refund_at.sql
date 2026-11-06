-- V1.4.2 exp-reverse Story 1.1（AD-1 · E-2 / E-3）：标记「兽医封禁挂起 + QRIS」退款的订单。
-- 🔴 不回填：存量为 NULL 即天然不可人工扣回成长值（PRD「存量不处理」）。
ALTER TABLE consult_orders ADD COLUMN suspension_refund_at timestamptz NULL;

COMMENT ON COLUMN consult_orders.suspension_refund_at IS
    '封禁挂起强制结束且走现金退款的发起时刻（UTC）；仅 ConsultSuspensionService 现金分支写入。NULL=非此类或 V1.4.2 前存量';
