-- Story 2.1（管理后台 Epic 2）：兽医资质 vet_qualifications（AB-2H，6 态状态机 + 接单门控地基）。
-- 决策 E2 + CLAUDE.md：按 db/migration 实测最大号顺延。Epic 1 实占 V30~V32（admin_accounts/permissions/audit_logs）；
--   Story 1.4（admin_session_logs）已延后未实现、未占号，故本表取 V33（非规划表所列 V34）。1.4 落地时再顺延取当时最大号+1。
-- 与 vet_accounts 1:1（vet_account_id 唯一，FK）。证件图列存 OSS 私密桶对象 key（绝不存签名 URL，2.7 落上传/签名）。
-- 待完善阶段除 id/vet_account_id/status/时间戳外均可空。sipdh_expiry 用 date（按日判到期）。

CREATE TABLE vet_qualifications (
    id                BIGSERIAL    PRIMARY KEY,
    vet_account_id    BIGINT       NOT NULL,
    ktp_no            VARCHAR(64),
    ktp_photo_key     VARCHAR(255),
    sipdh_no          VARCHAR(64),
    sipdh_issuer      VARCHAR(128),
    sipdh_expiry      DATE,
    sipdh_photo_key   VARCHAR(255),
    degree_photo_key  VARCHAR(255),
    profile_photo_key VARCHAR(255),
    pdhi_photo_key    VARCHAR(255),
    specialties       JSONB,
    status            VARCHAR(24)  NOT NULL DEFAULT 'PENDING_COMPLETION',
    reject_reason     VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_vet_qualifications_vet UNIQUE (vet_account_id),
    CONSTRAINT fk_vet_qualifications_vet FOREIGN KEY (vet_account_id) REFERENCES vet_accounts (id),
    CONSTRAINT ck_vet_qualifications_status CHECK (status IN
        ('PENDING_COMPLETION','UNDER_REVIEW','CERTIFIED','REJECTED','EXPIRING_SOON','EXPIRED'))
);

-- 2.2 列表筛选 / 2.8 到期扫描用索引。
CREATE INDEX idx_vet_qualifications_status ON vet_qualifications (status);
CREATE INDEX idx_vet_qualifications_sipdh_expiry ON vet_qualifications (sipdh_expiry);
