-- V1.5.0 健康记录提醒 Story 1.1（架构增量 health-reminder §1.2，PRD FR-162）：宠物档案健康状态字段。
--
-- cat_in_home      只对 DOG 有意义；null = 没填（按「否」处理，不出 permethrin 警示）。
-- pregnant_nursing 只对 FEMALE 或 sex 为空有意义；sex=MALE 时读取一律当 false。
-- pregnant_set_at  最近一次被设为 true 的时刻（设为 true 时刷新、设为 false 时清空）；
--                  WIB 日期 + 4 个日历月起自动清除（Story 1.3 PregnancyFlag / 2.3 日跑）。
-- sex_set_at       sex 从空首次变为非空的时刻（只记第一次）；存量 sex 非空的宠物保持空（视作建档时就有）。
-- 不回填；存量 neuter_status 不动（架构 Z-9：null = 没填、UNKNOWN = 不确定）。

ALTER TABLE pet_profiles
    ADD COLUMN cat_in_home      BOOLEAN,
    ADD COLUMN pregnant_nursing BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pregnant_set_at  TIMESTAMPTZ,
    ADD COLUMN sex_set_at       TIMESTAMPTZ;
