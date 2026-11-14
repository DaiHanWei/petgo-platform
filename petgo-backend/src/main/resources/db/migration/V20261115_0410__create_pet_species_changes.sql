-- V1.5.0 pet-species Story 1.2（架构 D-5）：宠物类型修改记录。
-- 写入只经 profile.service.PetSpeciesChangeLog.append（用户改 / 运营代改）；FR-208 迁移脚本 SQL 直写，字段口径同。
-- from_species / to_species 不加 CHECK（历史记录，枚举以后改名不该让旧行失效）。
-- FK ON DELETE CASCADE：删档 / 注销物理删宠时随之删除。

CREATE TABLE pet_species_changes (
    id                 BIGSERIAL    PRIMARY KEY,
    pet_profile_id     BIGINT       NOT NULL REFERENCES pet_profiles (id) ON DELETE CASCADE,
    from_species       VARCHAR(32)  NOT NULL,
    to_species         VARCHAR(32)  NOT NULL,
    type_changed       BOOLEAN      NOT NULL,   -- pickerType 是否变（=是否占用户冷却）
    group_changed      BOOLEAN      NOT NULL,   -- 大类是否变
    source             VARCHAR(16)  NOT NULL,   -- USER / MIGRATION / ADMIN
    operator_admin_id  BIGINT,                  -- ADMIN 时必填；不建 FK（同审计口径）
    reason             VARCHAR(200),            -- ADMIN 时必填
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_pet_species_changes_source CHECK (source IN ('USER', 'MIGRATION', 'ADMIN'))
);

CREATE INDEX idx_pet_species_changes_pet_created ON pet_species_changes (pet_profile_id, created_at DESC);

-- 迁移脚本可重跑：每只宠物至多一条 MIGRATION 记录。
CREATE UNIQUE INDEX uq_pet_species_changes_migration ON pet_species_changes (pet_profile_id) WHERE source = 'MIGRATION';
