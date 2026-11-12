-- analytics-pipeline Story 1.3：原始层 events_raw（只在埋点库执行）。D-2 / D-4 / D-8 / A8。
-- 按 received_at 的 UTC 日做 RANGE 分区；本文件只建父表 + DEFAULT 兜底分区 + BRIN。
-- 🔴 这里不建任何日期分区：迁移是静态文本，写死日期会在别的环境 / 别的日子跑出错位分区。
--    日期分区一律由 PartitionMaintenanceJob 建（启动后立即跑一次，之后每天 UTC 00:05），预建今天起 8 天。
-- 🔴 不授权给只读角色：原始层含完整报文，只给写入账号用（A8）。
-- 不建主键 / 唯一约束：原始层只追加、允许重复（D-2），去重在明细层（D-3）。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

CREATE TABLE events_raw (
    message_id          varchar(64)  NOT NULL,
    event               varchar(128) NOT NULL,
    -- 客户端声明的发生时间（请求里的 timestamp），原样
    client_ts           timestamptz  NOT NULL,
    -- 客户端声明的发送时间
    sent_at             timestamptz  NOT NULL,
    -- 服务端收到时间，UTC；分区键
    received_at         timestamptz  NOT NULL,
    -- 可空：服务端 captureForUser 事件没有匿名 id（Story 1.9）；H5 访客 id 是 32 位 hex，故不用 uuid 类型
    anonymous_id        varchar(64),
    -- 用户标识哈希（AnalyticsDistinctId，sha256 hex 64 位）
    user_key            varchar(64),
    -- 内部用户 id，只在埋点库内部用，不对外
    user_id             bigint,
    source              varchar(8)   NOT NULL,
    properties          jsonb        NOT NULL DEFAULT '{}'::jsonb,
    context             jsonb        NOT NULL DEFAULT '{}'::jsonb,
    identity_unverified boolean      NOT NULL DEFAULT false,
    unknown_event       boolean      NOT NULL DEFAULT false,
    CONSTRAINT ck_events_raw_source CHECK (source IN ('APP', 'SERVER', 'H5'))
) PARTITION BY RANGE (received_at);

-- 兜底分区（D-8）：正常永远为空；非空即 TECH 告警。
CREATE TABLE events_raw_pdefault PARTITION OF events_raw DEFAULT;

-- 只建 BRIN：原始层按 received_at 顺序追加，BRIN 体积极小；明细查询走明细层。别加其他索引。
CREATE INDEX ix_events_raw_received_at_brin ON events_raw USING brin (received_at);

COMMENT ON COLUMN events_raw.received_at IS '服务端收到时间（UTC），分区键：每天一个分区（UTC 日）';
