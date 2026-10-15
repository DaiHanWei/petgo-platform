-- V1.3.4 AB-22：后台「App 版本配置」卡片的变更日志前提。时间戳版本号（决策 E7）。
--
-- 本迁移做两件事，都是 AB-22 保存能写进 config_change_logs 的原子前提（拆开会出现
-- 「卡片上线、日志写不进去」的中间态，表现是「保存版本配置报 500」）：
--
-- ① old_value / new_value 从 VARCHAR(64) 加宽到 VARCHAR(512)。
--    app_version_config 的两个商店链接列是 VARCHAR(512)（Story 3.1），一个合法的长链接
--    原样写进 64 宽的日志列会炸 —— 而且炸在审计写入而不是业务校验（500 而不是 422）。
--    加宽是纯放宽：存量数据不受影响，既有配置卡的行为不变。
--
-- ② ck_config_change_type 放开 APP_VERSION。
--    🔴 **必须 DROP + ADD 重列全集**，值集取自当前树里最后一条重建它的迁移
--    V20260916_0925__init_support_contact_config.sql（五值）再加 APP_VERSION。
--    CLAUDE.md 记录此处已出过三次事故，每次都是照着一份过期列表抄，丢掉一批值。
--
-- ⚠️ V78 / V20260824_1655 / V20260916_0925 都已合入 main，属**冻结**迁移：不改它们，另起一条。

ALTER TABLE config_change_logs ALTER COLUMN old_value TYPE VARCHAR(512);
ALTER TABLE config_change_logs ALTER COLUMN new_value TYPE VARCHAR(512);

ALTER TABLE config_change_logs DROP CONSTRAINT IF EXISTS ck_config_change_type;
ALTER TABLE config_change_logs ADD CONSTRAINT ck_config_change_type
    CHECK (config_type IN ('PRICING', 'PAWCOIN', 'TOPUP_TIER', 'FEED_RANK', 'SUPPORT_CONTACT', 'APP_VERSION'));
