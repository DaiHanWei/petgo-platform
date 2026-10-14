-- V1.3.4 点赞人列表：按帖分页（created_at DESC, id DESC）的游标扫描索引。
CREATE INDEX idx_content_likes_post_created ON content_likes (post_id, created_at DESC, id DESC);
