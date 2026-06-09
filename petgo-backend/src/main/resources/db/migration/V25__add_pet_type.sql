-- 宠物档案加「宠物类型」必选结构化字段（FR-11 / 决策 F6）。
-- pet_type ∈ CAT/DOG/OTHER（UPPER_SNAKE），创建后不可改；决定 FR-42 里程碑清单。
-- Flyway 单调追加（决策 E2）：不改已应用的 V3 建表脚本，靠本迁移加列。
-- 加列分三步以兼容存量行（虽本表为 V1 新表、存量预期为空）：先可空加列 → 回填 → 置 NOT NULL + CHECK。

ALTER TABLE pet_profiles ADD COLUMN pet_type VARCHAR(16);

-- 存量行（若有）回填 OTHER，保证 NOT NULL 可加。
UPDATE pet_profiles SET pet_type = 'OTHER' WHERE pet_type IS NULL;

ALTER TABLE pet_profiles ALTER COLUMN pet_type SET NOT NULL;

ALTER TABLE pet_profiles ADD CONSTRAINT ck_pet_profiles_pet_type
    CHECK (pet_type IN ('CAT', 'DOG', 'OTHER'));
