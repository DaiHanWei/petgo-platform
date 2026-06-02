-- Story 2.5：profile 侧承接问诊存档。创建 health_events 表（健康事件，进成长时间线）。
-- Flyway 序号：接 V4__init_content 之后单调分配（决策 E2）。schema 归 Flyway，ddl-auto=validate。
-- source_ref 为幂等键（一次问诊一条决策，FR-16「只问一次」）；image_keys 存私密桶②自有 key（绝不存会过期的 IM URL）。

CREATE TABLE health_events (
    id               BIGSERIAL    PRIMARY KEY,
    pet_id           BIGINT       NOT NULL,
    source_type      VARCHAR(16)  NOT NULL,            -- AI_TRIAGE | VET_CONSULT
    source_ref       VARCHAR(64)  NOT NULL,            -- 对应问诊/会话 token（幂等键）
    event_date       DATE         NOT NULL,
    symptom_summary  TEXT,                             -- 健康数据：日志严禁落明文
    ai_level         VARCHAR(8),                       -- GREEN | YELLOW | RED
    advice_summary   TEXT,                             -- 处理建议摘要（健康数据）
    image_keys       JSONB,                            -- 私密桶②自有 key 列表（展示走签名 URL）
    archive_decision VARCHAR(8)   NOT NULL,            -- ARCHIVED | SKIPPED
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- UTC
    CONSTRAINT ck_health_events_source_type CHECK (source_type IN ('AI_TRIAGE', 'VET_CONSULT')),
    CONSTRAINT ck_health_events_ai_level CHECK (ai_level IS NULL OR ai_level IN ('GREEN', 'YELLOW', 'RED')),
    CONSTRAINT ck_health_events_decision CHECK (archive_decision IN ('ARCHIVED', 'SKIPPED')),
    -- 一次问诊一条决策（FR-16 只问一次，幂等防重弹/重存）
    CONSTRAINT uq_health_events_source_ref UNIQUE (source_ref),
    -- 注销级联前置（FR-20）：注销/删档案时连带 health_events + 私密桶图。
    CONSTRAINT fk_health_events_pet FOREIGN KEY (pet_id) REFERENCES pet_profiles (id)
);

CREATE INDEX idx_health_events_pet_id ON health_events (pet_id);
