-- Story 3.4：内容点赞关系表。统一表名 content_likes（勿用 likes）。Flyway 序号接 V9 之后（决策 E2）。
-- 唯一约束 (post_id, user_id) 防重复点赞；计数策略：V1 实时 COUNT(*)（500 DAU 足够，禁 Redis 计数缓存）。

CREATE TABLE content_likes (
    id         BIGSERIAL    PRIMARY KEY,
    post_id    BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT uq_content_likes_post_user UNIQUE (post_id, user_id),
    CONSTRAINT fk_content_likes_post FOREIGN KEY (post_id) REFERENCES content_posts (id),
    CONSTRAINT fk_content_likes_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_content_likes_post ON content_likes (post_id);
