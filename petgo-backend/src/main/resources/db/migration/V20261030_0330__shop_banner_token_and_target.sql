-- V1.4.1：Toko banner 曝光 / 点击埋点 + 单张可点击（2026-10-30 产品拍板「曝光 + 单张可点击」）。
--
-- 取代 V20260827_1500 里「纯展示、不可点」的口径：这一版明确要跳转，所以现在加列（当时就说好了「宁可以后加列」）。
--   · public_token —— 埋点 banner_id 用的不可枚举标识（CLAUDE.md 护栏：对外不外露自增 id）。
--     Base62 风格 22 位由应用层 ShopTokenGenerator 生成；存量行在这里用随机 md5 回填（同为不可枚举）。
--   · target_type / target_value —— 点击跳转：NONE 不可点 / PRODUCT 商品详情（值 = 商品 public_token）/
--     CATEGORY 切到 Toko 对应品类（值 = ProductCategory 字面量）/ EXTERNAL 系统浏览器打开（值 = https 链接）。
--     「领券」没有对应功能，本版不加枚举值。
-- 轮播仍不做：同一时间只展示一张，埋点 position 恒为 1。
ALTER TABLE shop_banners
    ADD COLUMN public_token VARCHAR(32),
    ADD COLUMN target_type  VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN target_value VARCHAR(512);

UPDATE shop_banners
SET public_token = substr(md5(random()::text || id::text || clock_timestamp()::text), 1, 22)
WHERE public_token IS NULL;

ALTER TABLE shop_banners ALTER COLUMN public_token SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_shop_banners_public_token ON shop_banners (public_token);

ALTER TABLE shop_banners
    ADD CONSTRAINT ck_shop_banners_target_type
        CHECK (target_type IN ('NONE', 'PRODUCT', 'CATEGORY', 'EXTERNAL'));

-- 不可点 ⇔ 没有目标值：防「配了类型没配值」或「NONE 却残留旧链接」两种半截状态。
ALTER TABLE shop_banners
    ADD CONSTRAINT ck_shop_banners_target_value
        CHECK ((target_type = 'NONE') = (target_value IS NULL));

COMMENT ON COLUMN shop_banners.public_token IS '对外不可枚举标识（埋点 banner_id），V1.4.1';
COMMENT ON COLUMN shop_banners.target_type IS '点击跳转类型 NONE/PRODUCT/CATEGORY/EXTERNAL，V1.4.1';
COMMENT ON COLUMN shop_banners.target_value IS '跳转目标：商品 public_token / ProductCategory / https 链接；NONE 时为空';
