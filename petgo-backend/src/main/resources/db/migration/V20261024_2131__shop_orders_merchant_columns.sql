-- V1.4.1 商家平台对接（tailtopia-contract.md 第 2 节；FR-14 / FR-18～21、AD-6 / AD-7 / AD-18 / AD-19）。
--
-- 商家平台按这些列只读扫描 Toko 订单记返佣：
--   referrer_merchant_id   下单时写入当时的绑定门店（merchant_stores.id，不加外键），只看绑定不看门店状态。
--   paid_at                离开 PENDING_PAYMENT（付款成功）的同一事务写入，之后不改；未付款为 NULL。
--   return_window_ends_at  签收（写 delivered_at）的同一处写入 delivered_at + 退货窗口（ShopOrder.RETURN_WINDOW = 7 天）。
-- 🔴 商家平台按 (paid_at, id) / (refunded_at, id) keyset 前进、只留 2 分钟安全延迟：
--    两个时刻都取「写入时刻」，绝不取支付网关回调里携带的时间，否则会被游标越过而漏记。

ALTER TABLE shop_orders
    ADD COLUMN referrer_merchant_id  BIGINT      NULL,
    ADD COLUMN paid_at               TIMESTAMPTZ NULL,
    ADD COLUMN return_window_ends_at TIMESTAMPTZ NULL;

-- 回填已付款订单的 paid_at：取付款提醒入队时刻（ShopOrderPaidEvent 的落点，与付款同刻），没有则取 updated_at。
-- 「已付款」= 出过 PENDING_PAYMENT：状态在履约/退款段；或已取消但有入队行 / 支付意图 PAID（已付后被运营取消的单）。
-- 仍在 PENDING_PAYMENT 的一律不回填（意图 PAID 而订单没推进是脏数据，不能凭它写出「已付款」）。
UPDATE shop_orders o
SET paid_at = COALESCE(
        (SELECT q.created_at FROM shop_order_notify_queue q WHERE q.shop_order_id = o.id),
        o.updated_at)
WHERE o.paid_at IS NULL
  AND o.status <> 'PENDING_PAYMENT'
  AND (o.status <> 'CANCELLED'
       OR EXISTS (SELECT 1 FROM shop_order_notify_queue q WHERE q.shop_order_id = o.id)
       OR EXISTS (SELECT 1 FROM payment_intents pi
                  WHERE pi.public_token = o.payment_intent_token AND pi.status = 'PAID'));

-- 回填已签收订单的退货期截止。
UPDATE shop_orders
SET return_window_ends_at = delivered_at + INTERVAL '7 days'
WHERE delivered_at IS NOT NULL AND return_window_ends_at IS NULL;

-- 按门店统计。
CREATE INDEX idx_shop_orders_referrer_paid
    ON shop_orders (referrer_merchant_id, paid_at, id)
    WHERE referrer_merchant_id IS NOT NULL;
-- 记账扫描跨门店按 (paid_at, id) keyset 前进（首列为门店的索引给不出这个顺序）。
CREATE INDEX idx_shop_orders_paid_referred
    ON shop_orders (paid_at, id)
    WHERE referrer_merchant_id IS NOT NULL AND paid_at IS NOT NULL;

-- return_requests.refunded_at（既有列）：转 REFUNDED 的同一事务写入，之后不改；REFUNDED 时必须非空。
UPDATE return_requests SET refunded_at = updated_at
WHERE status = 'REFUNDED' AND refunded_at IS NULL;
ALTER TABLE return_requests ADD CONSTRAINT ck_return_requests_refunded_at
    CHECK (status <> 'REFUNDED' OR refunded_at IS NOT NULL);

-- 商家平台退款扫描按 (refunded_at, id) keyset 前进。
CREATE INDEX idx_return_requests_refunded
    ON return_requests (refunded_at, id)
    WHERE status = 'REFUNDED';
-- 结算扫描按订单查进行中退货与已完成退款（既有的按订单索引只有进行中退货的部分唯一索引，查 REFUNDED 用不上）。
CREATE INDEX idx_return_requests_order
    ON return_requests (shop_order_id);
