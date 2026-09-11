-- V1.3.0 批次 A · Story 2.4（AD-A7）：评论点赞关系表。本批次唯一的新业务表。
--
-- 🔴 **逐字对齐 content_likes 的形态**（V10）—— 同一件事（谁给什么点了赞）在库里长同一个样子，
--    否则两处的取数、注销处理、对账口径都会各写一套，迟早走散。
--
-- ⚠️ **一级、二级评论共用同一张表，不按层级分表**：层级由 comments.parent_id 决定，与点赞无关。
--    分表会让「某条评论有多少赞」这个问题变成「先判它是几级、再查对应的表」。
--
-- 🔴 **没有 like_count 冗余计数列，且不许后来人加**（AD-A7.3）：
--    全库至今没有任何冗余计数列（content_likes 也是实时 COUNT）。开这个头就要处理并发增减、
--    回填与对账 —— 而本表的规模前提（单帖一级评论几十到几百）根本用不上它。
--    代价已明确接受：排序键第一元是跨表聚合值，**建不了索引**，每页现算一次（AD-A8.5）。
--
-- 索引只支撑**聚合与过滤两侧**（AD-A8.5）：
--   · comment_likes(comment_id) —— 聚合侧，COUNT/GROUP BY 走它；
--   · comments(post_id, parent_id, deleted_at, created_at) —— 过滤侧，一级评论取数走它。
-- 排序本身没有索引可用，这是上面那条「不加计数列」的直接后果，不是遗漏。
--
-- 注销级联（AC8）：user_id 为 NOT NULL + RESTRICT 外键，与 content_likes **完全相同** ——
-- 注销走「就地匿名化 user 行、不物理删」（决策 D1/A），点赞行原样保留、指向已匿名化的用户。
-- **不要**给本表单独加删除逻辑，那就是另立了一套口径。

CREATE TABLE comment_likes (
    id         BIGSERIAL    PRIMARY KEY,
    comment_id BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT uq_comment_likes_comment_user UNIQUE (comment_id, user_id),
    CONSTRAINT fk_comment_likes_comment FOREIGN KEY (comment_id) REFERENCES comments (id),
    CONSTRAINT fk_comment_likes_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 聚合侧：按 comment_id 数赞。
CREATE INDEX idx_comment_likes_comment ON comment_likes (comment_id);

-- 过滤侧：一级评论按帖取数（parent_id IS NULL + 未软删 + 时间 tie-breaker）。
CREATE INDEX idx_comments_post_parent_alive
    ON comments (post_id, parent_id, deleted_at, created_at);

COMMENT ON TABLE comment_likes IS
    '评论点赞关系（一级/二级共用）。形态逐字对齐 content_likes；无冗余计数列，点赞数实时聚合';
