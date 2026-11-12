-- analytics-pipeline Story 4.2：GA4 快照与日活对账（只在埋点库执行）。D-11 / FR-24 / FR-25 / A8。
-- 本文件建：
--   ga4_daily_snapshot        GA4 每日活跃用户快照（全版本 + 新版本），Ga4DailySnapshotJob 每天回拉最近 3 天覆盖旧值
--   agg_daily_dau_reconcile   自建对账日活 vs GA4 新版本日活的差值、新版覆盖率与对账状态（指标族 dau_reconcile）
-- 两张表都不含 user_id / 匿名 id 等可识别个人的字段；不建外键。
-- 授权：两张表都授给只读角色（A8「对账表」在白名单内）。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

-- ---------------------------------------------------------------- GA4 快照
CREATE TABLE ga4_daily_snapshot (
    stat_date                 date         PRIMARY KEY,
    active_users_all          bigint       NOT NULL,
    active_users_new_version  bigint       NOT NULL,
    min_app_version           varchar(16)  NOT NULL,
    fetched_at                timestamptz  NOT NULL
);

COMMENT ON TABLE ga4_daily_snapshot IS 'GA4 Data API activeUsers 每日快照（Firebase 统计，iOS + Android 合计，按设备、含未登录用户）。Ga4DailySnapshotJob 每天 WIB 02:30 回拉最近 3 天并补拉近 14 天缺的日期，覆盖旧值（GA4 有处理延迟）';
COMMENT ON COLUMN ga4_daily_snapshot.stat_date IS 'GA4 属性时区的自然日 = WIB 日（GA4 属性时区必须设为雅加达）';
COMMENT ON COLUMN ga4_daily_snapshot.active_users_all IS 'GA4 全版本 activeUsers（按设备去重、含游客）；观察期内 S1 日活取本列';
COMMENT ON COLUMN ga4_daily_snapshot.active_users_new_version IS 'GA4 appVersion ≥ min_app_version 的 activeUsers（inListFilter 一次请求，GA4 侧已按设备去重）；当天没有新版本 = 0';
COMMENT ON COLUMN ga4_daily_snapshot.min_app_version IS '本行使用的版本阈值（analytics.reconcile.min-app-version，如 1.5.0），按数字逐段比较';
COMMENT ON COLUMN ga4_daily_snapshot.fetched_at IS '最近一次拉取时间（UTC）';

-- ---------------------------------------------------------------- 日活对账
CREATE TABLE agg_daily_dau_reconcile (
    stat_date            date          PRIMARY KEY,
    self_reconcile_dau   bigint        NOT NULL,
    ga4_new_version_dau  bigint,
    diff_pct             numeric(9,4),
    coverage             numeric(9,4),
    dau_person           bigint,
    dau_logged_in        bigint,
    status               varchar(16)   NOT NULL,
    observation_start    date,
    computed_at          timestamptz   NOT NULL,
    CONSTRAINT ck_agg_daily_dau_reconcile_status
        CHECK (status IN ('NOT_STARTED', 'NORMAL', 'ABNORMAL', 'ENDED', 'NO_GA4'))
);

COMMENT ON TABLE agg_daily_dau_reconcile IS '日活对账（FR-24 / FR-25，D-11）：自建对账日活 vs GA4 V1.5.0+ 日活，每 WIB 日一行。只存库，不推飞书。SQL 见 analytics/rollup/dau_reconcile.sql，差值 / 覆盖率 / 状态由 DauReconcileCalculator 计算';
COMMENT ON COLUMN agg_daily_dau_reconcile.stat_date IS 'WIB 日期（Asia/Jakarta 自然日）';
COMMENT ON COLUMN agg_daily_dau_reconcile.self_reconcile_dau IS '自建对账日活：当天 Application Opened、source = APP、非可疑的去重匿名 id 数。按设备去重、不做生产排除、不排除兽医等账号，与 GA4 按设备计数同口径（Epic 4 通用规则的有意例外）';
COMMENT ON COLUMN agg_daily_dau_reconcile.ga4_new_version_dau IS 'GA4 V1.5.0+ 日活（取自 ga4_daily_snapshot.active_users_new_version）；NULL = GA4 没拉到';
COMMENT ON COLUMN agg_daily_dau_reconcile.diff_pct IS '差值 =（自建对账 − GA4 V1.5.0+）÷ GA4 V1.5.0+，小数（0.0523 = 5.23%），四位小数四舍五入；GA4 为空或 0 时为 NULL';
COMMENT ON COLUMN agg_daily_dau_reconcile.coverage IS '新版覆盖率 = GA4 V1.5.0+ ÷ GA4 全版本（近似：GA4 appVersion 取当天上报时的版本，升级当天的设备新旧两边各算一次）；GA4 缺数或全版本为 0 时为 NULL';
COMMENT ON COLUMN agg_daily_dau_reconcile.dau_person IS '参照：自建日活（含游客，按人，D-10），取 agg_daily_activity 的 all 行';
COMMENT ON COLUMN agg_daily_dau_reconcile.dau_logged_in IS '参照：登录用户日活，取 agg_daily_activity 的 all 行';
COMMENT ON COLUMN agg_daily_dau_reconcile.status IS '对账状态：观察期 = 首个覆盖率 ≥ 90% 的日期起 28 天（含两端）。之前 NOT_STARTED、之后 ENDED；期内 |差值| ≤ 10% 为 NORMAL，否则 ABNORMAL；未结束且 GA4 没拉到为 NO_GA4（epics 四种状态之外新增，区分「没拉到」与「对不上」）';
COMMENT ON COLUMN agg_daily_dau_reconcile.observation_start IS '观察期起点（全表同值）；随 GA4 回拉可能变化，每次汇总后整表重算';
COMMENT ON COLUMN agg_daily_dau_reconcile.computed_at IS '本行数值最近一次计算时间（UTC）';

-- ---------------------------------------------------------------- 只读角色授权（A8 逐表白名单）
-- 角色名由 Flyway 占位符 readerRole 传入；本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'ga4_daily_snapshot', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_daily_dau_reconcile', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
