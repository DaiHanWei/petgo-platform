-- 指标族 dau_reconcile（analytics-pipeline Story 4.2，FR-24 / FR-25，架构 D-11）：日活对账的数值列。
-- 写入 agg_daily_dau_reconcile。每个（指标族, WIB 日）一个事务，由 RollupService 执行（依赖 activity 指标族先算完当天）；
-- 同一事务内 DauReconcileFamily 再读整张表、用 DauReconcileCalculator 重算差值 / 覆盖率 / 观察期起点 / 状态并写回。
--
-- 参数（全部由 Java 传入，本文件不推算日期）：
--   :stat_date    统计日（WIB 日期）
--   :computed_at  本次运行开始时刻（UTC），只写进 computed_at 列
--
-- self_reconcile_dau：🔴 Epic 4 通用规则的有意例外（D-11，Dai 2026-11-12 拍板）——与 GA4「按设备计数」同口径：
--   按设备（匿名 id）去重；不走归属视图 v_events_person；不做生产排除（不看生产标记）；不排除兽医 / 虚拟号等账号。
--   仍然只算 source = 'APP'（H5 访客、服务端事件不是 App 设备）、仍然排除可疑事件（时间不可信，日期本身就不可靠）。
--   新版 App 才会发自建事件，所以它天然只含 V1.5.0+，与 GA4 V1.5.0+ 对齐。
-- ga4_new_version_dau：ga4_daily_snapshot 当天的 active_users_new_version；没有快照 → NULL。
-- dau_person / dau_logged_in：只作参照，取 agg_daily_activity 的 all 行（activity 指标族当天已算）。
-- status 新插入时先写 NOT_STARTED 占位，同一事务内由 Java 重算；冲突时不覆盖 status（同样随后重算）。
-- upsert 幂等：同一统计日重跑结果相同。

INSERT INTO agg_daily_dau_reconcile (stat_date, self_reconcile_dau, ga4_new_version_dau, dau_person, dau_logged_in,
                                     status, computed_at)
SELECT CAST(:stat_date AS date),
       (SELECT count(DISTINCT d.anonymous_id)
        FROM events_detail d
        WHERE d.event_date = CAST(:stat_date AS date)
          AND d.event = 'Application Opened'
          AND d.source = 'APP'
          AND NOT d.is_suspicious),
       (SELECT s.active_users_new_version
        FROM ga4_daily_snapshot s
        WHERE s.stat_date = CAST(:stat_date AS date)),
       (SELECT a.dau_person
        FROM agg_daily_activity a
        WHERE a.stat_date = CAST(:stat_date AS date) AND a.platform = 'all'),
       (SELECT a.dau_logged_in
        FROM agg_daily_activity a
        WHERE a.stat_date = CAST(:stat_date AS date) AND a.platform = 'all'),
       'NOT_STARTED',
       CAST(:computed_at AS timestamptz)
ON CONFLICT (stat_date) DO UPDATE SET
    self_reconcile_dau = EXCLUDED.self_reconcile_dau,
    ga4_new_version_dau = EXCLUDED.ga4_new_version_dau,
    dau_person = EXCLUDED.dau_person,
    dau_logged_in = EXCLUDED.dau_logged_in,
    computed_at = EXCLUDED.computed_at;
