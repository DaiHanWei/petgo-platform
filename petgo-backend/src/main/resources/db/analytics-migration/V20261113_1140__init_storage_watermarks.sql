-- analytics-pipeline Story 1.10：埋点库存储水位每日快照（只在埋点库执行）。FR-16 / NFR-5 / 架构「磁盘熔断」。
-- 每天 WIB 04:30 由 StorageWatermarkJob 写一行（同一天重跑 ON CONFLICT 覆盖）；每个环境只记自己连接的那个埋点库（D-1）。
-- 🔴 不授权给只读角色：架构 A8 白名单未列此表（如需让 AI 看水位，另行拍板后新起迁移 GRANT）。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

CREATE TABLE storage_watermarks (
    snapshot_date       date         PRIMARY KEY,
    db_bytes            bigint       NOT NULL,
    raw_bytes           bigint       NOT NULL,
    detail_bytes        bigint       NOT NULL,
    events_on_day       bigint       NOT NULL,
    month_events        bigint       NOT NULL,
    month_growth_bytes  bigint,
    created_at          timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE storage_watermarks IS '埋点库存储水位每日快照（StorageWatermarkJob）：40 GB 告警 / 切回第三方提示的依据；停写熔断（50 GB）另由 IngestCircuitBreaker 每 5 分钟实时查';
COMMENT ON COLUMN storage_watermarks.snapshot_date IS 'WIB 日期（任务运行当天），每天一行，重跑覆盖';
COMMENT ON COLUMN storage_watermarks.db_bytes IS 'pg_database_size(current_database())：本环境埋点库整库占用（字节）';
COMMENT ON COLUMN storage_watermarks.raw_bytes IS 'events_raw 全部分区（含 events_raw_pdefault）pg_total_relation_size 之和（字节）';
COMMENT ON COLUMN storage_watermarks.detail_bytes IS 'events_detail 全部分区 pg_total_relation_size 之和（字节）';
COMMENT ON COLUMN storage_watermarks.events_on_day IS '任务运行时刻已完整结束的前一个 UTC 日的原始层分区 events_raw_pYYYYMMDD 行数（分区不存在记 0）。WIB 04:30 运行时该日 = snapshot_date − 2（UTC 仍在 snapshot_date − 1 的 21:30）';
COMMENT ON COLUMN storage_watermarks.month_events IS '本 WIB 自然月截至当天（含）的 events_on_day 累计';
COMMENT ON COLUMN storage_watermarks.month_growth_bytes IS 'db_bytes 与 30 天前快照之差；不足 30 天时取最早快照按天数折算为 30 天；仅当天一条时为 NULL';
COMMENT ON COLUMN storage_watermarks.created_at IS '本行最后写入时间（UTC）';
