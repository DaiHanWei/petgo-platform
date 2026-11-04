-- V1.4.2 social-topic Story 1.3 · AC5：关注流（关注的人 ∪ 追更宠物）新查询的性能核查（本地 L1，云端不跑）。
--
-- 用法（本地 docker postgres，库里已有一批用户 / 宠物，例如跑过 L1 集成测试之后）：
--   psql "$DB_URL" -v viewer=<某个 users.id> -f petgo-backend/src/test/resources/explain/following-feed-v1.4.2.sql
--
-- 整个脚本在一个事务里造数并 ROLLBACK，不污染库。造数量级（AC5.1 建议）：
--   viewer 关注 300 人、追更 200 只宠物、库内 5 万帖 / 2 万条 content_pets。
-- 现有用户 / 宠物不够时按实际数量取（LIMIT），把实际量级一并记进 Completion Notes。
--
-- 下面的 SQL 是 ContentPostRepository.findFollowingFeed（JPQL）的等价 native 形态，
-- 以 Hibernate 实际打出的 SQL 为准（spring.jpa.show-sql 开一下即可对照）。

\set ON_ERROR_STOP on
BEGIN;

-- ① 关注 300 人（排除自己）
INSERT INTO user_follows (follower_id, followee_id)
SELECT :viewer, u.id FROM users u
 WHERE u.id <> :viewer AND u.deleted_at IS NULL
 ORDER BY u.id DESC LIMIT 300
ON CONFLICT DO NOTHING;

-- ② 追更 200 只宠物（不是自己的）
INSERT INTO pet_follows (follower_id, pet_profile_id, created_at)
SELECT :viewer, p.id, now() FROM pet_profiles p
 WHERE p.owner_id <> :viewer
 ORDER BY p.id DESC LIMIT 200
ON CONFLICT DO NOTHING;

-- ③ 5 万帖：作者在全体用户里轮转，约 40% 为 GROWTH_MOMENT
CREATE TEMP TABLE _authors AS SELECT id, row_number() OVER (ORDER BY id) AS rn FROM users WHERE deleted_at IS NULL;
CREATE TEMP TABLE _pets AS SELECT id, owner_id, row_number() OVER (ORDER BY id) AS rn FROM pet_profiles;

INSERT INTO content_posts (author_id, type, text, status, visibility, created_at, updated_at)
SELECT a.id,
       CASE WHEN g % 5 < 2 THEN 'GROWTH_MOMENT' ELSE 'DAILY' END,
       'explain-seed-' || g, 'PUBLISHED', 'PUBLIC',
       now() - (g || ' seconds')::interval, now()
  FROM generate_series(1, 50000) g
  JOIN _authors a ON a.rn = 1 + (g % (SELECT count(*) FROM _authors));

-- ④ 2 万条 content_pets：给种子 Diary 挂宠物（帖作者改成宠物主人，保持数据自洽）
WITH diaries AS (
    SELECT id, row_number() OVER (ORDER BY id) AS rn
      FROM content_posts WHERE text LIKE 'explain-seed-%' AND type = 'GROWTH_MOMENT'
     LIMIT 20000
), mapped AS (
    SELECT d.id AS post_id, p.id AS pet_id, p.owner_id
      FROM diaries d JOIN _pets p ON p.rn = 1 + (d.rn % (SELECT count(*) FROM _pets))
), upd AS (
    UPDATE content_posts c SET author_id = m.owner_id, pet_id = m.pet_id
      FROM mapped m WHERE c.id = m.post_id
)
INSERT INTO content_pets (post_id, pet_profile_id)
SELECT post_id, pet_id FROM mapped ON CONFLICT DO NOTHING;

ANALYZE content_posts; ANALYZE content_pets; ANALYZE pet_follows; ANALYZE user_follows; ANALYZE user_hide_relations;

-- ⑤ 首页（无游标）
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.* FROM content_posts p
 WHERE p.deleted_at IS NULL
   AND p.status = 'PUBLISHED'
   AND p.visibility = 'PUBLIC'
   AND p.author_id IN (SELECT u.id FROM users u WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE')
   AND NOT EXISTS (SELECT 1 FROM content_reports r WHERE r.post_id = p.id AND r.reporter_id = :viewer)
   AND NOT EXISTS (SELECT 1 FROM user_hide_relations h WHERE h.holder_id = :viewer AND h.target_id = p.author_id)
   AND (
         p.author_id IN (SELECT f.followee_id FROM user_follows f WHERE f.follower_id = :viewer)
      OR (p.type = 'GROWTH_MOMENT'
          AND EXISTS (SELECT 1 FROM content_pets cp, pet_follows pf
                       WHERE cp.post_id = p.id AND pf.pet_profile_id = cp.pet_profile_id
                         AND pf.follower_id = :viewer)
          AND NOT EXISTS (SELECT 1 FROM user_hide_relations hb
                           WHERE hb.holder_id = p.author_id AND hb.target_id = :viewer
                             AND hb.source = 'BLOCK'))
   )
 ORDER BY p.created_at DESC, p.id DESC
 LIMIT 21;

-- ⑥ 深翻页（游标落在约 1 万条之前）
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.* FROM content_posts p
 WHERE p.deleted_at IS NULL
   AND p.status = 'PUBLISHED'
   AND p.visibility = 'PUBLIC'
   AND p.author_id IN (SELECT u.id FROM users u WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE')
   AND NOT EXISTS (SELECT 1 FROM content_reports r WHERE r.post_id = p.id AND r.reporter_id = :viewer)
   AND NOT EXISTS (SELECT 1 FROM user_hide_relations h WHERE h.holder_id = :viewer AND h.target_id = p.author_id)
   AND (
         p.author_id IN (SELECT f.followee_id FROM user_follows f WHERE f.follower_id = :viewer)
      OR (p.type = 'GROWTH_MOMENT'
          AND EXISTS (SELECT 1 FROM content_pets cp, pet_follows pf
                       WHERE cp.post_id = p.id AND pf.pet_profile_id = cp.pet_profile_id
                         AND pf.follower_id = :viewer)
          AND NOT EXISTS (SELECT 1 FROM user_hide_relations hb
                           WHERE hb.holder_id = p.author_id AND hb.target_id = :viewer
                             AND hb.source = 'BLOCK'))
   )
   AND (p.created_at < now() - interval '10000 seconds')
 ORDER BY p.created_at DESC, p.id DESC
 LIMIT 21;

ROLLBACK;
