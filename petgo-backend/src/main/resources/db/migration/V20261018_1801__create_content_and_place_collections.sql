-- V1.4.0 ④ push-collect · Story 2.1 · AD-7：帖子收藏 / 场所收藏（FR-151）。
-- · 收藏是「仅本人可见」的私人数据；注销由 CollectDeletionService 显式删除（users 行只匿名化，CASCADE 不触发）。
-- · 外键一律 ON DELETE CASCADE：生产无硬删路径，CASCADE 只为存量测试 deleteAll 不被阻断。
-- · 帖子软删 / 下架 / 场所下架 / 合并时**不删**收藏行（列表读时判不可见，PRD「保留这一条」）。
-- · 不加冗余计数列（与点赞一致，实时 COUNT）。自增 id 不外露（帖子沿用 /content-posts/{id}，场所用 public_token）。

CREATE TABLE content_collections (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    post_id    BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT uq_content_collections_user_post UNIQUE (user_id, post_id),
    CONSTRAINT fk_content_collections_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_collections_post FOREIGN KEY (post_id) REFERENCES content_posts (id) ON DELETE CASCADE
);
-- 我的收藏列表（2.2）：按收藏时间倒序 keyset。
CREATE INDEX idx_content_collections_user_created ON content_collections (user_id, created_at DESC, id DESC);
-- 作者视角收藏数（详情 collectCount）。
CREATE INDEX idx_content_collections_post ON content_collections (post_id);

CREATE TABLE place_collections (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    place_id   BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT uq_place_collections_user_place UNIQUE (user_id, place_id),
    CONSTRAINT fk_place_collections_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_place_collections_place FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE
);
CREATE INDEX idx_place_collections_user_created ON place_collections (user_id, created_at DESC, id DESC);
-- 场所收藏数本版不展示，索引留给以后「最多人收藏的场所」与合并覆盖查询。
CREATE INDEX idx_place_collections_place ON place_collections (place_id);
