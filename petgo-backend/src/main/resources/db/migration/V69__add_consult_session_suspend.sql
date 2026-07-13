-- Story 3.8（Epic 3 收尾，安全攸关 H-5）：封禁挂起逃生。接 3-7 的 V68 顺延 V69（决策 E2 单调顺延）。
-- ⚠️ 移动靶：Epic 4 起原规划号顺延 +1，merge 时按当时全局 max 再顺延。加列不动旧迁移。
--
-- suspend_deadline_at：兽医被封禁时其进行中「付费」会话进入挂起态（非空=挂起中，服务端权威 15min 截止）。
--   挂起不改状态机（会话仍 IN_PROGRESS，IM 可用、用户在控制、不被劫持）；到期/用户逃生 → 强制结束+退款+INTERRUPTED。
--   免费会话封禁仍即时 INTERRUPTED（5.7 不变），该列恒 null。nullable，additive 安全。
ALTER TABLE consult_sessions ADD COLUMN suspend_deadline_at TIMESTAMPTZ;
CREATE INDEX idx_consult_sessions_suspend ON consult_sessions (suspend_deadline_at)
    WHERE suspend_deadline_at IS NOT NULL; -- 供 @Scheduled 扫挂起过期（部分索引，仅挂起中行）
