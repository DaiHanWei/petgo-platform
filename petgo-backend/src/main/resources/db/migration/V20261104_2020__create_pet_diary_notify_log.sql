-- V1.4.2 social-topic · Story 1.2 · 迁移 ④（架构 D-5 / P-8 ④）：追更日提醒去重日志。
--
-- 一行 = 某追更者在某 WIB 日已收到过某只宠物的新 Diary 提醒。
-- 写入一律 INSERT … ON CONFLICT DO NOTHING，受影响行数 = 1 才发通知
-- （同一追更者 × 同一宠物 × 同一 WIB 日最多 1 条，PetDiaryNotifyLogDao#tryInsert）。
--
-- 不加 FK：只是去重日志，不含 PII（只有 id 与日期）；删宠 / 注销后的残留行由每日清理任务
-- （PetDiaryNotifyLogCleanupJob，删 wib_date < 今天(WIB) − 30 天）带走，也不阻断测试库 deleteAll。
-- 无 JPA 实体（JdbcTemplate 读写），与 ddl-auto=validate 无关。新表，不回填。

CREATE TABLE pet_diary_notify_log (
    follower_id     BIGINT      NOT NULL,
    pet_profile_id  BIGINT      NOT NULL,
    wib_date        DATE        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_pet_diary_notify_log PRIMARY KEY (follower_id, pet_profile_id, wib_date)
);

-- 清理任务按日期范围删。
CREATE INDEX idx_pet_diary_notify_log_date ON pet_diary_notify_log (wib_date);
