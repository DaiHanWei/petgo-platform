-- V20260909_1531__add_comments_reply_to_comment_id.sql
-- V1.3.0 Story 4.2（D-35 / 契约 X-2）预留：二级回复所回复的目标评论 id。本版只落列不写值（暖评只发一级评论），
-- App 分支合入「回复某条评论」后启用；列可空、无默认值，对存量零影响。
ALTER TABLE comments ADD COLUMN reply_to_comment_id BIGINT;
CREATE INDEX ix_comments_reply_to ON comments (reply_to_comment_id) WHERE reply_to_comment_id IS NOT NULL;
COMMENT ON COLUMN comments.reply_to_comment_id IS
    'V1.3.0 预留（D-35/X-2）：二级回复所回复的目标评论 id；本版只落列不写值，App 分支合入后启用';
