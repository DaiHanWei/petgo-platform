-- 工作线：V1.1.6（本分支迁移从 V105 起，V101–V104 归 hex/v1.1.4）
--
-- V1.1.6 Story 4.1 · FR-68：顶置坑位配置表。
--
-- 设计要点（架构 AD-8 / AD-9）：
--   1. 🛡 **坑位是一个字段，不是把首页写死**（AD-8 Rule 5）。本版本只有一个坑位（HOME_FEED），
--      但下游 V1.2.0 的话题页坑位要直接复用本机制 —— 新增坑位只需写入一个新的 slot 取值，
--      **不改表结构、不改代码结构**。
--      ⚠️ 刻意**不建独立的坑位表**：坑位本身除主键与名字外没有任何属性，
--      建了就得连带一套后台增删改查，属于为"以后可能"付现在的钱。
--      哪天坑位要带属性（展示条数 / 样式 / 开关）再拆，拆的成本与现在一样。
--      同理 slot **不做 CHECK 约束** —— 约束住取值就等于把新增坑位变成一次迁移，
--      与 AD-8 "新增坑位不需重构"直接冲突。
--
--   2. 🛡 **不落状态列、不建定时扫描器**（AD-9 Rule 2）。「生效中」一律**查询时**按
--      当前时刻是否落在 [starts_at, ends_at) 内判定。表里没有 status 列，这是刻意的。
--
--   3. **两类可顶置对象互斥**（FR-68）：
--      (a) CONTENT —— 顶置一篇已发布的公开内容，带 content_id；
--      (b) PROMO   —— 运营直接配的推广卡片，不对应任何真实帖子，自带图片 / 标题 / 跳转目标。
--      由 CHECK 约束保证"该有的有、不该有的没有"，防止应用层漏判写出半截数据。
--
--   4. **提前结束单列 terminated_at，不覆盖 ends_at**：顶置内容在生效期间被下架时
--      "视为提前到达结束时间"。最省事的实现是直接改 ends_at，但那样运营只会看到
--      "这条 14:32 结束了"，**无从知道是排期到点还是被下架带走的**，排期意图的记录也没了。
--      生效判定取两者中较早的那个；写入时保证 terminated_at <= ends_at，
--      因此 SQL 侧可以直接用 COALESCE(terminated_at, ends_at) 当作生效结束时刻。
--
--   5. 时间一律 timestamptz、UTC 绝对时刻。运营配的是 WIB 墙上时间，**入库前换算**，
--      判定时不再按客户端时区二次换算（AD-9 Rule 4）。

CREATE TABLE content_pins (
    id              BIGSERIAL PRIMARY KEY,
    slot            VARCHAR(32)  NOT NULL,
    object_type     VARCHAR(16)  NOT NULL,
    content_id      BIGINT,
    promo_image_url VARCHAR(512),
    promo_title     VARCHAR(120),
    promo_link_url  VARCHAR(512),
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    terminated_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 排期本身必须是个正区间（左闭右开，等长零区间无意义）。
ALTER TABLE content_pins
    ADD CONSTRAINT ck_content_pins_window CHECK (ends_at > starts_at);

-- 提前结束不得晚于排期结束 —— 这条不变式让 SQL 侧可以直接用
-- COALESCE(terminated_at, ends_at) 当生效结束时刻，无需 LEAST()。
ALTER TABLE content_pins
    ADD CONSTRAINT ck_content_pins_terminated CHECK (terminated_at IS NULL OR terminated_at <= ends_at);

-- 两类对象互斥：CONTENT 有内容编号、无卡片字段；PROMO 反之（跳转目标可空 = 纯展示卡）。
ALTER TABLE content_pins
    ADD CONSTRAINT ck_content_pins_object CHECK (
        (object_type = 'CONTENT'
             AND content_id IS NOT NULL
             AND promo_image_url IS NULL AND promo_title IS NULL AND promo_link_url IS NULL)
        OR (object_type = 'PROMO'
             AND content_id IS NULL
             AND promo_image_url IS NOT NULL AND promo_title IS NOT NULL)
    );

-- 取数：按坑位 + 时间窗筛「当前生效」。slot 前导（等值），其后按窗口范围扫。
CREATE INDEX idx_content_pins_slot_window ON content_pins (slot, starts_at, ends_at);

-- 下架联动：按内容编号反查要提前结束的配置。仅 CONTENT 类有值，故用部分索引。
CREATE INDEX idx_content_pins_content_id ON content_pins (content_id) WHERE content_id IS NOT NULL;

COMMENT ON TABLE content_pins IS
    '顶置坑位排期（FR-68）。生效与否一律查询时按 [starts_at, ends_at) 判定，无状态列、无扫描器（AD-9）。';
COMMENT ON COLUMN content_pins.slot IS
    '坑位标识（UPPER_SNAKE，本版本仅 HOME_FEED）。刻意不加 CHECK：约束取值 = 新增坑位要走迁移，与 AD-8「新增坑位不需重构」冲突。';
COMMENT ON COLUMN content_pins.terminated_at IS
    '提前结束时刻（顶置内容被下架时写入）。不覆盖 ends_at，以便区分「排期到点」与「被下架带走」。';
