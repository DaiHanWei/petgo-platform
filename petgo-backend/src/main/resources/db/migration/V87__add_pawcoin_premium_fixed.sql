-- V87（退款流 0718 改版）：退款转 PawCoin「固定溢价」列，配合已有 premium_rate（%）支持「百分比 + 固定值」双参数后台可配。
-- 号 V87 单调追加（v1.1-dev 当前 max V85；V86 为「列注释」迁移并存，out-of-order 已开）。
--
-- 背景：0718 退款流让 QRIS/DANA 订单可选「转 PawCoin」并给 bonus 激励（反套利 C-1：仅未交付+转币分支）。
-- bonus = 退款额 × premium_rate/100（V78 已建）+ premium_fixed（本迁移）。种子 0 → 行为零变化，实际值由运营在后台配。
-- 资金护栏：premium_fixed ≥ 0（表级 CHECK）；写操作记 config_change_logs + 审计哈希链（沿用 9-2 AdminConfigService）。
ALTER TABLE pawcoin_config ADD COLUMN premium_fixed BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pawcoin_config ADD CONSTRAINT ck_pawcoin_premium_fixed CHECK (premium_fixed >= 0);
COMMENT ON COLUMN pawcoin_config.premium_fixed IS '退款转 PawCoin 固定溢价（koin，配合 premium_rate % 一起构成 bonus）';
