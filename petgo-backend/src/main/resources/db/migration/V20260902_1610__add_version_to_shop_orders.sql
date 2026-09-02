-- 评审修复：shop_orders 补乐观锁 version 列（支付回调 vs 取消竞态）。时间戳版本号（决策 E6）。
--
-- ShopOrderPaymentService#fulfillPaid 的 javadoc 一直声称并发由 @Version 乐观锁裁决，
-- 但实体从未有该字段——网关回调事务与取消/懒过期事务同时读到 PENDING_PAYMENT 并各自提交时，
-- inventory.commit 与 inventory.release 会双双执行（库存账目膨胀），订单状态 last-writer-wins。
-- 补上后，后提交者在同一行上撞版本号整体回滚，两条路径互斥。
--
-- 照 payment_intents 同款（V60）：BIGINT NOT NULL DEFAULT 0，存量行从 0 起算。
ALTER TABLE shop_orders
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
