-- V1.4.2 shop-admin Story 7.2 · AD-11（O-4，G8）：迟到款部分缺货重新下单。
-- 锁不到库存的行照常复制到新单、置 true：转已付款时跳过这些行的库存确认；部分 / 整单取消不回补；
-- 未处置（未全部取消）前所有发货入口拒绝；「联系用户后继续」做 lock + commit 后清标记。
ALTER TABLE shop_order_lines ADD COLUMN stock_unlocked BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN shop_order_lines.stock_unlocked IS
    '迟到款重新下单时锁不到库存的行（未锁 / 未确认库存）。true 时不回补库存、拒绝发货，联系用户后继续成功后清为 false';
