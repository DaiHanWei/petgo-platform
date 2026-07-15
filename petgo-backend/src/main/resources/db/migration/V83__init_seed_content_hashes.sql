-- Story 9.8 Part 2（AB-1.1-02，轻量批量种子上传）：内容 hash 去重（防重发）。号 V83（本分支 max V82；决策 E2）。
-- ⚠️ merge 时与 9-7 分支复核撞号顺延。
-- 轻量方案（用户 2026-07-14 定）：复用 seed-post 发布 + 虚拟账号逐条发，内容 hash 去重跨批防重发。

CREATE TABLE seed_content_hashes (
    content_hash VARCHAR(64) PRIMARY KEY,                  -- sha256(type|text|sorted images)
    post_id      BIGINT      NOT NULL,                     -- 首次发布的 content_posts.id
    author_id    BIGINT      NOT NULL,                     -- 虚拟账号 users.id
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
