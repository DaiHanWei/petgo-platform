-- V1.5.0 pet-species Story 1.3（架构 D-6 / D-13）：身份证卡快照细分类型。
-- 可空、不回填：旧卡按 pet_type 显示（快照不变，PRD 3.3.3）。不加 CHECK（快照，取值由 DTO 白名单保证）。
-- id_cards.pet_type 仍存大类（卡号物种码、findNumberSourceCard、findReusableKtpPassportNo 不变）。

ALTER TABLE id_cards ADD COLUMN pet_species VARCHAR(32);
COMMENT ON COLUMN id_cards.pet_species IS '建卡时细分类型快照，V1.5.0 起写；旧卡为 null，按 pet_type 显示';
