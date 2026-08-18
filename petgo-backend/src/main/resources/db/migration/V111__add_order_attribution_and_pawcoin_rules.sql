-- V1.4.0 精选自营电商 · Story 3.4 —— 订单行归因来源 + PawCoin 电商消费规则。
-- 工作线：V1.4.0 电商 · 独占号段 V101–V139。本迁移【只动本工作线自建的表 + 建新表】→ 无需认领。
--
-- 一、订单行归因（🔴 IR 检查前移：原在 Story 9.2）
--    AB-13B 要算「触发卡转化率 vs 普通商品曝光转化率」。若归因只靠客户端埋点，
--    广告拦截与事件丢失会让分母失真，而这个数字是裁决 A-16（复购引擎值不值得做）的唯一依据。
--    🔴 必须在 Epic 3 落地：放到 Epic 9 会造成 Epic 6 的 Story 6.6 依赖 Epic 9，
--    构成【Epic N 依赖 Epic N+3】的结构性倒置。
ALTER TABLE shop_order_lines ADD COLUMN entry_source VARCHAR(32);
ALTER TABLE shop_order_lines ADD COLUMN trigger_type VARCHAR(32);

COMMENT ON COLUMN shop_order_lines.entry_source IS
    '下单入口来源（Toko 全部精选 / 档案推荐区 / 补货提醒卡 / 商品详情直达…）。'
    '🔴 服务端权威归因，不受客户端事件丢失与广告拦截影响（AB-13B 的分母）。';
COMMENT ON COLUMN shop_order_lines.trigger_type IS
    '若来自复购触发卡，记其触发类型（FR-109 粮量见底等）。非触发来源为 NULL。';

-- 二、PawCoin 电商消费规则（FR-100A 规则 2/3/4 的配置依据；后台页属 Story 3.5 / AB-6D）
--    🔴 单例行，范式同 pricing_config / pawcoin_config。
CREATE TABLE shop_pawcoin_rules (
    id                       SMALLINT    PRIMARY KEY,
    -- 电商 PawCoin 总开关
    enabled                  BOOLEAN     NOT NULL DEFAULT TRUE,
    -- 规则 3：运费是否计入可抵扣范围。默认 TRUE —— FR-100A 规则 3 原文即「运费计入总额一并参与抵扣」
    allow_shipping_deduction BOOLEAN     NOT NULL DEFAULT TRUE,
    -- 规则 4：PawCoin 段单笔支付上限（最小币种单位）。默认 Rp 1.000.000
    -- 🔴 用途是【故障/欺诈的爆炸半径 + DEP-7 监管姿态】，不是控浮存 —— 定低反而有害（L-7 自纠）：
    --    定低只会把大额订单挤到纯现金，既不减少浮存也损失了 Coin 的消耗出口。
    max_coin_per_order       BIGINT      NOT NULL DEFAULT 1000000,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_shop_pawcoin_rules_singleton CHECK (id = 1),
    CONSTRAINT ck_shop_pawcoin_rules_cap CHECK (max_coin_per_order >= 0)
);

INSERT INTO shop_pawcoin_rules (id) VALUES (1);

COMMENT ON TABLE shop_pawcoin_rules IS
    'PawCoin 电商消费规则（FR-100A 规则 2/3/4）。建表与读路径属 Story 3.4，后台配置页属 Story 3.5 / AB-6D。';
COMMENT ON COLUMN shop_pawcoin_rules.max_coin_per_order IS
    '🔴 单笔上限的用途是爆炸半径与监管姿态，不是控浮存——定低反而把大额单挤到纯现金（L-7）。';

-- 三、订单上固化支付拆分（Story 3.4）
--    🔴 建单时写一次，【不随后续部分退款重算】——部分退款会改变实付比例，
--       若重算，退到一半时「已退多少 Coin」就没有稳定的分母了（AD-2 整数累计法的前提）。
ALTER TABLE shop_orders ADD COLUMN pay_channel VARCHAR(16);
ALTER TABLE shop_orders ADD COLUMN coin_amount BIGINT;
ALTER TABLE shop_orders ADD COLUMN cash_amount BIGINT;

ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_pay_channel
    CHECK (pay_channel IS NULL OR pay_channel IN ('QRIS', 'PAWCOIN', 'MIXED'));
-- 同 payment_intents 的不变式：拆分金额之和必须等于应付总额
ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_split_sum CHECK (
    (coin_amount IS NULL AND cash_amount IS NULL)
    OR (coin_amount >= 0 AND cash_amount >= 0 AND coin_amount + cash_amount = total_amount)
);
