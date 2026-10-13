-- V1.3.4 ktp Story 1.1（AD-1）：身份证卡种扩为 5 种 + 学生卡配色 card_variant + 三条 CHECK。
-- card_type：KTP / PASSPORT / KITAS（居住证）/ SIM / STUDENT；card_variant：学生卡 BINTANG / MALAM / CERIA / KLASIK，
-- 非学生卡一律 NULL。后端枚举 IdCardType / StudentVariant 与请求白名单同源（单测断言一致）。
-- ddl-auto=validate，schema 归 Flyway。
--
-- 加约束前自检（V93 起请求白名单只放 KTP|PASSPORT|STUDENT，存量不应有其它值）：
--   SELECT card_type, COUNT(*) FROM id_cards GROUP BY card_type;
--   期望只有 KTP / PASSPORT / STUDENT 三个值。
--   本地 / stag 库结果：云端会话无库可连，待本地验收时执行并补记于 story 1-1 Completion Notes。
-- 下方 DO 块在迁移内再做一次同口径自检：出现白名单外的值直接 RAISE，迁移失败、不落半截约束。

ALTER TABLE id_cards ADD COLUMN card_variant VARCHAR(16);

-- 存量学生卡只有紫色一款（旧版 App），回填 KLASIK。
UPDATE id_cards SET card_variant = 'KLASIK' WHERE card_type = 'STUDENT';

DO $$
DECLARE
    bad BIGINT;
BEGIN
    SELECT COUNT(*) INTO bad FROM id_cards WHERE card_type NOT IN ('KTP', 'PASSPORT', 'STUDENT');
    IF bad > 0 THEN
        RAISE EXCEPTION 'id_cards 存在 % 行白名单外的 card_type，先人工核查再加 CHECK', bad;
    END IF;
END $$;

ALTER TABLE id_cards ADD CONSTRAINT ck_id_cards_card_type
    CHECK (card_type IN ('KTP', 'PASSPORT', 'KITAS', 'SIM', 'STUDENT'));
ALTER TABLE id_cards ADD CONSTRAINT ck_id_cards_card_variant
    CHECK (card_variant IS NULL OR card_variant IN ('BINTANG', 'MALAM', 'CERIA', 'KLASIK'));
ALTER TABLE id_cards ADD CONSTRAINT ck_id_cards_variant_only_student
    CHECK ((card_type = 'STUDENT') = (card_variant IS NOT NULL));

COMMENT ON COLUMN id_cards.card_variant IS
    '学生卡配色 BINTANG/MALAM/CERIA/KLASIK；非学生卡 NULL；存量学生卡回填 KLASIK（V1.3.4 ktp）';
