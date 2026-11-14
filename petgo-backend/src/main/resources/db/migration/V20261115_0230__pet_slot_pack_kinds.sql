-- V1.5.0 gift-pack Story 4-2 · 架构 §3.6（逐字）。开工当天（2026-11-14）grep 确认 ck_pet_slot_purchases_kind / ck_pet_slot_rentals_source 只在 V20261018_1601 定义过。
-- 照抄 V20261018_1601 的全集再加值（当前树无其它迁移改过这两条约束；开工当天再 grep 一次）。
ALTER TABLE pet_slot_purchases DROP CONSTRAINT ck_pet_slot_purchases_kind;
ALTER TABLE pet_slot_purchases ADD CONSTRAINT ck_pet_slot_purchases_kind
    CHECK (kind IN ('RENT', 'RENEW', 'ADMIN_GRANT', 'PACK_GRANT', 'PACK_EXTEND'));
ALTER TABLE pet_slot_rentals DROP CONSTRAINT ck_pet_slot_rentals_source;
ALTER TABLE pet_slot_rentals ADD CONSTRAINT ck_pet_slot_rentals_source
    CHECK (source IN ('RENT', 'ADMIN_GRANT', 'PACK_GRANT'));
ALTER TABLE pet_slot_purchases ADD COLUMN starter_pack_purchase_id BIGINT NULL
    REFERENCES starter_pack_purchases (id) ON DELETE SET NULL;      -- 后台「档 n」显示用
