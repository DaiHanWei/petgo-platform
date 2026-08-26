-- V1.1.6 Story 18.2：分享奖励的**渠道层**两个配置项（身份证卡面渠道）。时间戳版本号（决策 E6）。
--
-- 与 18.1 的全局层（share_reward_enabled / share_reward_monthly_cap）是**两层**：
-- 全局层管「一个账号一个月最多免费拿多少」，本层管「这个渠道一次发几枚、一天最多几次」。
-- 🔴 全局总开关优先于本层 —— 关掉它，本层配成什么都不发。
--
-- 🔴 两项均默认 **0 = 不发币**（2026-08-26 产品决定），与全局层同一姿态：
--    功能随版本上线，但默认一分不发，等产品把数配上。
--    ⚠️ 三个数里**任意一个是 0 都不会发**（闸门是串联的），所以配的时候三个都要看。
--
--    ⚠️ 本渠道另有「按宠物档案去重、一个档案只发一次」的更强约束（18.2 AC4），
--    所以即便配上数，一个用户一辈子也最多拿一次 —— 日上限对本渠道是**冗余的保险**，
--    存在的意义是后续渠道接入时这一层已经就位（18.1 AC1 的原话：额度定义成全局的）。
ALTER TABLE pawcoin_config
    ADD COLUMN id_card_share_reward    BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN id_card_share_daily_cap INT    NOT NULL DEFAULT 0;

ALTER TABLE pawcoin_config
    ADD CONSTRAINT ck_pawcoin_id_card_share
        CHECK (id_card_share_reward >= 0 AND id_card_share_daily_cap >= 0);
