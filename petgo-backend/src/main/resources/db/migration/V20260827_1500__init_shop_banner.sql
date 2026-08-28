-- Toko 顶部 banner（2026-08-27 产品需求）。
--
-- 产品口径（拍板时定死，实现按此，勿自行扩展）：
--   · **同一时间只展示一张** —— 表里可以配多条，但 App 只取「已上架 + 权重最高」的那一条。
--     不做轮播：轮播的第二张之后经常没被看到就被划走，收益不抵实现与运营成本。
--   · **纯展示，不可点** —— 本版本不带跳转目标。所以表里没有 target/link 列：
--     🔴 宁可以后加列，也不要现在放一个恒为空的 link 字段 —— 空字段会让下一个人
--     以为「跳转已经做了只是没配」，进而在 App 侧写出永远走不到的分支。
--   · **无 banner 时顶部不是留白，而是白色顶栏**（与其他板块的深色顶栏区分），
--     这条在客户端实现，与本表无关。
--
-- image_w / image_h 与 shop_products 同一口径（见 V20260827_1400）：
-- 只存原始像素，不存比例。App 用它按屏宽算出 banner 高度，避免图到达前后布局跳动。
-- ⚠️ 可空：手填 objectKey 的兜底路径给不出尺寸，此时 App 走默认比例。
CREATE TABLE IF NOT EXISTS shop_banners (
    id          BIGSERIAL PRIMARY KEY,
    image_key   VARCHAR(255) NOT NULL,
    image_w     INTEGER,
    image_h     INTEGER,
    -- 🔴 默认**未上架**：与商品同一安全默认（V1.4.0 Story 1.5）——
    -- 新建即可见会让运营在还没检查图的时候就把它推到了所有用户首屏。
    active      BOOLEAN NOT NULL DEFAULT FALSE,
    -- 并列多条时的取用顺序；相同权重按 id 倒序（后建的优先）。
    sort_weight INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- App 每次进 Toko 都会查一次「当前该显示哪张」，走这条索引；
-- 部分索引只覆盖已上架的行 —— 下架的历史 banner 不该占索引体积。
CREATE INDEX IF NOT EXISTS ix_shop_banners_active_pick
    ON shop_banners (sort_weight DESC, id DESC)
    WHERE active = TRUE;

COMMENT ON TABLE  shop_banners             IS 'Toko 顶部 banner；同一时间只展示一张（active + 权重最高）';
COMMENT ON COLUMN shop_banners.image_key   IS 'OSS objectKey，非 URL —— 签名 URL 禁入库（NFR-5）';
COMMENT ON COLUMN shop_banners.image_w     IS '原始像素宽；null=未知（手填 key 的兜底路径），App 走默认比例';
COMMENT ON COLUMN shop_banners.image_h     IS '原始像素高；null=未知';
COMMENT ON COLUMN shop_banners.active      IS '是否上架；默认 false，需运营主动上架';
COMMENT ON COLUMN shop_banners.sort_weight IS '取用权重，越大越优先；同权重按 id 倒序';
