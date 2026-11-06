-- V1.4.2 shop-admin Story 3.2 · AD-8 / 决策 O-1：分享奖励总开关「默认改为关」。
-- 🔴 只改列默认值（影响今后新插入的行），不改线上已有那一行的值 —— 上线前由运营自己看开关要不要关。
--    本文件不得出现任何数据修改语句（ShareRewardDefaultMigrationTest 守门）。
ALTER TABLE pawcoin_config ALTER COLUMN share_reward_enabled SET DEFAULT false;
