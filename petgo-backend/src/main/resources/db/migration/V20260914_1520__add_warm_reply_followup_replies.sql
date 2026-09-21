-- V20260914_1520__add_warm_reply_followup_replies.sql —— V1.3.0 暖贴跟进计数绑定到具体回复（代码复审 0914 P2）
-- 问题：pending_reply_count 只按「一级暖评」加减，不记是哪条回复计的数。回复被下架 → 恢复（恢复不重新入队）→ 再次下架 / 作者自删，
--      会第二次发 CommentRemovedEvent，把另一条从未跟进过的新回复的待办抵消掉。
-- 修法：登记「本跟进项计入了哪些回复」；出队时只有该回复确实登记在当前 PENDING 项上才减一（删登记行即核销，天然幂等）。
CREATE TABLE warm_reply_followup_replies (
    followup_id       BIGINT      NOT NULL REFERENCES warm_reply_followups (id) ON DELETE CASCADE,
    reply_comment_id  BIGINT      NOT NULL,   -- comments.id（真实用户的二级回复）；不加 FK：评论软删不物理删，行随跟进项级联清理
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (followup_id, reply_comment_id)
);
CREATE INDEX ix_warm_reply_followup_replies_reply ON warm_reply_followup_replies (reply_comment_id);
COMMENT ON TABLE warm_reply_followup_replies IS '暖贴跟进项计入的回复登记：入队登记、出队核销；防同一回复被扣两次账';

-- 存量 PENDING 项回填最近一条回复（更早的回复无从追溯；它们被移除时不再减计数，最坏多留一条待跟进，由运营标记已读）
INSERT INTO warm_reply_followup_replies (followup_id, reply_comment_id)
SELECT id, last_reply_id FROM warm_reply_followups WHERE status = 'PENDING' AND last_reply_id IS NOT NULL
ON CONFLICT DO NOTHING;
