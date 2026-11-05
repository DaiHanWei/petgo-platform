-- V1.4.2 social-topic · Story 1.1 · 迁移 ①（架构 D-1 / P-8 ①）：追更宠物关系 + 宠物粉丝计数。
--
-- 一行 = follower 追更了 pet_profile。复合主键即幂等键；写入一律 INSERT … ON CONFLICT DO NOTHING。
--
-- 级联口径：
--   * 宠物删除是物理删除（ProfileDeletionService.deletePetRows）→ 追更行由 fk_pet_follows_pet 级联清理，
--     宠物行本身也没了，计数无需修正。
--   * 账号注销只匿名化 users 行（不删），fk_pet_follows_follower 的 CASCADE 在注销路径上永不触发 ——
--     追更者一侧必须由 PetFollowService.purgeFollower 显式清理（先扣宠物计数再删行）。
--   * 封号（DEACTIVATED）不清理、不改计数。
--
-- 整数列一律 INTEGER（Hibernate int → int4），禁止更小的整数类型（validate 全红的老坑）。
-- 新功能，存量为 0，不回填。

CREATE TABLE pet_follows (
    follower_id     BIGINT      NOT NULL,
    pet_profile_id  BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_pet_follows PRIMARY KEY (follower_id, pet_profile_id),
    CONSTRAINT fk_pet_follows_follower FOREIGN KEY (follower_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_pet_follows_pet FOREIGN KEY (pet_profile_id) REFERENCES pet_profiles (id) ON DELETE CASCADE
);

-- 宠物侧：某只宠物的追更者（1.2 扇出 / 计数核对）。
CREATE INDEX idx_pet_follows_pet_created ON pet_follows (pet_profile_id, created_at);
-- 追更者侧：我追更的宠物列表（keyset created_at DESC, pet_profile_id DESC）与每日额度计数。
CREATE INDEX idx_pet_follows_follower_created ON pet_follows (follower_id, created_at);

-- 冗余计数：只经 native 条件 UPDATE ±1（GREATEST 兜底），实体上只读映射。
ALTER TABLE pet_profiles ADD COLUMN follower_count INTEGER NOT NULL DEFAULT 0;
