-- V1.3.4 follow · Story 2.3 —— 推荐关注「近 30 天获赞」按点赞时间取窗（AD-4，决策 T-28）。
--
-- · 推荐查询 recent_likes 段 `content_likes.created_at >= now() - 30 天`，原先无可用索引 → 全表顺扫 content_likes；
--   加本索引后变为范围扫描 + index-only 取 post_id，成本只与近 30 天点赞量相关。
--   顺带让运营周报 ContentLikeRepository.countInWindow（同样按 created_at 取窗）受益。
-- · 写放大：每次点赞多维护一棵小索引，可接受。表量级小，普通 CREATE INDEX（Flyway 事务内不能 CONCURRENTLY，
--   与仓库既有索引迁移同做法）。
-- · ⚠️ 是否保留以本地造数（≥ 5 万条点赞）后的 EXPLAIN (ANALYZE, BUFFERS) 为准（T-28，云端无库未做）；
--   若结论为不需要，且本迁移尚未被任何环境应用，可直接删除本文件。

CREATE INDEX IF NOT EXISTS idx_content_likes_created_at ON content_likes (created_at) INCLUDE (post_id);
