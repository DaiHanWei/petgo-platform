-- Story 3.2（管理后台 Epic 3）：停用用户强关进行中会话引入新中断原因 USER_DEACTIVATED。
-- 实测最大号+1 = V37（V36=users.status）。扩 consult_sessions 的 interrupt_reason CHECK 容纳新值。
-- 列 interrupted_reason VARCHAR(16) 已存在（V18），'USER_DEACTIVATED'=16 字符正好容纳。
ALTER TABLE consult_sessions DROP CONSTRAINT ck_consult_sessions_interrupt_reason;
ALTER TABLE consult_sessions
    ADD CONSTRAINT ck_consult_sessions_interrupt_reason CHECK (
        interrupted_reason IS NULL OR interrupted_reason IN ('VET_BANNED', 'USER_DEACTIVATED'));
