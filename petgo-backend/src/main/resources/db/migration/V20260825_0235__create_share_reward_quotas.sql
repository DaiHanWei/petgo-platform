-- V1.1.6 Story 18.1：分享奖励的**月度额度记账**。时间戳版本号（决策 E6）。
--
-- 一行 = 某账号在某个 WIB 自然月里通过分享类行为已获得的 PawCoin 总量。
--
-- 🔴 period 用 **WIB（Asia/Jakarta）** 的 YYYY-MM，刻意偏离项目全局 UTC 惯例 ——
--    与 user_monthly_free_quota 完全同一口径（Story 2.1 已经定死）。
--    UTC 月初 = WIB 月初早上 7 点，按 UTC 切会在月初那天错发一整批。**勿按 UTC 惯例「订正」**。
--
-- ⚠️ 换月自然产生新 period 行 = **惰性重置**，不需要任何 @Scheduled。
--
-- 🛡 额度按「所有分享类行为」合一（AC1）：本表**没有渠道列** ——
--    加了渠道列就变成按渠道各算一份，等于上限乘以渠道数。
--    渠道自己的日上限（id_card_share_daily_cap 等）是另一层，归 18-2。
CREATE TABLE share_reward_quotas (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    -- WIB 自然月，YYYY-MM。
    period         VARCHAR(7)  NOT NULL,
    -- 本月已通过分享获得的 PawCoin 累计量。
    granted_coins  BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 🛡 并发不超发的支点：唯一约束让 ON CONFLICT DO NOTHING 能幂等建行，
    --    之后的条件 UPDATE 靠单行行锁串行化。
    CONSTRAINT uq_share_reward_quotas UNIQUE (user_id, period),
    CONSTRAINT ck_share_reward_quotas_nonneg CHECK (granted_coins >= 0)
);
