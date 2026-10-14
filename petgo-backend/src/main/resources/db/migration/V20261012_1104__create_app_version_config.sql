-- V1.3.4 app 主题 Story 3.1（FR-124 / AD-10）：App 版本配置从环境变量搬进单行表。
-- 时间戳版本号（决策 E7，预分配 V20261012_1104）。
--
-- 🔴 发版检查项：上线前按商店实际版本核对；本迁移提交即冻结，改值走后台 AB-22 或另起 UPDATE 迁移，不得改本文件
--    （决策日志 D-18：两端最新版本号预置 1.3.3；D-10：商店链接用标准地址、最低支持版本 1.0.0）。
--
-- 为什么 iOS / Android 各一列（PRD §3.3 规则 g）：两端上架不同步，共用一个最新版本号会让
-- 先上架那端改值后、另一端用户被提示去更新商店里还不存在的版本。
-- min_supported_version 双端共用，默认 1.0.0 永不上调（PRD §3.3 规则 d）。
--
-- 范式照 support_contact_config / pricing_config：单行强类型表 + CHECK(id=1) + 种子 INSERT，不建 key-value 表。

CREATE TABLE app_version_config (
    id                      BIGINT       PRIMARY KEY,
    ios_latest_version      VARCHAR(20)  NOT NULL,
    android_latest_version  VARCHAR(20)  NOT NULL,
    min_supported_version   VARCHAR(20)  NOT NULL,
    ios_store_url           VARCHAR(512) NOT NULL,   -- 空串 = 未配置（App 不出横幅、弹窗无「前往更新」）
    android_store_url       VARCHAR(512) NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by              BIGINT       NULL,       -- admin_accounts.id；迁移种子为 NULL，后台保存时写入
    CONSTRAINT ck_app_version_config_singleton CHECK (id = 1)
);

COMMENT ON TABLE app_version_config IS
    'App 版本配置（单行 id=1）。V1.3.4 Story 3.1：GET /api/v1/app-version 每次读本表，改值即生效、无需重启。';
COMMENT ON COLUMN app_version_config.min_supported_version IS
    '低于此版本 = 强制更新。默认 1.0.0 永不上调，仅安全问题 / 数据不兼容时临时上调，上调前两个商店链接必须已填。';

INSERT INTO app_version_config
    (id, ios_latest_version, android_latest_version, min_supported_version, ios_store_url, android_store_url)
VALUES
    (1, '1.3.3', '1.3.3', '1.0.0',
     'https://apps.apple.com/app/id6785361196',
     'https://play.google.com/store/apps/details?id=com.tailtopia.app');
