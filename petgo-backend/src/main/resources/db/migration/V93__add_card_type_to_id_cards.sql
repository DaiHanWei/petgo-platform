-- Story 6-8：身份证卡种维度（bug 20260721-330，Pet Passport / Student Card）。
-- 在 6-7 多卡快照（V91/V92）之上加卡种列；存量卡默认 KTP。ddl-auto=validate，schema 归 Flyway。
ALTER TABLE id_cards ADD COLUMN card_type VARCHAR(16) NOT NULL DEFAULT 'KTP';
