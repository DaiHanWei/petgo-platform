-- v1.4.2 shop · Story 1.1 · 迁移 ②（D-9、D-10、P-8 ②）：发货地、免运封顶、保价费率、区域代表邮编
--
-- 不回填：shipping_settings.origin_postal_code 与 shipping_zones.postal_code 由运营补齐；
-- 区域邮编补齐前该区游客询价按「不支持配送」（D-9 / §3.6 第 3 条）。
-- shipping_zones.fee 保留（NOT NULL 不动，后台录区仍写它；3.2 删旧运费服务后不再被读取）。
-- shipping_settings.id 为存量 SMALLINT（V20260817_2245），不动；新列不得用该类型。

ALTER TABLE shipping_settings ADD COLUMN origin_postal_code   VARCHAR(5);
ALTER TABLE shipping_settings ADD COLUMN origin_contact_name  VARCHAR(60);
ALTER TABLE shipping_settings ADD COLUMN origin_contact_phone VARCHAR(20);
ALTER TABLE shipping_settings ADD COLUMN origin_address       VARCHAR(300);
ALTER TABLE shipping_settings ADD COLUMN free_shipping_cap    BIGINT;              -- 空或 0 = 不设额外封顶（m7）
ALTER TABLE shipping_settings ADD COLUMN insurance_rate_bp    INTEGER NOT NULL DEFAULT 50;  -- 0.5%
ALTER TABLE shipping_settings ADD CONSTRAINT ck_shipping_settings_origin_postal
    CHECK (origin_postal_code IS NULL OR origin_postal_code ~ '^[1-9][0-9]{4}$');
ALTER TABLE shipping_settings ADD CONSTRAINT ck_shipping_settings_free_shipping_cap
    CHECK (free_shipping_cap IS NULL OR free_shipping_cap >= 0);
ALTER TABLE shipping_settings ADD CONSTRAINT ck_shipping_settings_insurance_rate
    CHECK (insurance_rate_bp BETWEEN 0 AND 10000);

ALTER TABLE shipping_zones ADD COLUMN postal_code VARCHAR(5);
ALTER TABLE shipping_zones ADD CONSTRAINT ck_shipping_zones_postal_code
    CHECK (postal_code IS NULL OR postal_code ~ '^[1-9][0-9]{4}$');
