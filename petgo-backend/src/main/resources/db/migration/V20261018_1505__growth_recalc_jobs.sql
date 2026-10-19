-- V1.4.0 ① Tailrent Level（Story 4.1 · AD-12；号预分配 V20261018_1505）：改等级表后的全量等级重算任务。
-- 状态机 PENDING → RUNNING → DONE / FAILED，只用 @Async + DB 状态机（不引 MQ / 调度中间件）。
-- 相对 AD-12 列表补 started_at（判僵死：RUNNING 超 30 分钟可重新执行）与 failure_reason（失败可见，只记异常类名）。
-- requested_by = admin_accounts.id，不建 FK（后台账号不物理删）。
CREATE TABLE growth_recalc_jobs (
    id             BIGSERIAL    PRIMARY KEY,
    status         VARCHAR(16)  NOT NULL,
    requested_by   BIGINT       NOT NULL,
    upgraded       INT          NULL,
    downgraded     INT          NULL,
    failure_reason VARCHAR(200) NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ  NULL,
    finished_at    TIMESTAMPTZ  NULL,
    CONSTRAINT ck_growth_recalc_jobs_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'))
);

-- 同时最多一个进行中的重算（并发保存等级表时第二个插入即违反唯一索引 → 422 recalcRunning）。
CREATE UNIQUE INDEX uq_growth_recalc_jobs_active ON growth_recalc_jobs ((1)) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX idx_growth_recalc_jobs_created ON growth_recalc_jobs (created_at DESC);

COMMENT ON TABLE growth_recalc_jobs IS '改等级表后的全量等级重算任务（V1.4.0 Story 4.1 · AD-12）';
