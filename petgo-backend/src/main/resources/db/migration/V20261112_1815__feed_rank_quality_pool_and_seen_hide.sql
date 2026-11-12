-- 修复线（2026-11-12 产品）：Discover 推荐流三项改进的后台可调参数 + 两条按人查询索引。
--
-- ① 优质老帖候选池：除「最近 N 条」主池外，另取近 quality_pool_days 天互动最高的 quality_pool_size 条一并参与打分。
--    原主池只有最近 1000 条（生产约 4 天），更早的高互动帖子永远进不了推荐流。size = 0 关闭。
-- ② 互动冷却：本人点过赞 / 评论过的帖子 interacted_cooldown_days 天内不再推给本人（从最近一次互动算起）。0 关闭。
-- ③ 看过暂不出现：seen_hide_hours 小时内已下发过的帖子先不排进来，没看过的不够时才回补（按曝光衰减降分）。0 关闭。
--
-- 全部在后台「算法参数」页露出，改动写 config_change_logs（config_type = FEED_RANK，已在 CHECK 全集内，不动共享约束）。
-- INTEGER 对齐实体 int（ddl-auto=validate）。CHECK 与 AdminConfigService.updateFeedRank 同一口径：
--    回看天数 1–180；条数 0–2000（上限防误填把每次排序拖慢）；冷却天数 0–30；暂不出现小时数 0–240。
ALTER TABLE feed_rank_config
    ADD COLUMN quality_pool_days        INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN quality_pool_size        INTEGER NOT NULL DEFAULT 300,
    ADD COLUMN interacted_cooldown_days INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN seen_hide_hours          INTEGER NOT NULL DEFAULT 24;

ALTER TABLE feed_rank_config
    ADD CONSTRAINT ck_feed_rank_quality_pool_days CHECK (quality_pool_days BETWEEN 1 AND 180),
    ADD CONSTRAINT ck_feed_rank_quality_pool_size CHECK (quality_pool_size BETWEEN 0 AND 2000),
    ADD CONSTRAINT ck_feed_rank_interacted_cooldown_days CHECK (interacted_cooldown_days BETWEEN 0 AND 30),
    ADD CONSTRAINT ck_feed_rank_seen_hide_hours CHECK (seen_hide_hours BETWEEN 0 AND 240);

COMMENT ON COLUMN feed_rank_config.quality_pool_days IS '优质老帖候选池：回看天数（默认 30）';
COMMENT ON COLUMN feed_rank_config.quality_pool_size IS '优质老帖候选池：按互动量取前 N 条（默认 300；0 = 关闭）';
COMMENT ON COLUMN feed_rank_config.interacted_cooldown_days IS '点赞 / 评论过的帖子多少天内不再推给本人（默认 3；0 = 关闭）';
COMMENT ON COLUMN feed_rank_config.seen_hide_hours IS '下发过的帖子多少小时内先不出现，不够才回补（默认 24；0 = 关闭）';

-- ② 每次生成推荐序都按「本人 + 近 N 天」查点赞 / 评论过的帖子；两张表此前都没有按人的索引。
CREATE INDEX IF NOT EXISTS idx_content_likes_user_created ON content_likes (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_comments_author_created ON comments (author_id, created_at);
