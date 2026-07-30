-- 内容审核 story 9（注销联动，§5.5 R1）：评论可见性态扩容纳「作者已注销 → 对他人隐藏」。
-- content_posts.status 为无 CHECK 的 varchar（新增 AUTHOR_DEACTIVATED 免迁移）；comments.moderation_status
-- 有 CHECK（V49），故此处放宽 CHECK 容纳新值。语义：作者注销后其评论对他人不可见、内容保留（与匿名化并存）。
ALTER TABLE comments
    DROP CONSTRAINT ck_comments_moderation_status;

ALTER TABLE comments
    ADD CONSTRAINT ck_comments_moderation_status CHECK
        (moderation_status IN ('VISIBLE', 'UNDER_REVIEW', 'TAKEN_DOWN', 'REJECTED', 'AUTHOR_DEACTIVATED'));
