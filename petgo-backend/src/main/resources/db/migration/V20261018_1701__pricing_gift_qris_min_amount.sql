-- V1.4.0 gift · Story 1.1 —— 送礼 QRIS 单笔最低金额（FR-148 / AB-35，架构增量 AD-4、§6.1）。时间戳版本号（决策 E7），号已预分配。
--
-- 只在送礼服务发起 QRIS 前校验（total < 本值 → 422 gift-qris-below-min）；不进通用 createIntent，不影响其他付费。
-- 后台改值在 Story 3.1。约束命名照 V20261002_1817 的 ck_pricing_*_min。
ALTER TABLE pricing_config ADD COLUMN gift_qris_min_amount BIGINT NOT NULL DEFAULT 10000;
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_gift_qris_min CHECK (gift_qris_min_amount >= 100);

COMMENT ON COLUMN pricing_config.gift_qris_min_amount IS
    'FR-148 送礼 · QRIS 单笔最低金额（IDR，≥100，默认 10000）：低于此额只能用 PawCoin；只在送礼服务内校验。';
