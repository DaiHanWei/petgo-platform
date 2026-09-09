-- V1.3.0 Story 6.1（AB-18A / AD-7 / D-3 / D-7）：KTP 模块高清图解锁定价三行——
-- pricing_config 加两列（护照·护照内页 / 护照·登机牌 一次性解锁价），初始值 = 当前 KTP 卡高清价；三价一律 CHECK (>= 1)（不做 0 元限免）。
-- D-3：加样式 = 加列 + 迁移 + 小发版，不改 key-value。D-45：已核实 stag=5000 / prod=100，均 ≥1，最后一条 CHECK 可直接上。
ALTER TABLE pricing_config ADD COLUMN passport_page_unlock_price     BIGINT;
ALTER TABLE pricing_config ADD COLUMN passport_boarding_unlock_price BIGINT;
UPDATE pricing_config SET passport_page_unlock_price     = id_hd_download_price,
                          passport_boarding_unlock_price = id_hd_download_price;
ALTER TABLE pricing_config ALTER COLUMN passport_page_unlock_price     SET NOT NULL;
ALTER TABLE pricing_config ALTER COLUMN passport_boarding_unlock_price SET NOT NULL;
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_passport_page_min     CHECK (passport_page_unlock_price >= 1);
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_passport_boarding_min CHECK (passport_boarding_unlock_price >= 1);
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_id_hd_min             CHECK (id_hd_download_price >= 1);
COMMENT ON COLUMN pricing_config.passport_page_unlock_price     IS 'FR-120 护照·护照内页样式一次性解锁价（IDR，≥1，D-7 不做 0 元）';
COMMENT ON COLUMN pricing_config.passport_boarding_unlock_price IS 'FR-120 护照·登机牌样式一次性解锁价（IDR，≥1）';
COMMENT ON COLUMN pricing_config.id_hd_download_price           IS 'KTP 卡高清图下载价（IDR，≥1；V1.3.0 起与护照两价同卡「KTP 模块高清图解锁定价」）';
