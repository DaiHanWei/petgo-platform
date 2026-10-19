-- V1.4.0 multi-pet Story 3.1（AD-10 · §6.4）：内容 ↔ 宠物 多对多关联表 content_pets。
--
-- 归属：② 多宠物线。一条 Diary（GROWTH_MOMENT）可关联多只宠物（≤5）。
-- 兼容：content_posts.pet_id 保留为「第一只关联宠物」兼容字段（关联宠物按 pet_profiles.created_at, id 升序的第一只；
--      删宠物时顺延为剩余关联宠物中最早的一只，没有则置空）。新代码按宠物查询一律读本表，不再读 content_posts.pet_id。
-- 两条外键都 ON DELETE CASCADE：删宠物 / 删帖 / 测试 deleteAll 不被本表挡住。
-- 不删、不改 idx_content_posts_pet_id / idx_content_posts_pet_recommend / idx_content_posts_pet_cover（别处按 pet_id 精确查还在用）。
-- 不碰 ck_notifications_type / ck_payment_intents_purpose。

CREATE TABLE content_pets (
    post_id        BIGINT NOT NULL REFERENCES content_posts (id) ON DELETE CASCADE,
    pet_profile_id BIGINT NOT NULL REFERENCES pet_profiles (id)  ON DELETE CASCADE,
    CONSTRAINT pk_content_pets PRIMARY KEY (post_id, pet_profile_id)
);
CREATE INDEX idx_content_pets_pet_post ON content_pets (pet_profile_id, post_id);

-- 回填存量：现状只有 GROWTH_MOMENT 有 pet_id（V86 列注释）。不过滤 deleted_at / status（软删帖的关联无害，读口自己会过滤）。
INSERT INTO content_pets (post_id, pet_profile_id)
SELECT id, pet_id FROM content_posts WHERE pet_id IS NOT NULL
ON CONFLICT DO NOTHING;

COMMENT ON TABLE content_pets IS '内容关联宠物（V1.4.0 多宠物）：一条成长日历（Diary）可关联多只宠物。按宠物的时间线 / 日历 / 统计 / 推荐池 / 里程碑一律读本表；content_posts.pet_id 仅保留为「第一只关联宠物」兼容字段。删帖或删宠物时本表行级联删除';
COMMENT ON COLUMN content_pets.post_id IS '内容 id（关联 content_posts.id）。仅成长日历（GROWTH_MOMENT）会有关联行，日常 / 科普不关联宠物';
COMMENT ON COLUMN content_pets.pet_profile_id IS '关联的宠物档案 id（关联 pet_profiles.id），须属于内容作者本人';
