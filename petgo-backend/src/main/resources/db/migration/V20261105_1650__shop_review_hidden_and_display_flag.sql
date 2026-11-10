-- v1.4.2 shop Story 6.1 · 迁移 ⑥（D-17，PRD FR-133「商品详情」评价列表）：评价隐藏 + 评价列表展示开关。
--
--   · shop_reviews.hidden_at —— 运营隐藏时刻；非空 = 不展示、不计入均分与条数。隐藏不改 review_status（仍为 PUBLISHED，可撤销）。
--   · 新部分索引只覆盖「已发布且未隐藏」：详情页评价区与 /reviews 分页只读这部分。
--     旧索引 ix_shop_reviews_product 保留（不 DROP，避免影响已应用环境）。
--   · shipping_settings.review_display_enabled —— 评价列表展示开关（默认关，运营过完历史评价后打开）。
--     架构 D-17 写的 platform_config 键值表在代码中不存在；shipping_settings 是现成的电商全局单行配置。
ALTER TABLE shop_reviews ADD COLUMN hidden_at TIMESTAMPTZ;

CREATE INDEX ix_shop_reviews_product_visible ON shop_reviews (product_id, created_at DESC, id DESC)
    WHERE review_status = 'PUBLISHED' AND hidden_at IS NULL;

ALTER TABLE shipping_settings ADD COLUMN review_display_enabled BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN shop_reviews.hidden_at IS '运营隐藏时刻（V1.4.2）；非空 = 不展示、不计入均分与条数';
COMMENT ON COLUMN shipping_settings.review_display_enabled IS '商品评价列表展示开关（V1.4.2，默认关）；关闭时只展示均分与条数';
