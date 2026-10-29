-- V1.4.1 运营推送人群（2026-10-29）：开屏弹窗加四维人群条件（等级档 / 宠物类型 / 活跃档 / 付费）。
-- JSON 文本，形状见 com.tailtopia.ops.audience.AudienceFilters；NULL = 不筛（存量弹窗行为不变）。
-- 设了任一条件的弹窗只对登录用户下发（游客没有等级 / 宠物 / 付费数据可判）。
ALTER TABLE ops_popups ADD COLUMN audience_filter VARCHAR(512) NULL;

COMMENT ON COLUMN ops_popups.audience_filter IS
    '人群条件 JSON（levelTiers/species/activity/paid，未出现的键=不筛）；NULL=不筛。与运营通知同口径（OpsAudienceSql.appendTargeting）';
