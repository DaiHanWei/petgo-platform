-- 场所评论 + 二元态度（V1.3.0 batch-b1 Story 1.7 · FR-112.3 · AD-8）。
--
-- 🔴 **独立建表，不改既有 comments、不用多态外键**（AD-8，2026-09-14 拍板）。两条理由：
--    ① 既有 `comments.post_id` 是 NOT NULL 且语义绑死内容帖。共表就得把它改成可空再加场所列 ——
--       那正是 v1.1.6 AD-10 已经否过的多态外键；
--    ② 形态本就不同：内容评论有楼中楼，场所评论**只有一级**（攻略提示性质，无对话需求）。
--       共表等于把"不能回复"降格成一条只靠代码自觉维持的约定。
--
-- 🔴 **本表没有 parent_id，这是"只有一级"的结构性保证**（AC2）。
--    不要"为以后留个 parent_id" —— 留了它，某天加一个端点就能盖楼，而 PRD ③ 明确排除盖楼。
--
-- 审核：文本走与内容评论**同一套**三方审核（ContentModerationService），但**状态各存各的** ——
-- 所以这里有自己的 moderation_status 列，取值域与 Java 枚举 CommentModerationStatus 同源。
CREATE TABLE IF NOT EXISTS place_comments (
    id                BIGSERIAL PRIMARY KEY,
    place_id          BIGINT       NOT NULL,
    author_id         BIGINT       NOT NULL,
    -- ≤200 字（PRD ③）。长度上限**以服务端为权威**，客户端的计数器只是提前告知。
    body              VARCHAR(200) NOT NULL,
    -- 二元态度：RECOMMEND / NOT_RECOMMEND / NULL（可以不表态，AC3）。
    -- 🔴 **可空列而不是独立投票表**（AD-8 §4）：不写评论就不能表态（B1-D3）。
    --    做成独立表就等于允许"只投票不评论"，那是被否掉的形态。
    attitude          VARCHAR(16),
    -- 审核可见性态。默认 VISIBLE 只是 DDL 缺省；实际写入走 UNDER_REVIEW（先发后审，见 service）。
    moderation_status VARCHAR(24)  NOT NULL DEFAULT 'VISIBLE',
    -- 内容版本键（D-CM3）：供陈旧审核结果作废。无编辑端点，故恒为 1。
    content_version   INTEGER      NOT NULL DEFAULT 1,
    -- 软删（用户自删 AC7 / 运营删）。硬删会让计数回算与审核队列对不上。
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 态度取值域只有两个值，且**没有第三个"中立"值** —— NULL 就是"没表态"。
-- 这里写 CHECK 是值得的：它是本表独有的列（不像 moderation_status 那样与 comments 同源枚举），
-- 一旦有人往里写 'NEUTRAL'，计数口径会悄悄多出一档。
ALTER TABLE place_comments DROP CONSTRAINT IF EXISTS ck_place_comments_attitude;
ALTER TABLE place_comments ADD CONSTRAINT ck_place_comments_attitude
    CHECK (attitude IS NULL OR attitude IN ('RECOMMEND', 'NOT_RECOMMEND'));

-- 详情页评论区的读路径：某场所的未删评论按时间倒序。
-- 🔴 partial index（WHERE deleted_at IS NULL）：软删行永远不参与列表，别让它们占着索引。
CREATE INDEX IF NOT EXISTS ix_place_comments_place_created
    ON place_comments (place_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- 态度计数回算（Story 1.8 的 DB 真值来源）与"我评论过哪些场所"。
CREATE INDEX IF NOT EXISTS ix_place_comments_place_attitude
    ON place_comments (place_id, attitude)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_place_comments_author
    ON place_comments (author_id);

COMMENT ON TABLE  place_comments                   IS '场所评论（Story 1.7）；一级 only（无 parent_id）、独立于 comments';
COMMENT ON COLUMN place_comments.place_id          IS '所属场所 places.id（站内标识，不外露）';
COMMENT ON COLUMN place_comments.body              IS '评论正文 ≤200 字，服务端权威';
COMMENT ON COLUMN place_comments.attitude          IS '二元态度 RECOMMEND/NOT_RECOMMEND；NULL=未表态（不写评论不能表态）';
COMMENT ON COLUMN place_comments.moderation_status IS '审核态，取值域同 Java 枚举 CommentModerationStatus；与 comments 各存各的';
