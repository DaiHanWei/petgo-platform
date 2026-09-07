-- 2026-08-31：内容浏览统计。口径（产品拍板）：打开详情页记一次；作者本人打开自己的帖子不计。
-- 每「内容 × 观看者」一行：浏览次数 = SUM(view_count)，浏览人数 = COUNT(*)。
-- viewer_key：登录用户 'u:<userId>'；游客 'a:<匿名会话id>'（沿用信息流 X-Anon-Session 机制）。
-- 游客没带匿名会话头（老版本 App）则整次不记 —— 揉进共享桶会把「人数」算错，宁可少记。
-- 注销口径与 content_likes 一致（D1/A：账号就地匿名化、行为数据保留），不做级联删除。
CREATE TABLE content_post_views (
    id              BIGSERIAL    PRIMARY KEY,
    post_id         BIGINT       NOT NULL,
    viewer_key      VARCHAR(40)  NOT NULL,
    view_count      BIGINT       NOT NULL DEFAULT 1,
    first_viewed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    last_viewed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT uq_content_post_views_post_viewer UNIQUE (post_id, viewer_key),
    CONSTRAINT fk_content_post_views_post FOREIGN KEY (post_id) REFERENCES content_posts (id)
);

-- 聚合读路径（后台列表整页批量取）按 post_id 查：唯一约束的复合索引已覆盖，不另建。
