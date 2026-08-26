-- V1.1.6 Story 18.1 · AC1/AC6：分享奖励的**全局层**两个配置项挂既有 PawCoin 配置组
-- （复用 PAWCOIN 的 diff 审计与实时下发，不新增配置类型）。时间戳版本号（决策 E6）。
--
-- 🔴 share_reward_enabled 是**总开关**：发现被刷要能立刻全线关掉。
--    它必须比任何渠道层配置优先 —— 这是它存在的唯一理由。
--
-- 🔴 share_reward_monthly_cap 默认 **0 = 不发币**（2026-08-26 产品决定）。
--    也就是说功能随版本上线，但**默认一分不发**，等产品在后台把数配上才开始发。
--
--    ⚠️ 为什么不预置一个"合理值"：这个数的正确取值取决于它与 HD 解锁价的**比值**
--    （攒满几个月能换一次高清 = 白嫖速度），而那个比值正是 OQ-C1 要产品看着定的东西。
--    预置一个数意味着「没人做过决定，但线上已经在按它发币了」—— 那比不发更糟。
--    18-3 的配置页会同屏把这个比值算出来，产品照着填即可。
--
--    🛡 0 是**合法取值**，不是"未配置"的错误态：闸门会直接短路、不发币、不提示，
--    分享功能本身照常可用。
--
-- 🛡 渠道层的 id_card_share_reward / id_card_share_daily_cap 不在本迁移 —— 归 18-2。
ALTER TABLE pawcoin_config
    ADD COLUMN share_reward_enabled     BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN share_reward_monthly_cap BIGINT  NOT NULL DEFAULT 0;

ALTER TABLE pawcoin_config
    ADD CONSTRAINT ck_pawcoin_share_reward_cap CHECK (share_reward_monthly_cap >= 0);
