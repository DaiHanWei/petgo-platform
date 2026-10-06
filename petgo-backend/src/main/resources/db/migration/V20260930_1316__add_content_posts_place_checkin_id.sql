-- V1.3.2 batch-a · Story 1.5 —— 帖子关联打卡（架构 delta AD-10）。
--
-- · 可空：只有「打卡后顺手发帖」带值；任何帖子类型均可。
-- · ON DELETE SET NULL：删档时 PlaceCheckinDeletionService 删打卡行，帖子（UGC）按既有口径保留，关联自动断开。
-- · 索引：Story 1.6 按它反查「该打卡的关联帖子」做 Diary 去重。

ALTER TABLE content_posts
    ADD COLUMN place_checkin_id BIGINT NULL REFERENCES place_checkins (id) ON DELETE SET NULL;

CREATE INDEX idx_content_posts_place_checkin ON content_posts (place_checkin_id) WHERE place_checkin_id IS NOT NULL;

COMMENT ON COLUMN content_posts.place_checkin_id IS
    '打卡后顺手发帖关联的打卡（AD-10）；打卡被删（删档）时置空，帖子保留。';
