-- V1.1.6 Story 18.1 · AC1/AC6：分享奖励的**全局层**两个配置项挂既有 PawCoin 配置组
-- （复用 PAWCOIN 的 diff 审计与实时下发，不新增配置类型）。时间戳版本号（决策 E6）。
--
-- 🔴 share_reward_enabled 是**总开关**：发现被刷要能立刻全线关掉。
--    它必须比任何渠道层配置优先 —— 这是它存在的唯一理由。
--
-- ⚠️ share_reward_monthly_cap 的默认 2000 是**待产品确认的取值**：
--    1 PawCoin = 1 IDR（充值档位 1:1），HD 解锁价种子值 5000 ⇒ 攒满 2.5 个月额度换一次 HD 解锁。
--    这个比值正是 OQ-C1 要运营看见的东西（18-3 的配置页要同屏算出来）。
--    在那之前先给一个不至于白嫖的数，而不是留 0（0 等于功能没上）或留一个大数。
--
-- 🛡 渠道层的 id_card_share_reward / id_card_share_daily_cap 不在本迁移 —— 归 18-2。
ALTER TABLE pawcoin_config
    ADD COLUMN share_reward_enabled     BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN share_reward_monthly_cap BIGINT  NOT NULL DEFAULT 2000;

ALTER TABLE pawcoin_config
    ADD CONSTRAINT ck_pawcoin_share_reward_cap CHECK (share_reward_monthly_cap >= 0);
