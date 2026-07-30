-- Story 9.6（AB-7A）：红色超额只读监控的人工复核标记。号 V81（当前 max V80；决策 E2）。
-- ⚠️ 纯观测 + 人工注记：绝不自动拦截/限流/封禁（AB-7A，阈值 OPEN OQ-11）。RED 计数实时聚合自
-- triage_tasks（不落此表）；本表仅存 user 维度的复核态（待核查/已处理）。

CREATE TABLE red_overage_reviews (
    user_id      BIGINT      PRIMARY KEY,                 -- 一用户一复核行
    status       VARCHAR(12) NOT NULL,                    -- TO_VERIFY / RESOLVED
    note         VARCHAR(255),
    reviewed_by  BIGINT      NOT NULL,                    -- admin_accounts.id
    reviewed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_red_overage_status CHECK (status IN ('TO_VERIFY', 'RESOLVED'))
);
