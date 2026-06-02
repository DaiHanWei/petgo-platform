-- Story 3.3：content 评论表（两级：parent_id 自引用，最多两级）。本 Story 落表 + 只读路径；
-- 写路径（创建/删除）在 Story 3.5。Flyway 序号接 V8 之后单调分配（决策 E2）。
-- 一级评论 parent_id 为 NULL；二级回复 parent_id 指向某一级评论。软删 deleted_at（3.5/3.6/7.3 用）。

CREATE TABLE comments (
    id         BIGSERIAL    PRIMARY KEY,
    post_id    BIGINT       NOT NULL,
    parent_id  BIGINT,                                -- NULL=一级评论；非空=二级回复（指向一级）
    author_id  BIGINT       NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    deleted_at TIMESTAMPTZ,                           -- 软删（Story 3.5 删除 / 3.6 / 7.3 级联）
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES content_posts (id),
    CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES comments (id),
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users (id)
);

-- 一级评论按帖时间正序游标分页：(post_id, parent_id, created_at, id)。
CREATE INDEX idx_comments_post_toplevel ON comments (post_id, created_at, id) WHERE parent_id IS NULL;
-- 二级回复按父评论时间正序：(parent_id, created_at, id)。
CREATE INDEX idx_comments_parent ON comments (parent_id, created_at, id);
