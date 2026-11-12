-- analytics-pipeline Story 1.8（架构 A10，Dai 2026-11-12 拍板）：注销擦除的待处理记录，放在【业务库】。
--
-- AccountDeletionService 状态机执行注销时，AnalyticsErasureParticipant 用业务数据源写一行（不访问埋点库，
-- 埋点库故障 / 管道停用都不会让注销失败）；ErasureJob 每晚读出 done_at IS NULL 的行，在埋点库执行擦除，
-- 成功后回写 done_at。DetailBuilder 也读本表（任何状态），拦截注销后设备补发的旧事件。
--
-- 只存内部 user_id 与哈希 user_key（AnalyticsDistinctId），不存任何 PII。
-- 不对 users 建外键：注销是就地匿名化，users 行不删；本表要比任何 users 行状态活得久（拦截迟到事件按 user_id 查）。
-- 行不删除：同一用户只一行（uq_analytics_erasure_outbox_user_id，状态机重跑时 ON CONFLICT DO NOTHING）。

CREATE TABLE analytics_erasure_outbox (
    id            BIGSERIAL    PRIMARY KEY,
    -- 被注销用户的内部 id
    user_id       BIGINT       NOT NULL,
    -- AnalyticsDistinctId.of(user_id)，sha256 hex 64 位
    user_key      VARCHAR(64)  NOT NULL,
    -- 登记时间（UTC）
    requested_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 埋点库擦除完成时间（UTC）；NULL = 待处理
    done_at       TIMESTAMPTZ,
    CONSTRAINT uq_analytics_erasure_outbox_user_id UNIQUE (user_id)
);

-- ErasureJob 每晚只扫待处理行
CREATE INDEX ix_analytics_erasure_outbox_pending ON analytics_erasure_outbox (requested_at) WHERE done_at IS NULL;

COMMENT ON TABLE analytics_erasure_outbox IS '注销擦除待处理记录（埋点库匿名化 outbox，A10）；同一用户一行，done_at 为空 = 待擦除';
