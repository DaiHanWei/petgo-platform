-- Story 9.2（AB-8A/8F/6A/6B）：定价与 PawCoin 运营配置外化。号 V78 单调追加（当前 max V77；架构旧稿 V51 作废，决策 E2）。
--
-- 把散落 env 的计费参数外化为 DB 可配 + 变更留痕。**种子值 = 现网 env 默认**（行为零变化）；
-- 若 prod 曾用自定义 env，部署后由运营在后台改回。成交价/分成/额度判定已在各订单落快照 → 改配置只影响后续。
--
-- 资金护栏：premium_rate∈[0,50]、vet_share_rate∈[0,100]、monthly_free_quota∈[0,35]（表级 CHECK 兜底，
-- 业务层再校验）；充值档位保底 ≥1 启用（跨行不变式，业务层强校验）。写操作记 config_change_logs + 审计哈希链。

-- 定价配置（单行 id=1）——AB-8A/8F
CREATE TABLE pricing_config (
    id                    BIGINT      PRIMARY KEY,
    vet_consult_price     BIGINT      NOT NULL,          -- 兽医单次咨询价 IDR
    vet_share_rate        INTEGER     NOT NULL,          -- 兽医分成 %（到手 = price*rate/100）
    ai_unlock_price       BIGINT      NOT NULL,          -- AI 详情付费解锁价 IDR
    id_hd_download_price   BIGINT     NOT NULL,          -- 身份证高清图下载价 IDR
    monthly_free_quota    INTEGER     NOT NULL,          -- 每月免费解锁 AI 详情次数
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_pricing_config_singleton CHECK (id = 1),
    CONSTRAINT ck_pricing_vet_share_rate  CHECK (vet_share_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_pricing_free_quota      CHECK (monthly_free_quota BETWEEN 0 AND 35),
    CONSTRAINT ck_pricing_nonneg          CHECK (vet_consult_price >= 0 AND ai_unlock_price >= 0 AND id_hd_download_price >= 0)
);
INSERT INTO pricing_config (id, vet_consult_price, vet_share_rate, ai_unlock_price, id_hd_download_price, monthly_free_quota)
VALUES (1, 50000, 60, 10000, 5000, 1);

-- PawCoin 配置（单行 id=1）——AB-6A（退款转币溢价）/ AB-6C（充值暂停）
CREATE TABLE pawcoin_config (
    id            BIGINT      PRIMARY KEY,
    premium_rate  INTEGER     NOT NULL,                  -- 退款转 PawCoin 溢价 %（仅「未交付+转币」分支用）
    topup_paused  BOOLEAN     NOT NULL,                  -- 充值暂停（浮存门槛 AB-6C）
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_pawcoin_config_singleton CHECK (id = 1),
    CONSTRAINT ck_pawcoin_premium_rate     CHECK (premium_rate BETWEEN 0 AND 50)
);
INSERT INTO pawcoin_config (id, premium_rate, topup_paused) VALUES (1, 0, false);

-- 充值档位（多行）——AB-6B。tier_key 稳定对外标识；保底 ≥1 启用（业务层强校验）。
CREATE TABLE pawcoin_topup_tiers (
    id          BIGSERIAL   PRIMARY KEY,
    tier_key    VARCHAR(16) NOT NULL,                    -- 对外档位标识（10k/25k…）
    amount_idr  BIGINT      NOT NULL,                    -- 充值金额 IDR（=到账 koin，1:1）
    enabled     BOOLEAN     NOT NULL DEFAULT true,
    sort_order  INT         NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pawcoin_topup_tiers_key UNIQUE (tier_key),
    CONSTRAINT ck_pawcoin_topup_amount    CHECK (amount_idr > 0)
);
INSERT INTO pawcoin_topup_tiers (tier_key, amount_idr, enabled, sort_order) VALUES
    ('10k',  10000,  true, 1),
    ('25k',  25000,  true, 2),
    ('50k',  50000,  true, 3),
    ('100k', 100000, true, 4);

-- 配置变更日志（append-only；审计哈希链另经 AdminAuditService）
CREATE TABLE config_change_logs (
    id          BIGSERIAL   PRIMARY KEY,
    config_type VARCHAR(24) NOT NULL,                    -- PRICING / PAWCOIN / TOPUP_TIER
    field       VARCHAR(48) NOT NULL,                    -- 变更字段（或档位 key）
    old_value   VARCHAR(64),
    new_value   VARCHAR(64),
    changed_by  BIGINT      NOT NULL,                    -- admin_accounts.id
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_config_change_type CHECK (config_type IN ('PRICING', 'PAWCOIN', 'TOPUP_TIER'))
);
CREATE INDEX ix_config_change_logs_at ON config_change_logs (changed_at DESC);
