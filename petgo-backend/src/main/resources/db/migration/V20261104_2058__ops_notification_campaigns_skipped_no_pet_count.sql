-- V1.4.2 social-topic · Story 7.1（AB-37 · 决策日志 R-3 · 架构 D-16 / P-8 迁移 ⑧）
-- 只记「用了宠物昵称变量、因没有宠物被排除」的人数；原 skipped_count（IDS 模式无效 id 个数）口径不变。
-- 新增 ALTER，不改 V20261012_1107 / V20261015_1209（均已应用于 stag / prod）。
ALTER TABLE ops_notification_campaigns
    ADD COLUMN skipped_no_pet_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ops_notification_campaigns
    ADD CONSTRAINT ck_ops_notification_campaigns_skipped_no_pet_count CHECK (skipped_no_pet_count >= 0);
COMMENT ON COLUMN ops_notification_campaigns.skipped_no_pet_count IS
    'AB-37：标题 / 正文任一语言用了 {{petName}} 时，因没有宠物被排除的人数（所有人群模式）；其余为 0';
