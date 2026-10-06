-- V1.3.2 后台 PRD（2026-10-02 定稿）—— AB-18A 定价下限收紧 + AB-18B 专属章上传人 / 时间。时间戳版本号（决策 E7）。
--
-- ① 一次性解锁四价下限 1 → 100（后台 PRD §1.4）：Rp1～Rp99 没有任何实际售价会落进去，却能让运营漏个零
--    把 Rp2,000 配成 Rp200。只改这四列；问诊定价、keepsake_purchases.price_idr（成交价记录）不动。
--    🔴 现值任一 < 100 本迁移会失败、启动即拒 —— 上线前先跑 PRD §1.1 的核实 SQL（发版检查单 #1）。
--    2026-09-09 已核实 stag KTP=5000 / prod KTP=100（护照两列取自当时 KTP 价），Tailsonality 迁移给 5000。
ALTER TABLE pricing_config DROP CONSTRAINT ck_pricing_id_hd_min;
ALTER TABLE pricing_config DROP CONSTRAINT ck_pricing_passport_page_min;
ALTER TABLE pricing_config DROP CONSTRAINT ck_pricing_passport_boarding_min;
ALTER TABLE pricing_config DROP CONSTRAINT ck_pricing_tailsonality_min;
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_id_hd_min             CHECK (id_hd_download_price >= 100);
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_passport_page_min     CHECK (passport_page_unlock_price >= 100);
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_passport_boarding_min CHECK (passport_boarding_unlock_price >= 100);
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_tailsonality_min      CHECK (tailsonality_unlock_price >= 100);

COMMENT ON COLUMN pricing_config.id_hd_download_price           IS 'KTP 卡高清图下载价（IDR，≥100；同卡「一次性解锁定价」）';
COMMENT ON COLUMN pricing_config.passport_page_unlock_price     IS 'FR-120 护照内页 · 每次快照解锁价（IDR，≥100）';
COMMENT ON COLUMN pricing_config.passport_boarding_unlock_price IS 'FR-120 登机牌 · 每张解锁价（IDR，≥100）';
COMMENT ON COLUMN pricing_config.tailsonality_unlock_price      IS 'FR-117 Tailsonality · 结果解锁价（IDR，≥100，不做 0 元）';

-- ② 专属章上传人 / 时间（抽屉「09-30 15:12 由 Hex 上传」）。上传 / 替换时写入，移除时与 stamp_object_key 一起清空。
--    存量已上传的章两列为 NULL —— 抽屉不显示那一行，不从审计回填。
ALTER TABLE places ADD COLUMN stamp_uploaded_at TIMESTAMPTZ;
ALTER TABLE places ADD COLUMN stamp_uploaded_by BIGINT REFERENCES admin_accounts (id) ON DELETE SET NULL;

COMMENT ON COLUMN places.stamp_uploaded_at IS '专属章最近一次上传 / 替换时刻（UTC）；NULL = 无专属章或 2026-10-02 前上传的存量章。';
COMMENT ON COLUMN places.stamp_uploaded_by IS '专属章最近一次上传 / 替换的后台账号；账号删除置 NULL。';
