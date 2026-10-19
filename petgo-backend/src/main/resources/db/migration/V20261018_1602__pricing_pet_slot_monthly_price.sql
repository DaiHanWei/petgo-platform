-- V1.4.0 multi-pet · Story 2.2 —— 宠物名额月租价格（AB-34.3，照 V20261009_0113 末段）。不碰任何共享 CHECK。
ALTER TABLE pricing_config ADD COLUMN pet_slot_monthly_price BIGINT;
UPDATE pricing_config SET pet_slot_monthly_price = 5000;
ALTER TABLE pricing_config ALTER COLUMN pet_slot_monthly_price SET NOT NULL;
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_pet_slot_monthly_min CHECK (pet_slot_monthly_price >= 100);
COMMENT ON COLUMN pricing_config.pet_slot_monthly_price IS '宠物名额月租：每个名额 / 30 天（IDR，≥100）；只影响之后的新租与续租，已生成待付单按下单快照';
