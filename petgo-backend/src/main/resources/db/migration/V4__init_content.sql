-- Story 2.3：content 模块地基。创建 content_posts 表（三类内容发布的数据根；Epic 3 Feed/详情/互动复用）。
-- Flyway 序号：接 V3__init_profile 之后单调分配（决策 E2）。schema 归 Flyway，ddl-auto=validate。
-- 弹性字段：image_urls 用 JSONB；type/danger_level/status 落 varchar + UPPER_SNAKE。软删 deleted_at（为 Epic3 删除/注销级联准备）。

CREATE TABLE content_posts (
    id           BIGSERIAL    PRIMARY KEY,
    author_id    BIGINT       NOT NULL,
    type         VARCHAR(20)  NOT NULL,                 -- DAILY | GROWTH_MOMENT | KNOWLEDGE
    pet_id       BIGINT,                                -- 仅 GROWTH_MOMENT 绑定（属作者档案）
    text         VARCHAR(1000),
    image_urls   JSONB,                                 -- 公开桶 CDN URL 列表（≤9）
    danger_level VARCHAR(8),                            -- 弹性：成长日历可关联问诊评级；普通发布留 null
    status       VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED',
    deleted_at   TIMESTAMPTZ,                           -- 软删（Epic3 删除 / 注销级联）
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT ck_content_posts_type CHECK (type IN ('DAILY', 'GROWTH_MOMENT', 'KNOWLEDGE')),
    -- 注销级联前置（FR-20）：注销时作者内容按 D1 处理（UGC 匿名化/删除，详见 7.3）。
    CONSTRAINT fk_content_posts_author FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT fk_content_posts_pet FOREIGN KEY (pet_id) REFERENCES pet_profiles (id)
);

CREATE INDEX idx_content_posts_author_id  ON content_posts (author_id);
CREATE INDEX idx_content_posts_pet_id     ON content_posts (pet_id);
CREATE INDEX idx_content_posts_type       ON content_posts (type);
CREATE INDEX idx_content_posts_created_at ON content_posts (created_at DESC);
