-- pet_status 取值重命名（A/B/C → HAS_PET/PLANNING/ENTHUSIAST）。
-- 起因：弃用无语义的 A/B/C 字面，改用自解释枚举名（落库值=API 契约值=枚举名）。
-- Flyway 单调追加（决策 E2）：不改已应用的 V2，靠本迁移 drop 旧 CHECK + 加宽列 + 迁移存量行 + 加新 CHECK。

-- 1) 先撤旧 CHECK（否则 UPDATE 成新值会被旧约束拒绝）。
ALTER TABLE users DROP CONSTRAINT ck_users_pet_status;

-- 2) 加宽列：原 VARCHAR(8) 放不下 'ENTHUSIAST'(10)。
ALTER TABLE users ALTER COLUMN pet_status TYPE VARCHAR(16);

-- 3) 迁移存量数据 A→HAS_PET / B→PLANNING / C→ENTHUSIAST。
UPDATE users SET pet_status = 'HAS_PET'    WHERE pet_status = 'A';
UPDATE users SET pet_status = 'PLANNING'   WHERE pet_status = 'B';
UPDATE users SET pet_status = 'ENTHUSIAST' WHERE pet_status = 'C';

-- 4) 加新 CHECK（语义同前，取值更新）。
ALTER TABLE users ADD CONSTRAINT ck_users_pet_status
    CHECK (pet_status IS NULL OR pet_status IN ('HAS_PET', 'PLANNING', 'ENTHUSIAST'));
