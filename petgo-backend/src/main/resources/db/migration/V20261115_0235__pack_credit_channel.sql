-- V1.5.0 gift-pack Story 4-3 · 架构 §3.7（逐字）。开工当天（2026-11-14）grep 全部迁移 + git fetch 核对远端分支：ck_gift_orders_channel / ck_gift_orders_total 只在 V20261018_1700 定义过，ck_id_card_hd_purchases_channel 只在 V75 定义过。
ALTER TABLE gift_orders DROP CONSTRAINT ck_gift_orders_channel;
ALTER TABLE gift_orders ADD CONSTRAINT ck_gift_orders_channel CHECK (channel IN ('PAWCOIN', 'QRIS', 'PACK_CREDIT'));
ALTER TABLE gift_orders DROP CONSTRAINT ck_gift_orders_total;
ALTER TABLE gift_orders ADD CONSTRAINT ck_gift_orders_total CHECK (
    (channel = 'PACK_CREDIT' AND total = 0 AND unit_price = 1000 AND payment_intent_id IS NULL)
    OR (channel <> 'PACK_CREDIT' AND total = unit_price * quantity));
-- V20260817_2313 的「窄 CHECK 纵深防御」只放宽这一个值：免费次数不是支付渠道混用，仍是单一来源。
ALTER TABLE id_card_hd_purchases DROP CONSTRAINT ck_id_card_hd_purchases_channel;
ALTER TABLE id_card_hd_purchases ADD CONSTRAINT ck_id_card_hd_purchases_channel
    CHECK (pay_channel IN ('QRIS', 'PAWCOIN', 'PACK_CREDIT'));
