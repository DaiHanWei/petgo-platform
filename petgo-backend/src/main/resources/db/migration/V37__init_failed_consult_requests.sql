-- Story 2.9（管理后台 Epic 2）：问诊请求未成功记录 failed_consult_requests（AB-2G）。
-- 实测最大号+1 = V35（V34=vet_accounts.contact_phone）。记录「从未建立会话」即失败的请求，
-- 独立于 Epic 5「已建立会话」异常工单。对外用不可枚举 request_token，不外露自增 id。
-- 系统行为落库（事件监听），无 admin_ 前缀（业务实体表）；时间 UTC；枚举 varchar UPPER_SNAKE。

CREATE TABLE failed_consult_requests (
    id               BIGSERIAL    PRIMARY KEY,
    request_token    VARCHAR(64)  NOT NULL,
    user_id          BIGINT       NOT NULL,
    session_id       BIGINT,                              -- 内部排查关联（不外露）
    submitted_at     TIMESTAMPTZ  NOT NULL,
    cancelled_at     TIMESTAMPTZ  NOT NULL,
    cancel_reason    VARCHAR(24)  NOT NULL,               -- USER_CANCEL | TIMEOUT | SYSTEM_FAILURE
    online_vet_count INT          NOT NULL DEFAULT 0,
    followed_up      BOOLEAN      NOT NULL DEFAULT FALSE,
    note             VARCHAR(1000),
    archived_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_failed_consult_requests_token UNIQUE (request_token),
    CONSTRAINT ck_failed_consult_requests_reason CHECK
        (cancel_reason IN ('USER_CANCEL','TIMEOUT','SYSTEM_FAILURE'))
);

CREATE INDEX idx_failed_consult_requests_reason     ON failed_consult_requests (cancel_reason);
CREATE INDEX idx_failed_consult_requests_followed   ON failed_consult_requests (followed_up);
CREATE INDEX idx_failed_consult_requests_archived   ON failed_consult_requests (archived_at);
CREATE INDEX idx_failed_consult_requests_submitted  ON failed_consult_requests (submitted_at);
