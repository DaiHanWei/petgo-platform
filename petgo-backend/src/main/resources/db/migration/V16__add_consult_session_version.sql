-- Story 5.5: 接单并发抢单 —— consult_sessions 加乐观锁版本列。
-- 多兽医同时点「接受」→ JPA @Version 乐观锁保证仅一人 CAS 成功 WAITING→IN_PROGRESS，其余「已被接走」。
ALTER TABLE consult_sessions
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
