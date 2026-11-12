-- analytics-pipeline Story 4.1：汇总框架 + 活跃 / 安装 / 注册（只在埋点库执行）。D-5 / D-6 / D-10 / A8。
-- 本文件建：
--   agg_progress            每个指标族已完成的最后一个 WIB 日（续算 + 新鲜度标记，D-5）
--   agg_daily_activity      日活（自建，含游客，按人）、登录用户日活，分平台
--   agg_daily_install       新装数（S2）、注册数（S3 分子），分平台
--   v_events_person         身份归属视图（D-10），所有指标族共用
--   rollup_rerun_requests   汇总手动重跑请求（与 Story 1.7 detail_recompute_requests 同一形态：插一行、定时任务消费，
--                           不新增 HTTP 接口——架构「只新增 POST /api/v1/analytics/batch 一个对外端点」）
-- 汇总表不含 user_id / 匿名 id 等可识别个人的字段；不建外键。
-- 授权：agg_progress / agg_daily_activity / agg_daily_install / v_events_person 授给只读角色；
--       rollup_rerun_requests 是运维控制表，不授权（同 detail_recompute_requests）。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

-- ---------------------------------------------------------------- 汇总进度（D-5）
CREATE TABLE agg_progress (
    family          varchar(64)  PRIMARY KEY,
    last_done_date  date,
    last_run_at     timestamptz,
    last_error      varchar(200),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE agg_progress IS '汇总进度：每个指标族一行。last_done_date 既用于续算，也是「数据截至哪天」的新鲜度标记';
COMMENT ON COLUMN agg_progress.family IS '指标族名（= resources/analytics/rollup/<family>.sql 的文件名）';
COMMENT ON COLUMN agg_progress.last_done_date IS '已完成的最后一个 WIB 日；只前进不倒退（重算旧日期不会让它变小）；NULL = 从未成功';
COMMENT ON COLUMN agg_progress.last_run_at IS '最近一次运行（成功或失败）的开始时间（UTC）';
COMMENT ON COLUMN agg_progress.last_error IS '最近一次失败的异常类名链（不含数据）；成功一个单元后清空';

-- ---------------------------------------------------------------- 日活
CREATE TABLE agg_daily_activity (
    stat_date      date         NOT NULL,
    platform       varchar(16)  NOT NULL,
    dau_person     bigint       NOT NULL,
    dau_logged_in  bigint       NOT NULL,
    computed_at    timestamptz  NOT NULL,
    CONSTRAINT pk_agg_daily_activity PRIMARY KEY (stat_date, platform)
);

COMMENT ON TABLE agg_daily_activity IS '日活（S1 自建口径、登录用户日活），按 WIB 日、分平台；SQL 见 analytics/rollup/activity.sql';
COMMENT ON COLUMN agg_daily_activity.stat_date IS 'WIB 日期（Asia/Jakarta 自然日）';
COMMENT ON COLUMN agg_daily_activity.platform IS 'all = 全部平台单独去重（不是各平台相加）；其余为明细层平台原值（android / ios），缺失记 unknown';
COMMENT ON COLUMN agg_daily_activity.dau_person IS '自建日活：当天有 Application Opened 的去重人数（含游客，按 D-10 归属到人）';
COMMENT ON COLUMN agg_daily_activity.dau_logged_in IS '登录用户日活：事件发生时已登录（明细带 user_id）的去重用户数，不经关联表推断';
COMMENT ON COLUMN agg_daily_activity.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 安装 / 注册
CREATE TABLE agg_daily_install (
    stat_date     date         NOT NULL,
    platform      varchar(16)  NOT NULL,
    new_installs  bigint       NOT NULL,
    signups       bigint       NOT NULL,
    computed_at   timestamptz  NOT NULL,
    CONSTRAINT pk_agg_daily_install PRIMARY KEY (stat_date, platform)
);

COMMENT ON TABLE agg_daily_install IS '新装数（S2）、注册数（S3 分子），按 WIB 日、分平台；SQL 见 analytics/rollup/install.sql';
COMMENT ON COLUMN agg_daily_install.stat_date IS 'WIB 日期（Asia/Jakarta 自然日）';
COMMENT ON COLUMN agg_daily_install.platform IS 'all = 全部平台；其余为该人当天最早一条事件的明细层平台原值，缺失记 unknown（各平台相加 = all）';
COMMENT ON COLUMN agg_daily_install.new_installs IS 'S2：在明细保留期内首次出现 Application Installed 的日期 = 本日的去重人数（升级 Application Updated 不算）';
COMMENT ON COLUMN agg_daily_install.signups IS 'S3 分子：在明细保留期内首次出现 signup_succeeded 的日期 = 本日的去重人数';
COMMENT ON COLUMN agg_daily_install.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 身份归属视图（D-10）
-- 带用户标识的事件归该用户；匿名事件：
--   ① 事件之前有关联 → 最近一次关联的用户（含登出之后的事件）
--   ② 之前没有、之后有 → 该匿名 id 的第一个关联用户（登录前的安装 / 首启归到首次登录的人）
--   ③ 从未关联 → 匿名 id 本身算一个人（person_key = 'a:' || anonymous_id）
-- 比较用 event_time（校正后的发生时间）。identity_links.linked_at 由 Story 1.5 写入，含义是 user_identified 事件的
-- 校正后发生时间（client_ts + (received_at − sent_at)，晚于 received_at 取 received_at），与明细 event_time 同口径。
-- 注销擦除后关联行被删、user_id 置空，事件自动变回匿名 id 这个「人」（A10）。
-- 🔴 person_user_id / person_key 只在汇总 SQL 的中间结果里用，不写进任何 agg_* 表。
-- 🔴 只用 event_date 过滤（条件会下推进视图、只扫对应分区），不要用 date(event_time) / received_at 定日。
CREATE VIEW v_events_person AS
SELECT d.*,
       COALESCE(d.user_id, prev.user_id, nxt.user_id) AS person_user_id,
       CASE WHEN COALESCE(d.user_id, prev.user_id, nxt.user_id) IS NOT NULL
            THEN 'u:' || COALESCE(d.user_id, prev.user_id, nxt.user_id)::text
            ELSE 'a:' || d.anonymous_id::text
       END AS person_key
FROM events_detail d
LEFT JOIN LATERAL (
    SELECT l.user_id FROM identity_links l
    WHERE d.user_id IS NULL AND l.anonymous_id = d.anonymous_id AND l.linked_at <= d.event_time
    ORDER BY l.linked_at DESC LIMIT 1) prev ON true
LEFT JOIN LATERAL (
    SELECT l.user_id FROM identity_links l
    WHERE d.user_id IS NULL AND l.anonymous_id = d.anonymous_id AND l.linked_at > d.event_time
    ORDER BY l.linked_at ASC LIMIT 1) nxt ON true;

COMMENT ON VIEW v_events_person IS '身份归属（D-10）：明细全部列 + person_user_id（归属到的用户，可能为空）+ person_key（u:<user_id> 或 a:<匿名 id>）。汇总按 event_date 过滤';

-- ---------------------------------------------------------------- 手动重跑请求
-- 由 Dai 用写入账号插一行触发（只限明细层保留期内，见 runbook「重跑汇总」）：
--   INSERT INTO rollup_rerun_requests (from_date, to_date) VALUES ('2026-11-01', '2026-11-07');
--   INSERT INTO rollup_rerun_requests (from_date, to_date, families) VALUES ('2026-11-01', '2026-11-07', '{install}');
CREATE TABLE rollup_rerun_requests (
    id            bigserial     PRIMARY KEY,
    from_date     date          NOT NULL,
    to_date       date          NOT NULL,
    families      text[],
    status        varchar(16)   NOT NULL DEFAULT 'PENDING',
    requested_at  timestamptz   NOT NULL DEFAULT now(),
    started_at    timestamptz,
    done_at       timestamptz,
    error_code    varchar(200),
    CONSTRAINT ck_rollup_rerun_requests_range CHECK (from_date <= to_date),
    CONSTRAINT ck_rollup_rerun_requests_status
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'REJECTED'))
);

COMMENT ON TABLE rollup_rerun_requests IS '汇总手动重跑请求：插一行 PENDING，RollupRerunJob 按 id 顺序消费；只给写入账号用';
COMMENT ON COLUMN rollup_rerun_requests.from_date IS 'WIB 日期，含两端；须 ≥ 昨天(WIB) − 明细保留天数';
COMMENT ON COLUMN rollup_rerun_requests.to_date IS 'WIB 日期，含两端；须 ≤ 昨天(WIB)';
COMMENT ON COLUMN rollup_rerun_requests.families IS '要重跑的指标族；NULL = 全部';
COMMENT ON COLUMN rollup_rerun_requests.status IS 'PENDING → RUNNING → DONE / FAILED / REJECTED；FAILED 不自动重试，人工改回 PENDING 再跑';
COMMENT ON COLUMN rollup_rerun_requests.error_code IS 'FAILED：失败的指标族与异常类名；REJECTED：OUT_OF_RANGE / UNKNOWN_FAMILY';

-- ---------------------------------------------------------------- 只读角色授权（A8 逐表白名单）
-- 角色名由 Flyway 占位符 readerRole 传入；本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_progress', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_daily_activity', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_daily_install', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'v_events_person', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
