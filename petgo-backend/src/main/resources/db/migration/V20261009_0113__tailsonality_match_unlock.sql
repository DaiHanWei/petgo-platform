-- V1.3.2 · Tailsonality 配型单独付费（2026-10-09 产品决策，推翻 2026-09-21「配型全免费」）。时间戳版本号（决策 E7）。
--
--   · 配型按「每次测试结果」单独解锁，默认 Rp3,000（后台「一次性解锁定价」第 5 行可调）；
--   · 完整解读（Rp5,000）解锁时配型一并可看；
--   · 已单独买过配型的结果再买完整解读只补差价（成交价 − 已付配型价，下限 100）——补差在服务端算，库里只记成交价。
--
-- 🔴🔴 **本迁移全量重建两条共享 CHECK。** 🔴🔴
--   ① ck_payment_intents_purpose：取值取自当前树里最后一条重建它的 V20260930_2152（八值），本次只在末尾加 TS_MATCH。
--      并集核查（2026-10-09 对全部远端分支 git grep）：各分支最新重建均为 V20260930_2152 的八值或其子集 —— 全集 = 8 + 1 = 9。
--   ② ck_keepsake_purchases_sku：取值取自 V20260930_2152（三值），本次加 TS_MATCH。
--   purpose / sku 两列都是 VARCHAR(16)，故新值不叫 TAILSONALITY_MATCH（18 字符）而叫 TS_MATCH。
--
-- 🔴 本迁移不回填、不改写任何既有行：存量结果 match_unlocked_at 全为 NULL；
--    已解锁完整解读的结果靠 unlocked_at 判定「配型可看」，无需回填。

ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_purpose;
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_purpose
    CHECK (purpose IN ('VET_CONSULT', 'PAWCOIN_TOPUP', 'AI_UNLOCK', 'ID_HD', 'SHOP_ORDER',
                       'TAILSONALITY', 'PASSPORT_SNAP', 'BOARDING_PASS', 'TS_MATCH'));

ALTER TABLE keepsake_purchases DROP CONSTRAINT ck_keepsake_purchases_sku;
ALTER TABLE keepsake_purchases ADD CONSTRAINT ck_keepsake_purchases_sku
    CHECK (sku IN ('TAILSONALITY', 'PASSPORT_SNAP', 'BOARDING_PASS', 'TS_MATCH'));
COMMENT ON COLUMN keepsake_purchases.sku IS
    'TAILSONALITY / PASSPORT_SNAP / BOARDING_PASS / TS_MATCH（Tailsonality 配型单独解锁），与 payment_intents.purpose 同名。';

-- 配型单独解锁的权益标记（只由 TS_MATCH 的发放置位）。「配型可看」= unlocked_at 或 match_unlocked_at 任一非空。
ALTER TABLE tailsonality_results ADD COLUMN match_unlocked_at TIMESTAMPTZ NULL;
COMMENT ON COLUMN tailsonality_results.match_unlocked_at IS
    '配型单独解锁时刻（UTC）；null = 未单独买过配型。完整解读解锁（unlocked_at）同样视为配型可看。';

ALTER TABLE pricing_config ADD COLUMN tailsonality_match_unlock_price BIGINT;
UPDATE pricing_config SET tailsonality_match_unlock_price = 3000;
ALTER TABLE pricing_config ALTER COLUMN tailsonality_match_unlock_price SET NOT NULL;
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_tailsonality_match_min CHECK (tailsonality_match_unlock_price >= 100);
COMMENT ON COLUMN pricing_config.tailsonality_match_unlock_price IS
    'Tailsonality · 配型单独解锁价（IDR，≥100；后台另校验须低于结果解锁价，否则补差价无意义）';
