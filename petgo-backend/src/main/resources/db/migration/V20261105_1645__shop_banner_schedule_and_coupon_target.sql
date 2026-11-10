-- v1.4.2 shop Story 6.1 · 迁移 ⑤（D-16，PRD FR-132）：banner 排期 + 跳转类型加 COUPON。
--
-- 取代 V20261030_0330 「轮播仍不做、领券不加」的口径：本版首页 banner 改为多张轮播（上限 6，由
-- ShopBannerQueryService 在读取端截断），新增「领券」跳转（target_value = 券模板 public_token）。
--   · start_at / end_at —— 排期，左闭右开 [start_at, end_at)；NULL = 不限。存量行两列为 NULL，即一直有效，行为不变。
--   · CATEGORY 值保留在库里，下发新端点时转成 TOKO_HOME（品类筛选本版下线）；旧端点仍按 CATEGORY 下发。
--   · ck_shop_banners_target_value（NONE ⇔ 值为空）保留不动。
-- 🔴 ck_shop_banners_target_type 按规则 DROP + ADD 重列全集：取 V20261030_0330 原文 4 值 + COUPON。
ALTER TABLE shop_banners
    ADD COLUMN start_at TIMESTAMPTZ,
    ADD COLUMN end_at   TIMESTAMPTZ;

ALTER TABLE shop_banners ADD CONSTRAINT ck_shop_banners_schedule
    CHECK (start_at IS NULL OR end_at IS NULL OR start_at < end_at);

ALTER TABLE shop_banners DROP CONSTRAINT ck_shop_banners_target_type;
ALTER TABLE shop_banners ADD CONSTRAINT ck_shop_banners_target_type
    CHECK (target_type IN ('NONE', 'PRODUCT', 'CATEGORY', 'EXTERNAL', 'COUPON'));

COMMENT ON COLUMN shop_banners.start_at IS '排期起（含），NULL = 不限，V1.4.2';
COMMENT ON COLUMN shop_banners.end_at   IS '排期止（不含），NULL = 不限，V1.4.2';
COMMENT ON COLUMN shop_banners.target_type IS '点击跳转类型 NONE/PRODUCT/CATEGORY/EXTERNAL/COUPON，V1.4.2 加 COUPON';
COMMENT ON COLUMN shop_banners.target_value IS '跳转目标：商品 public_token / ProductCategory / https 链接 / 券模板 public_token；NONE 时为空';
