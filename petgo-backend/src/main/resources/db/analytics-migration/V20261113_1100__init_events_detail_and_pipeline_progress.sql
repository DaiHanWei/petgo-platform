-- analytics-pipeline Story 1.6：明细层 events_detail + 处理进度 pipeline_progress（只在埋点库执行）。D-3 / D-4 / D-8 / A8。
-- 明细层按 event_date（WIB 日期）做 RANGE 分区，每天一个分区，🔴 不建 DEFAULT 分区（D-3：DEFAULT 一旦接住某天的行，
--   之后预建该天分区会直接报错）。本文件建今天（WIB）前后各 7 天共 15 个分区；之后由 PartitionMaintenanceJob 每天滚动预建。
-- 列只含 D-4 规定的解析列；properties 只保留事件清单登记过的键（由 DetailBuilder 过滤），完整报文只在原始层。
-- anonymous_id 与 events_raw 一致用 varchar(64)：H5 访客 id 是 32 位 hex，不是 uuid；服务端事件为空。
-- 授权：只把父表 events_detail 授给只读角色（AI 经父表查询，后建分区无需单独授权）；pipeline_progress 不授权（A8）。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

-- 处理进度（D-8）：DetailBuilder 处理到的 events_raw.received_at 上界（不含已处理之外的行）。不复用 agg_progress。
CREATE TABLE pipeline_progress (
    pipeline          varchar(32)  PRIMARY KEY,
    last_received_at  timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE pipeline_progress IS '埋点管道处理进度（DetailBuilder 等），只给写入账号用';
COMMENT ON COLUMN pipeline_progress.last_received_at IS '已处理到的 events_raw.received_at（含），下一轮处理 > 此值的行';

-- 初始游标取 1970-01-01：DetailBuilder 落后较多时会直接跳到下一条待处理的 received_at，不会逐片空转。
INSERT INTO pipeline_progress (pipeline, last_received_at) VALUES ('detail', '1970-01-01 00:00:00+00');

CREATE TABLE events_detail (
    message_id     varchar(64)  NOT NULL,
    event          varchar(128) NOT NULL,
    client_ts      timestamptz  NOT NULL,
    event_time     timestamptz  NOT NULL,
    event_date     date         NOT NULL,
    is_suspicious  boolean      NOT NULL DEFAULT false,
    source         varchar(8)   NOT NULL,
    anonymous_id   varchar(64),
    user_id        bigint,
    user_key       varchar(64),
    platform       varchar(64),
    app_version    varchar(64),
    os_version     varchar(64),
    device_model   varchar(64),
    is_production  boolean      NOT NULL,
    properties     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    -- 唯一约束必须含分区键；写入用 ON CONFLICT (event_date, message_id) DO NOTHING 兜底去重（D-3）
    CONSTRAINT uq_events_detail_date_msg UNIQUE (event_date, message_id),
    CONSTRAINT ck_events_detail_source CHECK (source IN ('APP', 'SERVER', 'H5'))
) PARTITION BY RANGE (event_date);

-- 父表索引自动下沉到每个分区
CREATE INDEX ix_events_detail_user_id ON events_detail (user_id);
CREATE INDEX ix_events_detail_anonymous_id ON events_detail (anonymous_id);
CREATE INDEX ix_events_detail_event ON events_detail (event);

COMMENT ON TABLE events_detail IS '埋点明细层：去重、时间校正、属性瘦身后的事件，按 event_date（WIB 日期）每天一个分区，保留 3 个月';
COMMENT ON COLUMN events_detail.message_id IS '客户端 / 服务端生成的事件唯一 id（去重键）';
COMMENT ON COLUMN events_detail.event IS '事件名（已在事件清单登记）';
COMMENT ON COLUMN events_detail.client_ts IS '客户端声明的发生时间，原样保留（UTC）';
COMMENT ON COLUMN events_detail.event_time IS '校正后的发生时间（UTC）= client_ts + (received_at - sent_at)';
COMMENT ON COLUMN events_detail.event_date IS 'WIB 日期（Asia/Jakarta），分区键；由 event_time 换算，可疑或超出已建分区时取收到时间的 WIB 日期。按天统计只看本列';
COMMENT ON COLUMN events_detail.is_suspicious IS '可疑：校正后晚于收到时间超过 5 秒或早于收到时间超过 7 天；汇总排除';
COMMENT ON COLUMN events_detail.source IS '来源：APP / SERVER / H5';
COMMENT ON COLUMN events_detail.anonymous_id IS '设备 / 访客匿名 id；服务端事件为空';
COMMENT ON COLUMN events_detail.user_id IS '内部用户 id（校验后），只在埋点库内部用；按人归属在汇总时查 identity_links';
COMMENT ON COLUMN events_detail.user_key IS '用户标识哈希 AnalyticsDistinctId（sha256 hex）';
COMMENT ON COLUMN events_detail.platform IS '平台：android / ios（来自上下文 platform）';
COMMENT ON COLUMN events_detail.app_version IS 'App 版本（上下文 appVersion）';
COMMENT ON COLUMN events_detail.os_version IS '系统版本（上下文 osVersion）';
COMMENT ON COLUMN events_detail.device_model IS '机型（上下文 deviceModel）';
COMMENT ON COLUMN events_detail.is_production IS '生产标记：模拟器 / TestFlight / 侧载、非 USER 角色或非 REAL 账号为 false；汇总排除 false';
COMMENT ON COLUMN events_detail.properties IS '只保留事件清单登记过的属性键；完整报文在原始层';

-- 初始分区：今天（WIB）前后各 7 天，共 15 个。之后由 PartitionMaintenanceJob 每天滚动预建今天起 7 天。
DO $$
DECLARE
  today date := (now() AT TIME ZONE 'Asia/Jakarta')::date;
  d date;
BEGIN
  FOR i IN -7..7 LOOP
    d := today + i;
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF events_detail FOR VALUES FROM (%L) TO (%L)',
                   'events_detail_p' || to_char(d, 'YYYYMMDD'), to_char(d, 'YYYY-MM-DD'), to_char(d + 1, 'YYYY-MM-DD'));
  END LOOP;
END $$;

-- 只读角色（AI 查数）只给父表 SELECT（A8 逐表授权）。角色名由 Flyway 占位符 readerRole 传入；
-- 本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'events_detail', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
