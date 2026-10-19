-- V1.4.0 ① Tailrent Level · Story 1.3 AC1：消费返档位（架构 AD-4 / AD-8、§6.4）。表归属 ① 线。
--
-- 每个 PaymentPurpose 一行（11 值，与地基 ck_payment_intents_purpose 一一对应；本迁移不碰该约束）。
-- 档位 → 规则：DIGITAL → SPEND_DIGITAL、TOKO → SPEND_SHOP、NONE / GIFT → 不入账。
-- 未登记的用途按 DIGITAL 处理（PRD「新上线的付费功能默认归入数字服务档」，在服务层兜底）。
-- locked = true 的行后台只读（Story 4.1）：充值不返、送礼走送礼规则。

CREATE TABLE growth_spend_tiers (
    purpose    VARCHAR(16) PRIMARY KEY,
    tier       VARCHAR(16) NOT NULL CONSTRAINT ck_growth_spend_tiers_tier CHECK (tier IN ('DIGITAL', 'TOKO', 'NONE', 'GIFT')),
    locked     BOOLEAN     NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by BIGINT      NULL
);

INSERT INTO growth_spend_tiers (purpose, tier, locked) VALUES
    ('VET_CONSULT',   'DIGITAL', false),
    ('AI_UNLOCK',     'DIGITAL', false),
    ('ID_HD',         'DIGITAL', false),
    ('TAILSONALITY',  'DIGITAL', false),
    ('PASSPORT_SNAP', 'DIGITAL', false),
    ('BOARDING_PASS', 'DIGITAL', false),
    ('TS_MATCH',      'DIGITAL', false),
    ('PET_SLOT_RENT', 'DIGITAL', false),
    ('SHOP_ORDER',    'TOKO',    false),
    ('PAWCOIN_TOPUP', 'NONE',    true),
    ('GIFT',          'GIFT',    true);
