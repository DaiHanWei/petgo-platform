-- Story 7.3: 账号注销级联删除/匿名化（PDP NFR-8，架构不可协商地基 #4）。
-- 注销作为可靠异步作业（DB 状态机驱动，禁 MQ）：PENDING→PROCESSING→DONE/FAILED + retry_count + 启动重扫。
-- 删除穷举各表 + OSS 图片；UGC(content) 与 consult 会话/评分走匿名化保留（决策 D1）。
-- 日志/本表绝不落 PII：仅存 user 代理 id + 状态机进度 + 时间（合规留证，不含昵称/令牌/健康数据）。

CREATE TABLE account_deletions (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING | PROCESSING | DONE | FAILED
    retry_count     INT          NOT NULL DEFAULT 0,
    requested_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),     -- 注销受理时间（合规留证，UTC）
    completed_at    TIMESTAMPTZ,
    CONSTRAINT ck_account_deletions_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    CONSTRAINT uq_account_deletions_user UNIQUE (user_id)
);

CREATE INDEX idx_account_deletions_status ON account_deletions (status);

-- 匿名化保留（决策 D1）：consult 会话/评分剥离 user PII（解关联 user_id），保留症状/评级/评分。
-- 故 user_id 需可空（注销后置 NULL）。
ALTER TABLE consult_sessions ALTER COLUMN user_id DROP NOT NULL;
