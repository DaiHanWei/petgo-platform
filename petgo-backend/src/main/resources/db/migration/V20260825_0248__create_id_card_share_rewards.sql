-- V1.1.6 Story 18.2：身份证卡面分享奖励的**去重与日限记账**。时间戳版本号（决策 E6）。
--
-- 🔴 去重键是 **pet_profile_id**，不是 card_id（AC4）。
--    createCard() 无数量限制且不要求档案 ⇒ 卡可无限建 ⇒ **按卡去重等于无去重**。
--    补充 PRD 的 A-C1「按卡去重」假设已废弃。
--
-- 🛡 UNIQUE(pet_profile_id) 让「一个档案只发一次」在**约束层面**成立：
--    并发两次分享，第二次撞唯一键 ⇒ 结构上不可能重复发。
--    不靠「先查有没有再插」——那是典型的并发双发。
--
-- 日限记账用 (user_id, share_date) 一行累计次数；share_date 是 **WIB 当地日期**
-- （与月度额度的 WIB 口径一致；UTC 切日会让「今天」在 WIB 早上 7 点才换）。
CREATE TABLE id_card_share_rewards (
    id             BIGSERIAL   PRIMARY KEY,
    pet_profile_id BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    card_id        BIGINT      NOT NULL,
    coins          BIGINT      NOT NULL,
    -- WIB 当地日期，用于日上限判定。
    share_date     DATE        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 🛡 这一条就是 AC4 与 AC7 的幂等：一个档案一行，永远。
    CONSTRAINT uq_id_card_share_rewards_profile UNIQUE (pet_profile_id)
);

CREATE INDEX ix_id_card_share_rewards_user_date ON id_card_share_rewards (user_id, share_date);
