-- V1.5.0 健康记录提醒 Story 1.1（架构增量 health-reminder §1.1，PRD FR-158/159/160/161/168）：
-- health_records 加 Next date、疫苗预设、剂次、驱虫类型、产品时长、记录来源；类型约束加 Kontrol dokter。
--
-- 值的来源（2026-11-14 核对）：
--   * ck_health_records_type 在当前树里只在 V76__init_health_records.sql 定义过一次（grep 全部迁移确认，
--     V76 之后无人重建），基数 = V76 的 5 值，本文件 DROP + ADD 重列全集并追加 VET_VISIT。
--   * 疫苗预设 10 值 / 驱虫类型 4 值 / 产品时长 1·3·8 个月：架构增量 §3.1 预设表、PRD FR-159/160。
-- 🔴 dose / product_duration_months 用 INTEGER，禁 SMALLINT：实体字段是 Integer，Hibernate 映成 int4，
--    SMALLINT 会让 ddl-auto=validate 启动即红（V1.1 事故）。
-- record_origin 三值：
--   BACKFILL   = 本迁移之前就存在的存量行（ADD COLUMN 的默认值一次性落到全部存量行）；
--   APP        = 1.5.0 及以上客户端新建（本迁移之后的列默认值）；
--   LEGACY_APP = 旧版客户端（请求头 < 1.5.0 或缺头）新建。
-- 🔴 不碰 ck_notifications_type（地基 V20261114_1000 所有）。不改写任何既有列值（回填归 Story 1.3 的 Runner）。

ALTER TABLE health_records
    ADD COLUMN next_date               DATE,
    ADD COLUMN vaccine_preset          VARCHAR(24),
    ADD COLUMN dose                    INTEGER,
    ADD COLUMN deworm_type             VARCHAR(16),
    ADD COLUMN product_duration_months INTEGER,
    ADD COLUMN record_origin           VARCHAR(16) NOT NULL DEFAULT 'BACKFILL';
ALTER TABLE health_records ALTER COLUMN record_origin SET DEFAULT 'APP';

ALTER TABLE health_records DROP CONSTRAINT IF EXISTS ck_health_records_type;
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_type
    CHECK (type IN ('VACCINE', 'DEWORM', 'MENSTRUATION', 'NEUTER', 'CUSTOM', 'VET_VISIT'));
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_vaccine_preset CHECK (vaccine_preset IS NULL OR vaccine_preset IN
    ('F3','F4','RABIES','FELV','PUPPY_DP','COMBO_DHPPI_L','LEPTO','KENNEL_COUGH','CANINE_INFLUENZA','OTHER'));
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_dose CHECK (dose IS NULL OR dose BETWEEN 1 AND 5);
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_deworm_type CHECK (deworm_type IS NULL OR deworm_type IN
    ('INTERNAL','EXTERNAL','FLEA_WORM','HEARTWORM'));
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_duration CHECK (product_duration_months IS NULL OR product_duration_months IN (1,3,8));
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_origin CHECK (record_origin IN ('APP','LEGACY_APP','BACKFILL'));
CREATE INDEX ix_health_records_pet_type ON health_records (pet_profile_id, type, event_date DESC, id DESC);
