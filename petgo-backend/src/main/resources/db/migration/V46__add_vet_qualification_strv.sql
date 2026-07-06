-- Bug 20260701-166（R2 增量）：兽医 STRV（Surat Tanda Registrasi Veteriner，
-- 印尼兽医注册证，独立于 SIPDH 执业许可）可选留档字段。
-- 决策 E2：既有建表迁移 V35 冻结，加列一律走新 ALTER（勿改 V35）。
-- 可选字段：不阻断接单、不做到期扫描，故 strv_expiry 不建索引（与 sipdh_expiry 不同）。
ALTER TABLE vet_qualifications
    ADD COLUMN strv_no        VARCHAR(64),
    ADD COLUMN strv_issuer    VARCHAR(128),
    ADD COLUMN strv_expiry    DATE,
    ADD COLUMN strv_photo_key VARCHAR(255);
