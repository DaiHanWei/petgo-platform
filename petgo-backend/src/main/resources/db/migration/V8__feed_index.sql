-- Story 3.2：Feed 读取性能。复合部分索引支撑「公开内容时间倒序游标分页 + 类型过滤」。
-- Flyway 序号：接 V7 之后单调分配（决策 E2）。
-- 排序键 (created_at DESC, id DESC) 与游标分页一致；WHERE deleted_at IS NULL 收窄为公开可见集。
-- type 入索引以支撑分类过滤与 B 状态硬过滤（type <> GROWTH_MOMENT）。

CREATE INDEX idx_content_posts_feed
    ON content_posts (created_at DESC, id DESC)
    INCLUDE (type, pet_id)
    WHERE deleted_at IS NULL;
