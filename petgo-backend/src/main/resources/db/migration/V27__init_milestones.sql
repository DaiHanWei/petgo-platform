-- Story 8.1（FR-42 / 决策 F16）：宠物第一次里程碑系统数据地基。新表归 profile 域。
-- 序号 V26→V27 单调追加（决策 E2）；schema 只走 Flyway，ddl-auto=validate。
--
-- pet_milestones      : 每只宠物按 pet_type 自动分配的里程碑清单（roster），建档时物化自后端固定
--                       常量 MilestoneCatalog。不含完成数据；完成与否由 milestone_completions 是否存在
--                       对应行决定（不预插完成行，AC 8.1）。对外标识用 code（C-S1 等，非顺序 id）。
-- milestone_completions: 仅记已完成项。pet_milestone_id 唯一 → 自动完成幂等、不可撤销；
--                       linked_content_id partial-unique → 一条成长日历内容至多关联一个里程碑（FR-42）。

CREATE TABLE pet_milestones (
    id              BIGSERIAL    PRIMARY KEY,
    pet_profile_id  BIGINT       NOT NULL REFERENCES pet_profiles (id),
    code            VARCHAR(16)  NOT NULL,            -- 目录码（C-S1 / D-M3 / G-L1…），稳定外露标识
    level           VARCHAR(1)   NOT NULL,            -- S / M / L
    trigger_type    VARCHAR(24)  NOT NULL,            -- SYSTEM_AUTO / USER_CHECKIN / PUSH_PUBLISH
    sort_order      INT          NOT NULL,            -- 同级展示次序
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- UTC
    CONSTRAINT uq_pet_milestones_pet_code UNIQUE (pet_profile_id, code),
    CONSTRAINT ck_pet_milestones_level CHECK (level IN ('S', 'M', 'L')),
    CONSTRAINT ck_pet_milestones_trigger CHECK (trigger_type IN ('SYSTEM_AUTO', 'USER_CHECKIN', 'PUSH_PUBLISH'))
);

CREATE INDEX idx_pet_milestones_profile ON pet_milestones (pet_profile_id);

CREATE TABLE milestone_completions (
    id                 BIGSERIAL    PRIMARY KEY,
    pet_milestone_id   BIGINT       NOT NULL REFERENCES pet_milestones (id),
    source             VARCHAR(24)  NOT NULL,         -- SYSTEM_AUTO / USER_CHECKIN / PUBLISH
    linked_content_id  BIGINT,                        -- 用户打卡关联的成长日历内容（系统自动类为 null）
    completed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- UTC
    CONSTRAINT uq_milestone_completions_milestone UNIQUE (pet_milestone_id),
    CONSTRAINT ck_milestone_completions_source CHECK (source IN ('SYSTEM_AUTO', 'USER_CHECKIN', 'PUBLISH'))
);

-- 一条成长日历内容至多关联一个里程碑（partial unique，仅约束非空 linked_content_id）。
CREATE UNIQUE INDEX uq_milestone_completions_content
    ON milestone_completions (linked_content_id) WHERE linked_content_id IS NOT NULL;
