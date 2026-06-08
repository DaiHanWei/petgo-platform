-- Story 5.3 (R2, 决策 F11): 兽医退单 —— consult_sessions 加退单计数列。
-- 兽医接单后可退单 IN_PROGRESS→WAITING 重新入队广播；release_count 累计该请求被退单次数。
-- 每请求最多正常退单 2 次；release_count > 2 为异常信号，由运营人工处理（查询 release_count）。
-- 表 V14 已落地，故走 ALTER 加列（决策 E2 单调序号，接 V22 之后）。并发互斥沿用 V16 @Version 乐观锁，禁中间件。
ALTER TABLE consult_sessions
    ADD COLUMN release_count INT NOT NULL DEFAULT 0;
