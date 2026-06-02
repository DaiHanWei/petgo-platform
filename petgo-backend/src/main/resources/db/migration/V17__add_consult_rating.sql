-- Story 5.6: 会话收尾 + 评分门 —— consult_sessions 增收尾字段 + 新建 consult_ratings 表。
-- 状态机收尾：IN_PROGRESS →(兽医结束) PENDING_CLOSE →(评分/30min超时) CLOSED。
-- 30min 评分门为状态迁移触发器（DB 状态机 + @Scheduled 定时扫描，禁 MQ）。
-- 评分仅运营可见（FR-33）；注销时匿名化保留（决策 D1，剥 user PII 留评级/评分）。时间戳 UTC。

ALTER TABLE consult_sessions
    ADD COLUMN pending_close_started_at TIMESTAMPTZ,            -- 评分门 30min 计时基准
    ADD COLUMN closed_reason            VARCHAR(16),            -- RATED | UNRATED
    ADD COLUMN rating_prompt_state      VARCHAR(16) NOT NULL DEFAULT 'NONE'; -- NONE | PENDING | PROMPTED

ALTER TABLE consult_sessions
    ADD CONSTRAINT ck_consult_sessions_closed_reason CHECK (
        closed_reason IS NULL OR closed_reason IN ('RATED', 'UNRATED')),
    ADD CONSTRAINT ck_consult_sessions_rating_prompt CHECK (
        rating_prompt_state IN ('NONE', 'PENDING', 'PROMPTED'));

CREATE TABLE consult_ratings (
    id         BIGSERIAL    PRIMARY KEY,
    session_id BIGINT       NOT NULL,
    vet_id     BIGINT       NOT NULL,
    user_id    BIGINT,                                -- 注销时匿名化置 NULL（决策 D1）
    stars      INT          NOT NULL,                 -- 1-5 必填
    comment    VARCHAR(100),                          -- ≤100 字选填
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT uq_consult_ratings_session UNIQUE (session_id),
    CONSTRAINT ck_consult_ratings_stars CHECK (stars BETWEEN 1 AND 5)
);

CREATE INDEX idx_consult_ratings_vet_id ON consult_ratings (vet_id);
