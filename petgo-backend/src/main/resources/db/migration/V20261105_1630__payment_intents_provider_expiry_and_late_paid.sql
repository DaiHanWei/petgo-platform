-- v1.4.2 shop Story 3.3：付款窗口、主动查单与关单后到账（架构 D-11、P-8 ⑩；决策 S-1 / B1 / I1 / I2 / I3）。
--
-- ① 不新增意图状态：报表 / 后台支付列表 / 财务导出等 21 处按 PAID 统计（I1）。关单后到账的电商意图照常
--    转 PAID，以 paid_after_close = true 标记；迟到款金额记到原订单 late_paid_*（V20261105_1625 已建列）。
-- ② shop_order_id 是「一单多意图」的关联：出码失败重试 / 网关拒付后重出码都会换意图，而
--    shop_orders.payment_intent_token 只记最新一个。关单判据「该单【所有】意图码已失效」与
--    「旧意图迟到到账找回订单」都依赖它 —— 架构 P-8 ⑩ 未列此列（见 story「待确认」1）。
-- ③ provider_expires_at = GemPay 码失效时刻（出码 + 65 分钟，GemPay 固定不可调）。存量意图为空，
--    判据按 created_at + 65 分钟兜底（COALESCE）。
-- ④ 上线前检查（回填前提：一个意图 token 只挂在一张订单上）—— 以下查询结果须为空：
--    SELECT payment_intent_token, count(*) FROM shop_orders
--     WHERE payment_intent_token IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
-- 整数列一律 BIGINT，无小整型列。

ALTER TABLE payment_intents
    ADD COLUMN provider_expires_at TIMESTAMPTZ,
    ADD COLUMN paid_after_close    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN shop_order_id       BIGINT  REFERENCES shop_orders (id) ON DELETE SET NULL;

ALTER TABLE payment_intents
    ADD CONSTRAINT ck_payment_intents_paid_after_close_shop
        CHECK (NOT paid_after_close OR purpose = 'SHOP_ORDER'),
    ADD CONSTRAINT ck_payment_intents_shop_order_purpose
        CHECK (shop_order_id IS NULL OR purpose = 'SHOP_ORDER');

-- 存量回填：只能回填订单当前挂着的那一个意图（历史被替换的意图无从关联）
UPDATE payment_intents pi SET shop_order_id = o.id
  FROM shop_orders o
 WHERE pi.purpose = 'SHOP_ORDER' AND o.payment_intent_token = pi.public_token;

CREATE INDEX ix_payment_intents_shop_order ON payment_intents (shop_order_id) WHERE shop_order_id IS NOT NULL;
CREATE INDEX ix_payment_intents_shop_reconcile ON payment_intents (updated_at)
    WHERE purpose = 'SHOP_ORDER' AND gateway_ref IS NOT NULL;
