-- 宠物友好场所本体（V1.3.0 batch-b1 Story 1.1 · FR-112.1 · AD-1）。
--
-- AD-1 的三条硬口径（改这张表前先回去读一遍）：
--   ① **只存经纬度 + 文字地址**，不存任何地图厂商的对象 id / POI id / plus code ——
--      换地图厂商（B1-D9 刚把 OSM 换成 Google）时数据一行都不用动。
--   ② **对外走不可枚举 token**（public_token），自增 id 只在站内 API 与后台使用，
--      绝不出现在分享链接里 —— 用场所名或自增 id 拼链接，等于让任何人按名字/按序号爬全站场所。
--   ③ 文字地址是**必填的位置备注**，平台不做地理编码、不校验它与坐标是否一致；
--      因此它是纯展示 + 一键复制的字符串，**不得被任何逻辑当作可解析的结构化地址**。
--
-- 🔴 **本表不预留 ⑧ 场所打卡的任何列**（打卡在批次 B2，连 content_posts 的场所关联列都不属本批次）。
--    "顺手预留一下" 会让 B2 的建模被一个没讨论过的形状绑死 —— 宁可 B2 再加列。
-- 🔴 **零地理扩展**：全库没有一条 CREATE EXTENSION，本迁移也不引入（AD-2 Rule 1/4：
--    禁 PostGIS / cube / earthdistance）。距离排序是 Story 1.2 的事，口径已定死为
--    「纯 SQL 直线距离 + 经纬度矩形范围粗筛 + 两列各建索引」——所以这里把经纬度存成
--    **两个独立的可建索引的数值列**，不拼字符串、不用 point 类型。
CREATE TABLE IF NOT EXISTS places (
    id            BIGSERIAL PRIMARY KEY,
    -- 不可枚举对外标识（32 位 BASE62 + SecureRandom，同 notify 侧 token 形态）。
    public_token  VARCHAR(32)  NOT NULL,
    name          VARCHAR(80)  NOT NULL,
    -- 7 类单选（FR-112.1）。枚举落库 varchar + UPPER_SNAKE（命名映射链）。
    type          VARCHAR(24)  NOT NULL,
    -- 宠物友好标签多选 ≥1（6 个全集）。JSONB 数组，取值同 UPPER_SNAKE。
    -- 用 JSONB 而非关联表：标签是**固定 6 值的枚举集合**、不可运营扩展、不参与本版任何 JOIN，
    -- 与 content_posts.image_urls 同一处理范式。
    tags          JSONB        NOT NULL,
    -- 🔴 DOUBLE PRECISION 而不是 NUMERIC：坐标是测量值，而 Story 1.2 的直线距离要过
    -- 三角函数（PostgreSQL 的 sin/cos/acos 收 double），存 numeric 只会让每行多一次隐式转换。
    latitude      DOUBLE PRECISION NOT NULL,
    longitude     DOUBLE PRECISION NOT NULL,
    -- 位置备注（必填）。详情页展示 + 一键复制；平台不解析它。
    address_text  VARCHAR(255) NOT NULL,
    description   VARCHAR(200),
    -- 公开桶 CDN 全 URL 列表（1–9 张，走既有上传链路）。
    -- ⚠️ 公开桶长期有效 URL，**不是签名 URL** —— 签名 URL 禁入库（NFR-5）。
    photo_urls    JSONB,
    -- 标记人（users.id）。运营冷启动数据的标记人是运营官方账号。
    created_by    BIGINT       NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- token 唯一（对外寻址的唯一入口）。
CREATE UNIQUE INDEX IF NOT EXISTS ux_places_public_token ON places (public_token);

-- 列表默认排序（无定位权限 → 按最新，FR-112.2）。部分索引只覆盖在架的行：
-- 已下架场所不进任何列表，不该占索引体积。
CREATE INDEX IF NOT EXISTS ix_places_active_recent
    ON places (created_at DESC, id DESC)
    WHERE status = 'ACTIVE';

-- 取值域守门。⚠️ 加类型 / 加标签 / 加状态时必须 DROP + ADD **重列全集**，
-- 且取值以**代码里的枚举**为唯一权威（同 ck_notifications_type 的教训）。
ALTER TABLE places DROP CONSTRAINT IF EXISTS ck_places_type;
ALTER TABLE places ADD CONSTRAINT ck_places_type CHECK (type IN (
    'CAFE', 'RESTAURANT', 'PARK', 'MALL', 'HOTEL', 'PET_SERVICE', 'OTHER'));

ALTER TABLE places DROP CONSTRAINT IF EXISTS ck_places_status;
ALTER TABLE places ADD CONSTRAINT ck_places_status CHECK (status IN ('ACTIVE', 'TAKEN_DOWN'));

-- 坐标合法区间。写进 DB 而不是只在应用层校验：这两列是 Story 1.2 范围粗筛的输入，
-- 一条越界坐标会让距离排序出现无法解释的结果，而且从列表上看不出是数据坏了。
ALTER TABLE places DROP CONSTRAINT IF EXISTS ck_places_latitude;
ALTER TABLE places ADD CONSTRAINT ck_places_latitude CHECK (latitude >= -90 AND latitude <= 90);

ALTER TABLE places DROP CONSTRAINT IF EXISTS ck_places_longitude;
ALTER TABLE places ADD CONSTRAINT ck_places_longitude
    CHECK (longitude >= -180 AND longitude <= 180);

COMMENT ON TABLE  places              IS '宠物友好场所（FR-112）；对外走 public_token，自增 id 不外露';
COMMENT ON COLUMN places.public_token IS '不可枚举对外标识（BASE62×32）；分享链接与 App 寻址只用它';
COMMENT ON COLUMN places.type         IS '场所类型（7 类单选，UPPER_SNAKE）；取值全集见 ck_places_type 与 PlaceType';
COMMENT ON COLUMN places.tags         IS '宠物友好标签 JSONB 数组（≥1，6 值全集）；取值见 PlaceTag';
COMMENT ON COLUMN places.latitude     IS '纬度（-90~90）；与 longitude 各建索引供 Story 1.2 范围粗筛';
COMMENT ON COLUMN places.longitude    IS '经度（-180~180）';
COMMENT ON COLUMN places.address_text IS '文字地址（必填位置备注）；平台不做地理编码、不校验与坐标一致 —— 纯展示 + 复制';
COMMENT ON COLUMN places.photo_urls   IS '公开桶 CDN 全 URL 列表（1–9 张）；非签名 URL（NFR-5）';
COMMENT ON COLUMN places.created_by   IS '标记人 users.id；冷启动数据为运营官方账号';
COMMENT ON COLUMN places.status       IS 'ACTIVE / TAKEN_DOWN（运营下架，AB-17A）；默认 ACTIVE';
