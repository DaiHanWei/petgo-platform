-- V1.3.2 batch-a Story 3.3 · Tailsonality 角色小标佩戴（AD-3 / D-16）。
--
-- 一宠一行：pet_profile_id 即主键。只能指向【已解锁】结果 —— 由服务层保证（切换接口校验 + 自动佩戴只在发放成功分支）；
-- 读取侧（TailsonalityBadgeQuery）再按 unlocked_at IS NOT NULL 兜一层。
-- 两个外键都 ON DELETE CASCADE：删档时 TailsonalityDeletionService 也会先显式删本表（清晰优先），级联只是兜底。
CREATE TABLE tailsonality_badges (
    pet_profile_id BIGINT      NOT NULL,
    result_id      BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_tailsonality_badges PRIMARY KEY (pet_profile_id),
    CONSTRAINT fk_tailsonality_badges_pet FOREIGN KEY (pet_profile_id)
        REFERENCES pet_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_tailsonality_badges_result FOREIGN KEY (result_id)
        REFERENCES tailsonality_results (id) ON DELETE CASCADE
);

CREATE INDEX idx_tailsonality_badges_result ON tailsonality_badges (result_id);

COMMENT ON TABLE tailsonality_badges IS
    'Tailsonality 角色小标佩戴（V1.3.2 Story 3.3）。一宠一行；只能指向已解锁结果，由服务层保证。可卸下（删行）。';
COMMENT ON COLUMN tailsonality_badges.pet_profile_id IS '宠物（主键，一宠最多佩戴一个）';
COMMENT ON COLUMN tailsonality_badges.result_id IS '佩戴的结果；只能指向 unlocked_at 非空的结果，由服务层保证';
COMMENT ON COLUMN tailsonality_badges.created_at IS '首次佩戴时刻（UTC）';
COMMENT ON COLUMN tailsonality_badges.updated_at IS '最近一次切换佩戴时刻（UTC）';
