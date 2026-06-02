-- Story 5.7: 兽医封禁 → 进行中会话中断 —— consult_sessions 增中断元信息。
-- 状态机旁路：IN_PROGRESS / PENDING_CLOSE →(封禁) INTERRUPTED（终态，不评分、不走存档桥接）。
-- 中断会话入历史标「已中断」（5.8 渲染），用户「同时仅 1 个」占用随之解除可重新发起。时间戳 UTC。

ALTER TABLE consult_sessions
    ADD COLUMN interrupted_reason VARCHAR(16),     -- VET_BANNED（封禁中断）
    ADD COLUMN interrupted_at     TIMESTAMPTZ;      -- 中断时刻 UTC

ALTER TABLE consult_sessions
    ADD CONSTRAINT ck_consult_sessions_interrupt_reason CHECK (
        interrupted_reason IS NULL OR interrupted_reason IN ('VET_BANNED'));
