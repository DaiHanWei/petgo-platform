-- V1.4.0 ① Tailrent Level · Story 1.1 AC1：成长值账本核心表（架构增量 AD-1 ~ AD-4、V-1、§6.4）。
--
-- 表归属：本迁移建的 5 张表（growth_accounts / growth_ledger / growth_daily_counters / growth_rules / growth_levels）
--         全部归 ① 线（feat/1.4.0-tailrent-level）。写成长值只经 GrowthLedgerService（§5.4），其他模块只发事件。
-- 不在此建：growth_spend_tiers（Story 1.3 · V20261018_1504）、growth_recalc_jobs（Story 4.1 · V20261018_1505）。
-- 不碰：ck_notifications_type / ck_payment_intents_purpose（地基共享约束，四条线一律不改）。
--
-- PASSIVE_GROUP 伪行（V-1）：被动三项（LIKE_RECEIVED / COMMENT_RECEIVED / COLLECT_RECEIVED，cap_group = 'PASSIVE'）
--         当天合计上限存在 growth_rules 的伪行 PASSIVE_GROUP.daily_cap（初始 50，后台可改）；它 points = 0、
--         永不产生入账，只是一个可配置的数。
--
-- 小整数列一律 INT（实体 int → Hibernate int4；SMALLINT 会让 ddl-auto=validate 启动失败）。

