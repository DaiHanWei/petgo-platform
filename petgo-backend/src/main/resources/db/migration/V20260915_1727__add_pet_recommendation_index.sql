-- V1.3.0 batch-b1 · Story 4.1「推荐规则与宠物卡」AC2（算法成本口径）。
--
-- 🔴 推荐池是**实时 SQL**，不许加缓存层（NFR-8 / AC2）。所以索引方案必须写出来，
--    而不是"先跑起来看看"。AC2 点名要覆盖两个过滤维度，它们其实是同一条聚合查询的两半：
--
--   ① 「近 14 天有新公开 Diary 帖」→ 按 pet_id 聚合取 MAX(created_at)
--   ② 「公开成长记录 ≥3 条」      → 按 pet_id 聚合取 COUNT(*)
--
--    两者的过滤谓词完全相同（GROWTH_MOMENT + PUBLIC + PUBLISHED + 未删 + pet_id 非空），
--    所以**一条部分索引**就能同时服务两个维度：谓词全部下推到索引条件里，
--    索引里只剩 (pet_id, created_at) —— 正好是 GROUP BY 键 + 聚合列，可以走
--    index-only scan，不回表。
--
-- ⚠️ 为什么是 partial index 而不是普通复合索引：
--    公开的成长日历帖只是 content_posts 的一个小切片（日常/科普/私密 Diary 都不在内）。
--    普通索引会把全表都装进去，而这张表是全站最大的之一。
-- ⚠️ 既有 idx_content_posts_pet_id 是单列的，扛不住这条查询：它拿不到 created_at，
--    每个 pet 都要回表才能算 MAX。**刻意不删它** —— 别处按 pet_id 精确查还在用。
--
-- 🔴 EXPLAIN 必须在本地真库上贴到 story 的 Completion Notes（AC2 原文要求）——
--    云端 headless 没有 postgres，那一步标了「L1 待本地验收」。
CREATE INDEX IF NOT EXISTS idx_content_posts_pet_recommend
    ON content_posts (pet_id, created_at DESC)
    WHERE type = 'GROWTH_MOMENT'
      AND visibility = 'PUBLIC'
      AND status = 'PUBLISHED'
      AND deleted_at IS NULL
      AND pet_id IS NOT NULL;

COMMENT ON INDEX idx_content_posts_pet_recommend IS
    'Story 4.1 推荐池：公开成长日历帖按宠物聚合（MAX(created_at) + COUNT(*)）。部分索引，谓词已下推。';

-- 卡片大图要的是「该宠物最近一张**公开照片**」（AC4 的第一个图片字段）。
-- 判据比上面多一条：那条帖子得**有配图**。image_urls 是 JSONB 数组，空数组与 NULL 都算没图。
--
-- ⚠️ 与上面那条刻意**分成两个索引**：上面那条服务的是「谁该进池子」（不关心有没有图），
--    这条服务的是「进了池子的宠物，封面取哪一张」。合成一个的话，
--    「有 3 条公开记录但都没配图」的宠物会被整个挡在池外 —— 而 AC1 的门槛里没有这一条。
CREATE INDEX IF NOT EXISTS idx_content_posts_pet_cover
    ON content_posts (pet_id, created_at DESC)
    WHERE type = 'GROWTH_MOMENT'
      AND visibility = 'PUBLIC'
      AND status = 'PUBLISHED'
      AND deleted_at IS NULL
      AND pet_id IS NOT NULL
      AND image_urls IS NOT NULL
      AND jsonb_array_length(image_urls) > 0;

COMMENT ON INDEX idx_content_posts_pet_cover IS
    'Story 4.1 宠物卡封面：该宠物最近一张带配图的公开成长日历帖。';
