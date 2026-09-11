-- V1.3.0 批次 A · Story 5.3（AD-A20）：分享奖励的**渠道层**两个配置项（年龄卡渠道）。
--
-- 逐字对齐既有的身份证渠道两列（V20260825_0247）—— 同一件事（这个渠道一次发几枚、
-- 一天最多几次）在库里长同一个样子，否则两处的读取、校验、后台表单都会各写一套。
--
-- 三层结构（AD-A20）里本迁移只动**中间那层**：
--   全局层 share_reward_enabled / share_reward_monthly_cap —— **不动，共用**；
--   渠道层 本迁移的两列 —— 新增；
--   渠道账本 age_card_share_rewards —— 另一支迁移。
--
-- 🔴 两项均默认 **0 = 不发币**，与既有渠道同一姿态：功能随版本上线，但默认一分不发，
--    等运营把数配上。⚠️ 三个数（全局开关、单次枚数、日上限）**任意一个是 0 都不会发** ——
--    闸门是串联的，配的时候三个都要看。
--
-- ⚠️ 年龄卡**没有档案级去重**（决策 A-8）：同一只宠物隔几个月再生成是不同的分享物。
--    所以这里的日上限**不是冗余保险**（身份证那边因为「一个档案只发一次」而冗余），
--    它是本渠道**唯一的频次闸门**。配 0 以外的值时请当真。
ALTER TABLE pawcoin_config
    ADD COLUMN age_card_share_reward    BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN age_card_share_daily_cap INT    NOT NULL DEFAULT 0;

ALTER TABLE pawcoin_config
    ADD CONSTRAINT ck_pawcoin_age_card_share
        CHECK (age_card_share_reward >= 0 AND age_card_share_daily_cap >= 0);
