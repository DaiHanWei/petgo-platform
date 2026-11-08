-- v1.4.2 shop Story 3.2：提交订单（新结算模式）所需的订单头 / 订单行新列（架构 D-8、决策 I5 / S-3 / S-4，P-8 ③）。
--
-- ① 订单头金额【沿用已有列，不新增同义列】：goods_subtotal = 商品金额、shipping_fee = 所选快递运费、
--    shipping_discount = 运费减免（仍 ≤ 0，沿用 V20260817_2308 与 ck_shop_orders_amounts 的存量口径）、
--    total_amount = 应付、coin_amount / cash_amount = 两段拆分；部分取消复用 shop_order_lines.refunded_qty。
-- ② checkout_source IS NULL = 本版之前下的单：PawCoin 仍在付款时扣（过渡规则，Story 3.2 AC6.4）；
--    非空的单 PawCoin 在提交时已扣（S-3），未付款取消时退回。
-- ③ checkout_source 的 REORDER 为 4.2 迟到款重新下单所建新单的来源（架构 D-8 列表未列，本 story 补，见 story「待确认」②）。
-- ④ shop_order_lines.discount_eligible 为 4.1 退款分摊所需快照：该行当初是否在实际采用的满减券适用范围内
--    （架构 D-8 未列，见 story「待确认」①）。快照，不随券模板后改。
-- 整数列一律 BIGINT / INTEGER，无 SMALLINT。

ALTER TABLE shop_orders
    ADD COLUMN checkout_source              VARCHAR(16),
    ADD COLUMN discount_coupon_amount       BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN discount_user_coupon_id      BIGINT      REFERENCES user_coupons (id) ON DELETE SET NULL,
    ADD COLUMN shipping_discount_source     VARCHAR(24),
    ADD COLUMN free_shipping_user_coupon_id BIGINT      REFERENCES user_coupons (id) ON DELETE SET NULL,
    ADD COLUMN insurance_fee                BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN insurance_selected           BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN courier_code                 VARCHAR(32),
    ADD COLUMN courier_service_code         VARCHAR(32),
    ADD COLUMN courier_name                 VARCHAR(64),
    ADD COLUMN courier_service_name         VARCHAR(64),
    ADD COLUMN courier_eta_text             VARCHAR(32),
    ADD COLUMN shipping_quote_id            BIGINT      REFERENCES shipping_quotes (id),
    ADD COLUMN coin_selected                BOOLEAN,
    ADD COLUMN cancel_reason                VARCHAR(32),
    ADD COLUMN cancelled_at                 TIMESTAMPTZ,
    ADD COLUMN reorder_of_order_id          BIGINT      REFERENCES shop_orders (id),
    ADD COLUMN late_paid_amount             BIGINT,
    ADD COLUMN late_paid_at                 TIMESTAMPTZ,
    ADD COLUMN late_paid_intent_id          BIGINT      REFERENCES payment_intents (id),
    ADD COLUMN late_payment_resolution      VARCHAR(16);

ALTER TABLE shop_orders
    ADD CONSTRAINT ck_shop_orders_checkout_source
        CHECK (checkout_source IS NULL OR checkout_source IN ('CART','BUY_NOW','LEGACY','REORDER')),
    ADD CONSTRAINT ck_shop_orders_shipping_discount_source
        CHECK (shipping_discount_source IS NULL OR shipping_discount_source IN ('NONE','FREE_SHIPPING_RULE','COUPON')),
    ADD CONSTRAINT ck_shop_orders_cancel_reason
        CHECK (cancel_reason IS NULL OR cancel_reason IN (
            'USER_UNPAID_CANCEL','PAYMENT_TIMEOUT','OUT_OF_STOCK','WAYBILL_FAILED','PICKUP_FAILED',
            'COURIER_FAULT','USER_FAULT_RETURNED','LOST_DAMAGED','LATE_PAYMENT')),
    ADD CONSTRAINT ck_shop_orders_late_payment_resolution
        CHECK (late_payment_resolution IS NULL OR late_payment_resolution IN ('REORDERED','REFUNDED')),
    ADD CONSTRAINT ck_shop_orders_checkout_amounts
        CHECK (discount_coupon_amount >= 0 AND insurance_fee >= 0
               AND (late_paid_amount IS NULL OR late_paid_amount > 0)),
    ADD CONSTRAINT ck_shop_orders_late_paid_shape
        CHECK ((late_paid_amount IS NULL AND late_paid_at IS NULL AND late_paid_intent_id IS NULL)
            OR (late_paid_amount IS NOT NULL AND late_paid_at IS NOT NULL AND late_paid_intent_id IS NOT NULL));

-- 应付公式库级钉死（存量行均满足：建单时 total = goods + fee + discount，列 updatable=false）。
-- NOT VALID：不扫存量、只约束此后的写入，避免历史脏行让生产迁移失败。
ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_total_formula
    CHECK (total_amount = goods_subtotal - discount_coupon_amount + shipping_fee + shipping_discount + insurance_fee)
    NOT VALID;

CREATE UNIQUE INDEX uq_shop_orders_reorder_of ON shop_orders (reorder_of_order_id) WHERE reorder_of_order_id IS NOT NULL;
CREATE INDEX ix_shop_orders_late_paid ON shop_orders (late_paid_at) WHERE late_paid_at IS NOT NULL;
-- 报价清理任务按「未被订单引用」判断（ShippingQuoteRecordRepository.deleteCreatedBefore 的 NOT EXISTS）。
CREATE INDEX ix_shop_orders_shipping_quote ON shop_orders (shipping_quote_id) WHERE shipping_quote_id IS NOT NULL;

-- 部分取消的满减分摊需要知道「该行当初是否在满减券适用范围内」（4.1 ShopRefundCalculator）。快照，不随券模板后改。
ALTER TABLE shop_order_lines ADD COLUMN discount_eligible BOOLEAN NOT NULL DEFAULT false;
