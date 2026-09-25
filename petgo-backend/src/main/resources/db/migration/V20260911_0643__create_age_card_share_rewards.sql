-- V1.3.0 批次 A · Story 5.3（AD-A20 · 决策 A-8）：年龄卡分享奖励的**去重与日限记账**。
--
-- 🔴 **为什么必须新建一张表，而不是塞进 id_card_share_rewards**：
--    那张表的去重键是 `pet_profile_id` 且**带唯一约束**（一个宠物档案一辈子只发一次）。
--    年龄卡没有卡实体，也**不该按档案唯一** —— 同一只宠物隔几个月再生成是不同的分享物
--    （决策 A-8）。塞进去要么撞唯一键、要么就得把那条约束拆掉，而那条约束正是
--    身份证渠道的幂等本身。两个渠道的去重语义不同，就该是两张表。
--
-- 🔴 **本表没有 pet_profile_id，更没有它的唯一约束**（AC2）。
--    去重键是**幂等键**：一次分享动作一行。客户端为每次分享生成一个键，
--    重复上报撞唯一键 ⇒ 结构上不可能重复发。
--    ⚠️ 「先查有没有再插」是典型的并发双发（两个请求都查到"没有"），本表不依赖它。
--
-- 日限记账用 (user_id, share_date) 累计行数；share_date 是 **WIB 当地日期**。
-- 🔴 **资金口径，不是体验问题**：按 UTC 切日会让「今天」在 WIB 早上 7 点才换，
--    运营配「3 次/日」时用户在 06:00–07:00 能领双份。日期由服务端按
--    IdCardShareRewardService.shareDateOf 那一个唯一实现算出来，本表只存结果。
--
-- 注销级联（CLAUDE.md 安全攸关 D1）：与同胞表 id_card_share_rewards **同口径物理删除**
-- （随 PawCoin 钱包/流水一起），入口在 ShareRewardDeletionService。
-- ⚠️ 同一个用户的两种分享留痕在注销后表现必须一致，不能一种消失、一种留着。
CREATE TABLE age_card_share_rewards (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    coins           BIGINT       NOT NULL,
    -- WIB 当地日期，用于日上限判定。
    share_date      DATE         NOT NULL,
    -- 🛡 一次分享动作一行：这一条就是 AC8 的幂等。
    idempotency_key VARCHAR(120) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_age_card_share_rewards_idem UNIQUE (idempotency_key),
    CONSTRAINT fk_age_card_share_rewards_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_age_card_share_rewards_user_date ON age_card_share_rewards (user_id, share_date);

COMMENT ON TABLE age_card_share_rewards IS
    '年龄卡分享奖励发放留痕。去重按幂等键（一次分享一行），刻意不按宠物档案（决策 A-8）';
