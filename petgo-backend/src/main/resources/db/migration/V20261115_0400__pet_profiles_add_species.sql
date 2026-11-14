-- V1.5.0 pet-species Story 1.1（架构 D-2 / D-3）：pet_profiles 加细分类型、里程碑清单归属、类型修改时间。
--
-- pet_type 列与 ck_pet_profiles_pet_type 不动（大类，三值，旧版 App 契约）。
-- 🔴 以后加物种：新迁移 DROP + ADD ck_pet_profiles_pet_species 重列全集，值取当前树里最后一条重建它的迁移；
--    ck_pet_profiles_species_group 不用改。
-- 存量回填后三列关系：pet_species = pet_type = milestone_track。

ALTER TABLE pet_profiles ADD COLUMN pet_species VARCHAR(32);
ALTER TABLE pet_profiles ADD COLUMN milestone_track VARCHAR(16);
ALTER TABLE pet_profiles ADD COLUMN pet_type_changed_at TIMESTAMPTZ;

UPDATE pet_profiles SET pet_species = pet_type, milestone_track = pet_type;

ALTER TABLE pet_profiles ALTER COLUMN pet_species SET NOT NULL;
ALTER TABLE pet_profiles ALTER COLUMN milestone_track SET NOT NULL;

ALTER TABLE pet_profiles ADD CONSTRAINT ck_pet_profiles_pet_species CHECK (pet_species IN ('CAT','DOG','RABBIT','HAMSTER','BIRD','CHICKEN','FISH','LIZARD','TURTLE','SUGAR_GLIDER','SNAKE','DUCK','FROG','GUINEA_PIG','CIVET','HEDGEHOG','HERMIT_CRAB','OTTER','SHEEP','COW','SPIDER','SQUIRREL','GOAT','CRAB','INSECT','MOUSE','OTHER'));
ALTER TABLE pet_profiles ADD CONSTRAINT ck_pet_profiles_species_group CHECK (
    (pet_species = 'CAT' AND pet_type = 'CAT') OR (pet_species = 'DOG' AND pet_type = 'DOG')
    OR (pet_species NOT IN ('CAT','DOG') AND pet_type = 'OTHER'));
ALTER TABLE pet_profiles ADD CONSTRAINT ck_pet_profiles_milestone_track CHECK (milestone_track IN ('CAT','DOG','OTHER'));

COMMENT ON COLUMN pet_profiles.pet_species IS '细分类型（V1.5.0 FR-205），大类 pet_type 由它推出';
COMMENT ON COLUMN pet_profiles.milestone_track IS '里程碑 / 新手任务清单归属（CAT/DOG/OTHER），迁移不改，用户改类型且大类变时改';
COMMENT ON COLUMN pet_profiles.pet_type_changed_at IS '用户最近一次修改类型的时间（UTC），只记用户修改；冷却 90 天按 WIB 日期';
