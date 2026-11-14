-- V1.5.0 地基 Story 0.1（CROSS-STORY-DECISIONS「2026-11-14 V1.5.0 六会话共用地基」）：
-- 用户最后上报的 App 版本。健康记录提醒（FR-166 版本门槛）与推送与召回（FR-185）共用「App 最后上报版本 ≥ 1.5.0」
-- 判定，两条线都调 AppVersionGate，不各写一套。
--
-- 写入：UserActivityFilter 每个已认证的 /api/v1 请求读 X-App-Version，与库里不同时才 UPDATE（同值不产生写）。
-- 存原样头值（含 -stag 后缀），截到 32 字符；比较只取开头三段数字（ClientAppVersion）。
-- 可空：NULL = 从没上报过（≤ 1.3.3 的包不带此头，或上线后还没来过），门槛判定按旧版处理。
-- 不回填。

ALTER TABLE users ADD COLUMN last_app_version VARCHAR(32);

COMMENT ON COLUMN users.last_app_version IS
    '最后上报的 App 版本（X-App-Version 原样，截 32 字符）；NULL = 未上报，推送版本门槛按旧版处理';
