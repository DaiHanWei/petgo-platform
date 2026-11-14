-- V1.5.0 onboarding-recall Story 2.2 · FR-177 · OR-6：Lulus Pemula 改账号级里程碑 flag。
--
-- 🔴 ck_user_milestone_flags_key 是 user_milestone_flags 的自有约束（只有本线改）：DROP + ADD **重列全集**。
--    值取当前树里最后一条定义它的迁移原文（V20261104_2030__milestone_account_flags_and_roster.sql 的 2 值）+ LULUS_PEMULA。
-- celebrated_at：账号级「已庆祝」（UTC）。只对 LULUS_PEMULA 有业务含义（无宠物账号补弹的唯一判据）；
--    另两个 flag 一律视为已庆祝（1.4.2 规则：无宠物不通知不庆祝，有宠物的走宠物行 celebrated_at）。
-- 幂等可重放（DROP IF EXISTS / ADD COLUMN IF NOT EXISTS / UPDATE 只补 NULL）。
ALTER TABLE user_milestone_flags DROP CONSTRAINT IF EXISTS ck_user_milestone_flags_key;
ALTER TABLE user_milestone_flags ADD CONSTRAINT ck_user_milestone_flags_key
    CHECK (flag_key IN ('FIRST_FOLLOW_USER', 'FIRST_COLLECT_CONTENT', 'LULUS_PEMULA'));

ALTER TABLE user_milestone_flags ADD COLUMN IF NOT EXISTS celebrated_at TIMESTAMPTZ;

UPDATE user_milestone_flags SET celebrated_at = occurred_at
 WHERE celebrated_at IS NULL AND flag_key IN ('FIRST_FOLLOW_USER', 'FIRST_COLLECT_CONTENT');

COMMENT ON COLUMN user_milestone_flags.celebrated_at IS
    '账号级已庆祝时刻（UTC，V1.5.0）：只对 LULUS_PEMULA 有业务含义；另两个 flag 恒等于 occurred_at';
