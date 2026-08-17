-- V1.4.0 精选自营电商 · Story 3.3 —— 混合支付模型扩展（FR-100 / AD-1 / AD-3）。
-- 工作线：V1.4.0 电商（分支 shawn/oneline-ecommerce）· 独占号段 V101–V139。
-- ⚠️ epics 原文写 V106，但按台账重排后取 V110。
--
-- 🔴🔴 **本迁移触碰三人共享表 payment_intents，并放宽共享 CHECK。** 🔴🔴
--    并行契约 A-2 要求事先认领。当前状态：**产品负责人临时授权生效**
--    （HEX-SIGNOFF.md §签字前的临时授权，2026-08-17），Hex 尚未书面确认。
--    ⚠️ 若 Hex 事后提出异议，须照 V97 的写法取并集重建约束，并回归两边的支付类型。
--
-- 🔴 这是 2026-07-30 事故的同一个剧本（原文见 V97__union_notification_types_two_lines.sql）：
--    两条工作线各自 DROP + ADD 同一个 CHECK，合并后一方的取值整类失效——
--    **两边测试全绿、git 无冲突、编译不报错**，只在真跑那条路径时才炸。
--    故此处 DROP + ADD 时【显式列全三个值】并在注释里写明本次新增的是哪一个。
--
-- 🔴 只放宽 payment_intents 这一处（AD-3）。consult_orders / ai_consult_orders /
--    id_card_hd_purchases 三张表的 pay_channel CHECK **刻意保持不放宽**——
--    虚拟商品恒单渠道，窄 CHECK 是纵深防御，不要以「统一口径」为由顺手对齐。
--
-- 🔴 本迁移【不回填、不改写任何既有行】：四个既有 PaymentPurpose 的行三列全 NULL、
--    channel 仍为 QRIS/PAWCOIN，行为逐字节不变。

ALTER TABLE payment_intents ADD COLUMN coin_amount BIGINT;
ALTER TABLE payment_intents ADD COLUMN cash_amount BIGINT;
-- coin_ratio 只作展示与审计冗余，🔴 **不参与任何计算**（AD-2）——
-- 退款拆分一律用 coin_amount/amount 的整数累计法，浮点比例参与计算必然凑不平。
ALTER TABLE payment_intents ADD COLUMN coin_ratio NUMERIC(9,6);

-- 放宽渠道 CHECK：QRIS / PAWCOIN 为既有值，**本次新增的只有 MIXED**。
ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_channel;
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_channel
    CHECK (channel IN ('QRIS', 'PAWCOIN', 'MIXED'));

-- 🔴 AD-1 的核心不变式，由 DB 强制：
--    非 MIXED → 三列全 NULL（防止旧流程被无意写入拆分字段）；
--    MIXED   → 三列非空、非负，且 coin_amount + cash_amount = amount。
--    把它放在 DB 而不是只放在应用层：拆分金额对不上就是账对不平，
--    而账不平在退款时才会暴露，那时已经动过真钱了。
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_mixed_shape CHECK (
    (channel <> 'MIXED'
        AND coin_amount IS NULL AND cash_amount IS NULL AND coin_ratio IS NULL)
    OR
    (channel = 'MIXED'
        AND coin_amount IS NOT NULL AND cash_amount IS NOT NULL AND coin_ratio IS NOT NULL
        AND coin_amount >= 0 AND cash_amount >= 0
        AND coin_amount + cash_amount = amount)
);

COMMENT ON COLUMN payment_intents.coin_amount IS
    'MIXED 时的 PawCoin 段金额（最小币种单位）。非 MIXED 恒 NULL。';
COMMENT ON COLUMN payment_intents.cash_amount IS
    'MIXED 时的现金段金额。coin_amount + cash_amount = amount，由 CHECK 强制。';
COMMENT ON COLUMN payment_intents.coin_ratio IS
    '🔴 只作展示与审计冗余，【不参与计算】（AD-2）——退款拆分用整数累计法。';
