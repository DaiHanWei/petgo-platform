-- V1.3.2 batch-a Story 4.5（AD-13 / AD-17）：两个新渠道的分享奖励**去重与日限账本**，结构同构。
--
-- 🔴 去重粒度 = **宠物档案 × 卡类型**（UNIQUE (pet_profile_id, card_type)）：
--    按卡实例计会让「打卡数 = 奖励数」「重测 → 分享 → 拿币」无限循环。所以接口只收卡类型，
--    不收结果 token / 场所 token；登机牌**整体一个卡类型**（BOARDING），不按张计。
--
-- pet_profile_id 可空 + ON DELETE SET NULL（AD-17）：删档后留痕行（资金发放流水）保留、置空；
--    PostgreSQL 唯一约束对 NULL 不判重 → 置空后的多条历史行互不相撞，活着的宠物仍被约束挡住。
--    （重建宠物后可再领一次 —— 已接受的洞，AD-17 Deferred 第 5 条。）
-- 注销：与同胞表 id_card_share_rewards / age_card_share_rewards **同口径物理删除**
--    （随 PawCoin 钱包 / 流水一起），入口 ShareRewardDeletionService。
--
-- share_date 是 **WIB 当地日期**（IdCardShareRewardService.shareDateOf 唯一实现算出，本表只存结果）。
CREATE TABLE tailsonality_share_rewards (
    id             BIGSERIAL    PRIMARY KEY,
    pet_profile_id BIGINT       NULL,
    user_id        BIGINT       NOT NULL,
    card_type      VARCHAR(16)  NOT NULL,
    coins          BIGINT       NOT NULL,
    share_date     DATE         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_tailsonality_share_rewards_card_type CHECK (card_type IN ('RESULT', 'MATCH')),
    CONSTRAINT uq_tailsonality_share_rewards_pet_card UNIQUE (pet_profile_id, card_type),
    CONSTRAINT fk_tailsonality_share_rewards_pet FOREIGN KEY (pet_profile_id)
        REFERENCES pet_profiles (id) ON DELETE SET NULL,
    CONSTRAINT fk_tailsonality_share_rewards_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_tailsonality_share_rewards_user_date ON tailsonality_share_rewards (user_id, share_date);

COMMENT ON TABLE tailsonality_share_rewards IS
    'Tailsonality 结果卡 / 配型卡分享奖励发放留痕。去重 = 宠物 × 卡类型；删档置空、注销物理删除';
COMMENT ON COLUMN tailsonality_share_rewards.pet_profile_id IS '领奖时的宠物；删档置 NULL（留痕保留）';
COMMENT ON COLUMN tailsonality_share_rewards.card_type IS 'RESULT = 结果卡；MATCH = 配型卡';
COMMENT ON COLUMN tailsonality_share_rewards.share_date IS 'WIB 当地日期，用于日上限判定';

CREATE TABLE passport_share_rewards (
    id             BIGSERIAL    PRIMARY KEY,
    pet_profile_id BIGINT       NULL,
    user_id        BIGINT       NOT NULL,
    card_type      VARCHAR(16)  NOT NULL,
    coins          BIGINT       NOT NULL,
    share_date     DATE         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_passport_share_rewards_card_type CHECK (card_type IN ('PAGE', 'BOARDING')),
    CONSTRAINT uq_passport_share_rewards_pet_card UNIQUE (pet_profile_id, card_type),
    CONSTRAINT fk_passport_share_rewards_pet FOREIGN KEY (pet_profile_id)
        REFERENCES pet_profiles (id) ON DELETE SET NULL,
    CONSTRAINT fk_passport_share_rewards_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_passport_share_rewards_user_date ON passport_share_rewards (user_id, share_date);

COMMENT ON TABLE passport_share_rewards IS
    '护照卡 / 登机牌卡分享奖励发放留痕。去重 = 宠物 × 卡类型（登机牌整体一个类型）；删档置空、注销物理删除';
COMMENT ON COLUMN passport_share_rewards.pet_profile_id IS '领奖时的宠物；删档置 NULL（留痕保留）';
COMMENT ON COLUMN passport_share_rewards.card_type IS 'PAGE = 护照卡；BOARDING = 登机牌卡（不按张计）';
COMMENT ON COLUMN passport_share_rewards.share_date IS 'WIB 当地日期，用于日上限判定';
