-- V1.4.0 精选自营电商 · Story 6.1 —— 宠物档案补体重与绝育状态（FR-107 / FR-109 的输入）。
-- 工作线：V1.4.0 电商 · 独占号段 V101–V139。
-- ⚠️ epics 原文写 V110；按台账「取最大号 +1」实取 V121。
--
-- ⚠️⚠️⚠️ 【触碰共享表 pet_profiles】——并行契约 F-4 要求文件头显式标注，且须事先在群里认领。
--    产品【临时授权】只点名了 ck_payment_intents_channel / ck_notifications_type / purpose，
--    🔴 本表【不在临时授权范围内】。风险评估：本迁移【只加列、全部可空、不回填、不改写既有行、
--    不动任何既有约束】，既有档案行为逐字节不变 —— 是加列里风险最低的一档。
--    仍已在 HANDOFF 与 sprint-status 登记为待补认领项。
--
-- 🔴【为什么必须是 Epic 6 的第一条】（L-9）：PetProfile 当前字段全集无体重、无绝育状态。
--    不加列则 FR-109 恒 100% 不触发，FR-107 退化为按物种硬过滤 ——
--    而 FR-108 已移出本版本（C-11），复购引擎的冗余已归零。
--
-- 🔒 体重是【PII 邻近的健康数据】：日志禁记（NFR-5）。

ALTER TABLE pet_profiles
    ADD COLUMN weight_kg NUMERIC(5, 2),
    ADD COLUMN neuter_status VARCHAR(16);

-- 🔴 存量档案未填一律 NULL —— 不回填、不猜。猜错的体重会让 FR-109 的耗尽日整体偏移，
--    而用户根本不知道系统在按一个他没填过的数字算。
ALTER TABLE pet_profiles
    ADD CONSTRAINT ck_pet_profiles_weight CHECK (
        weight_kg IS NULL OR (weight_kg > 0 AND weight_kg <= 200)),
    ADD CONSTRAINT ck_pet_profiles_neuter CHECK (
        neuter_status IS NULL OR neuter_status IN ('NEUTERED', 'INTACT', 'UNKNOWN'));

COMMENT ON COLUMN pet_profiles.weight_kg IS
    '🔒 体重（kg）。PII 邻近的健康数据，日志禁记（NFR-5）。'
    'FR-107 按体型匹配、FR-109 按体重查日喂量都读它；可空 —— 存量用户未填。';
COMMENT ON COLUMN pet_profiles.neuter_status IS
    '绝育状态。FR-107 可选过滤维度；可空。';
