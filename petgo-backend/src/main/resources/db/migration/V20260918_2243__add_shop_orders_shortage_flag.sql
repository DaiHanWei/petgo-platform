-- V1.3.0 异常订单（A8 / Story 4.4 AB-11D）缺货判定改为「运营手工标记」。时间戳版本号（决策 E7）。
--
-- 🔴 为什么不能再按库存数字判：sku_inventory 有不变式 CHECK (locked <= actual)
--    （V20260817_1220，NFR-8「超卖目标为 0」），盘点 / 报损也不允许把 actual 压到 locked 以下。
--    于是原判据「actual < locked」在库里**永远不成立**，异常工作台永远是空的。
--    真实的缺货只在拣货时才被发现（实物比账面少）—— 这个信号系统里没有，只能由运营标出来。
--
-- 三列都可空：未标记 = 三列全空。处置「整单取消」「联系用户后继续」时清空；「部分取消」保留
-- （一单缺多行时运营要逐行处理，中途不该从工作台消失）。

ALTER TABLE shop_orders
    ADD COLUMN shortage_flagged_at TIMESTAMPTZ,
    ADD COLUMN shortage_flagged_by BIGINT REFERENCES admin_accounts (id),
    ADD COLUMN shortage_note       VARCHAR(200);

COMMENT ON COLUMN shop_orders.shortage_flagged_at IS '运营标记缺货的时间（UTC）；NULL = 未标记。异常订单工作台的唯一判据（待发货 ∧ 已标记）';
COMMENT ON COLUMN shop_orders.shortage_flagged_by IS '标记缺货的后台账号 id';
COMMENT ON COLUMN shop_orders.shortage_note IS '标记缺货时填写的原因（仅后台可见，不告知用户）';

-- 工作台按「已标记」取数；绝大多数订单从不被标记 → 部分索引
CREATE INDEX ix_shop_orders_shortage_flagged
    ON shop_orders (created_at, id)
    WHERE shortage_flagged_at IS NOT NULL;
