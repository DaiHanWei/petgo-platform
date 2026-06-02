-- Story 4.1: 分诊异步基建 —— 创建 triage_tasks 表（AI 智能分诊任务状态机）。
-- 异步统一 DB 状态机：PENDING -> PROCESSING -> DONE/FAILED + retry_count + 启动重扫（禁 MQ）。
-- 私密图只存对象 key（不可枚举）；签名 URL 敏感且会过期，绝不入库、绝不落日志。
-- 健康数据：symptom_text / parsed_result 落库，日志严禁明文。时间戳一律 UTC。

CREATE TABLE triage_tasks (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    pet_id            BIGINT,                              -- 存档绑定（FR-16，Story 2.5/4.4 承接），本故事仅预留
    status            VARCHAR(16)  NOT NULL,               -- PENDING | PROCESSING | DONE | FAILED
    danger_level      VARCHAR(8),                          -- GREEN | YELLOW | RED（解析后写入；最终态由 4.2 后置层只升不降裁决）
    symptom_text      TEXT,                                -- 健康数据：日志严禁落明文
    image_object_keys JSONB,                               -- 私密桶②对象 key 列表（≤3）；不存签名 URL
    gemini_raw        JSONB,                               -- Gemini 原始响应（存档/审计）
    parsed_result     JSONB,                               -- 解析后：绿/黄/红 + 观察建议 + 用药参考 + 免责声明
    retry_count       INT          NOT NULL DEFAULT 0,
    idempotency_key   VARCHAR(80),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(), -- UTC
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(), -- UTC
    CONSTRAINT ck_triage_tasks_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    CONSTRAINT ck_triage_tasks_danger_level CHECK (danger_level IS NULL OR danger_level IN ('GREEN', 'YELLOW', 'RED')),
    CONSTRAINT uq_triage_tasks_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_triage_tasks_user_id ON triage_tasks (user_id);
-- 供启动重扫（TriageTaskScanner）扫 PENDING/PROCESSING 残留任务续跑。
CREATE INDEX idx_triage_tasks_status ON triage_tasks (status);