CREATE TABLE growth_accounts (
    user_id                BIGINT PRIMARY KEY REFERENCES users (id),
    lifetime_total         BIGINT      NOT NULL DEFAULT 0 CONSTRAINT ck_growth_accounts_lifetime CHECK (lifetime_total >= 0),
    -- PRD 预留（未来可消耗余额），1.4.0 不读写。
    balance                BIGINT      NOT NULL DEFAULT 0,
    level                  INT         NOT NULL DEFAULT 1,
    -- NULL = 自动（取最高可用档）；NONE = 用户主动不戴。
    frame_choice           VARCHAR(16) NULL CONSTRAINT ck_growth_accounts_frame CHECK (frame_choice IN ('NONE', 'BASIC', 'UPGRADED', 'ANIMATED')),
    -- 仅虚拟号 / 官方号：运营设定的展示等级。
    display_level          INT         NULL,
    celebrate_from_level   INT         NULL,
    celebrate_to_level     INT         NULL,
    backfill_popup_pending BOOLEAN     NOT NULL DEFAULT false,
    version                BIGINT      NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 只追加：扣回 = 追加一条指向原流水的反向记录，永不改删原流水（注销清理除外）。不接入 ledger_entries 总账。
CREATE TABLE growth_ledger (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    rule_code         VARCHAR(32)  NOT NULL,
    delta             INT          NOT NULL CONSTRAINT ck_growth_ledger_delta CHECK (delta <> 0),
    ref_type          VARCHAR(32)  NULL,
    ref_id            VARCHAR(64)  NULL,
    pet_ids           BIGINT[]     NULL,
    idem_key          VARCHAR(160) NOT NULL UNIQUE,
    reverses_id       BIGINT       NULL REFERENCES growth_ledger (id),
    reason            VARCHAR(24)  NULL,
    note              VARCHAR(200) NULL,
    operator_admin_id BIGINT       NULL,
    batch_no          VARCHAR(32)  NULL,
    wib_date          DATE         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_growth_ledger_user_created ON growth_ledger (user_id, created_at DESC, id DESC);
CREATE INDEX idx_growth_ledger_reverses ON growth_ledger (reverses_id);
CREATE INDEX idx_growth_ledger_ref ON growth_ledger (ref_type, ref_id);
CREATE INDEX idx_growth_ledger_user_rule ON growth_ledger (user_id, rule_code);

-- 每日额度（WIB 自然日）。主动规则 counter_key = 规则代码（计次数）；被动三项共用 PASSIVE（计分值）。只增不减。
CREATE TABLE growth_daily_counters (
    user_id     BIGINT      NOT NULL,
    wib_date    DATE        NOT NULL,
    counter_key VARCHAR(32) NOT NULL,
    used        INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, wib_date, counter_key)
);

CREATE TABLE growth_rules (
    code        VARCHAR(32) PRIMARY KEY,
    points      INT         NOT NULL,
    -- 按金额比例的规则：每 unit_amount（IDR）得 points。
    unit_amount INT         NULL,
    daily_cap   INT         NULL,
    cap_group   VARCHAR(16) NULL,
    enabled     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  BIGINT      NULL
);

CREATE TABLE growth_levels (
    level     INT PRIMARY KEY,
    threshold BIGINT      NOT NULL,
    tier      VARCHAR(16) NOT NULL CONSTRAINT ck_growth_levels_tier CHECK (tier IN ('NEWBIE', 'BUDDY', 'GUARDIAN', 'STAR'))
);

-- 19 条规则（PRD §3.1）。
INSERT INTO growth_rules (code, points, unit_amount, daily_cap, cap_group, enabled) VALUES
    ('DAILY_LOGIN',      3,  NULL,  1,    NULL,      true),
    ('POST_PUBLISHED',   5,  NULL,  3,    NULL,      true),
    ('COMMENT_POSTED',   2,  NULL,  5,    NULL,      true),
    ('LIKE_GIVEN',       1,  NULL,  5,    NULL,      true),
    ('PLACE_CHECKIN',    5,  NULL,  3,    NULL,      true),
    ('LIKE_RECEIVED',    1,  NULL,  NULL, 'PASSIVE', true),
    ('COMMENT_RECEIVED', 3,  NULL,  NULL, 'PASSIVE', true),
    ('COLLECT_RECEIVED', 2,  NULL,  NULL, 'PASSIVE', true),
    ('PASSIVE_GROUP',    0,  NULL,  50,   NULL,      true),
    ('MILESTONE_S',      5,  NULL,  NULL, NULL,      true),
    ('MILESTONE_M',      15, NULL,  NULL, NULL,      true),
    ('MILESTONE_L',      25, NULL,  NULL, NULL,      true),
    ('OPS_CAMPAIGN',     0,  NULL,  NULL, NULL,      true),
    ('GIFT_SENT',        5,  1000,  NULL, NULL,      true),
    ('GIFT_RECEIVED',    4,  1000,  NULL, NULL,      true),
    ('SPEND_DIGITAL',    5,  1000,  NULL, NULL,      true),
    ('SPEND_SHOP',       5,  10000, NULL, NULL,      true),
    ('LAUNCH_BACKFILL',  0,  NULL,  NULL, NULL,      true),
    ('ADMIN_ADJUST',     0,  NULL,  NULL, NULL,      true);

-- 等级曲线 Lv1–20（PRD）。门槛严格递增、段位单调由服务层校验；只能末尾追加。
INSERT INTO growth_levels (level, threshold, tier) VALUES
    (1,  0,     'NEWBIE'),
    (2,  70,    'NEWBIE'),
    (3,  130,   'NEWBIE'),
    (4,  250,   'NEWBIE'),
    (5,  500,   'BUDDY'),
    (6,  800,   'BUDDY'),
    (7,  1250,  'BUDDY'),
    (8,  1800,  'BUDDY'),
    (9,  2500,  'BUDDY'),
    (10, 3300,  'GUARDIAN'),
    (11, 4500,  'GUARDIAN'),
    (12, 5800,  'GUARDIAN'),
    (13, 7400,  'GUARDIAN'),
    (14, 9500,  'GUARDIAN'),
    (15, 12400, 'STAR'),
    (16, 16500, 'STAR'),
    (17, 21500, 'STAR'),
    (18, 27200, 'STAR'),
    (19, 33800, 'STAR'),
    (20, 41300, 'STAR');
