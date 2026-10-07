-- V1.3.2 batch-a Story 4.5（AD-13）：分享奖励的**渠道层**配置项 —— 两个新渠道各两列。
--
-- 逐字对齐既有身份证（V20260825_0247）/ 年龄卡（V20260911_0642）两组：同一件事（这个渠道一次发几枚、
-- 一天最多几次）在库里长同一个样子，读取、校验、后台表单才不会各写一套。
--
--   tailsonality_share_*  —— Tailsonality 结果卡 / 配型卡（去重：宠物 × 卡类型，RESULT / MATCH 各一次）
--   passport_share_*      —— 护照卡 / 登机牌卡（去重：宠物 × 卡类型，PAGE / BOARDING 各一次）
--
-- 全局层 share_reward_enabled / share_reward_monthly_cap **不动、共用**。
-- 🔴 四项均默认 **0 = 不发币**（RC-3 由运营配）。闸门串联：总开关、月度上限、单次枚数、日上限任一为 0 都不发。
ALTER TABLE pawcoin_config
    ADD COLUMN tailsonality_share_reward    BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tailsonality_share_daily_cap INT    NOT NULL DEFAULT 0,
    ADD COLUMN passport_share_reward        BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN passport_share_daily_cap     INT    NOT NULL DEFAULT 0;

ALTER TABLE pawcoin_config
    ADD CONSTRAINT ck_pawcoin_tailsonality_share
        CHECK (tailsonality_share_reward >= 0 AND tailsonality_share_daily_cap >= 0);

ALTER TABLE pawcoin_config
    ADD CONSTRAINT ck_pawcoin_passport_share
        CHECK (passport_share_reward >= 0 AND passport_share_daily_cap >= 0);

COMMENT ON COLUMN pawcoin_config.tailsonality_share_reward IS
    'Tailsonality 结果卡 / 配型卡分享一次发几枚（0 = 不发）；每只宠物每卡类型只发一次';
COMMENT ON COLUMN pawcoin_config.tailsonality_share_daily_cap IS
    'Tailsonality 分享奖励每用户每 WIB 日最多次数（0 = 不发）';
COMMENT ON COLUMN pawcoin_config.passport_share_reward IS
    '护照卡 / 登机牌卡分享一次发几枚（0 = 不发）；每只宠物每卡类型只发一次（登机牌整体一个类型）';
COMMENT ON COLUMN pawcoin_config.passport_share_daily_cap IS
    '护照 / 登机牌分享奖励每用户每 WIB 日最多次数（0 = 不发）';
