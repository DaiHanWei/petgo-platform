-- V1.4.0 精选自营电商 · Story 3.5 —— 平台责任补偿溢价配置（AB-6A 扩展，C-9 / D-8）。
-- 工作线：V1.4.0 电商 · 独占号段 V101–V139。
-- ⚠️ 本迁移给共享表 pawcoin_config 加列 —— 但【只加不改、且新列全部有默认值】，
--    既有行为逐字节不变；不放宽任何 CHECK、不动既有列。故风险等级远低于 Story 3.3。
--
-- 🔴🔴 【两条溢价必须是两个独立配置项】（C-9 / D-8）：
--    ① 激励溢价（既有 premium_rate / premium_fixed）—— 「未交付+转币」分支的反套利激励；
--    ② 平台责任补偿溢价（本次新增）—— 平台责任退货时，PawCoin 段不退现金，用溢价安抚（C-9）。
--    ⚠️ 写成同一个数值会【连带毁掉 AB-13A 的售后成本口径与 AB-6C 的浮存归因】，
--       而且是【静默错误】——不报错，只是两个报表的数字一直不对，且没人知道该信哪个。

ALTER TABLE pawcoin_config ADD COLUMN compensation_premium_rate INT NOT NULL DEFAULT 0;
ALTER TABLE pawcoin_config ADD COLUMN compensation_premium_cap BIGINT NOT NULL DEFAULT 0;

ALTER TABLE pawcoin_config ADD CONSTRAINT ck_pawcoin_config_compensation CHECK (
    compensation_premium_rate >= 0 AND compensation_premium_rate <= 100
    AND compensation_premium_cap >= 0);

COMMENT ON COLUMN pawcoin_config.compensation_premium_rate IS
    '🔴 平台责任【补偿】溢价比例（C-9）。与 premium_rate（激励溢价）是【两个独立配置项】，'
    '共用同一数值会静默毁掉 AB-13A 售后成本口径与 AB-6C 浮存归因。';
COMMENT ON COLUMN pawcoin_config.compensation_premium_cap IS
    '补偿溢价的单笔上限（最小币种单位）。0 = 不设上限。';
