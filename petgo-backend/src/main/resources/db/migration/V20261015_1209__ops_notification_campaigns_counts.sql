-- V1.3.4 AB-31 后台「系统通知发送」返工（admin-doc-rework-v1.3.4.md §4）：发送记录列表「触达人数」三种口径所需的计数列。
-- 新增 ALTER，不改 V20261012_1106 / 1107（已应用于 stag）。三列均可空：存量行没有这些值，列表显示「—」。
--   estimated_count：后台新建时按当前条件算出的预估人数（待发送行显示「预计 N」）；
--   planned_count  ：发送核心开始发送、按实际发送时刻解析出的人数（发送中行显示「已写入 / planned」的分母）；
--   ids_source     ：指定用户模式的 ID 来源（PASTE 粘贴 / CSV 上传），列表人群列显示「指定用户（CSV）」。
ALTER TABLE ops_notification_campaigns
    ADD COLUMN estimated_count INT NULL,
    ADD COLUMN planned_count INT NULL,
    ADD COLUMN ids_source VARCHAR(8) NULL;

ALTER TABLE ops_notification_campaigns
    ADD CONSTRAINT ck_ops_notification_campaigns_estimated_count CHECK (estimated_count IS NULL OR estimated_count >= 0),
    ADD CONSTRAINT ck_ops_notification_campaigns_planned_count CHECK (planned_count IS NULL OR planned_count >= 0),
    ADD CONSTRAINT ck_ops_notification_campaigns_ids_source CHECK (ids_source IS NULL OR ids_source IN ('PASTE', 'CSV'));
