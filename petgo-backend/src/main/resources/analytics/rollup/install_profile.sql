-- 指标族 install_profile（analytics-pipeline Story 4.3）：安装后创建宠物档案（产品 #1），按 WIB 自然月。
-- 写入 agg_monthly_install_profile。由 InstallProfileFamily.computePeriods 对与本次重算窗口相交的每个月执行一次
-- （一个月一组 DELETE + INSERT，同一单元事务内；D-12：月份结束次日起算终值，之后 3 天内随回补再刷新）。
--
-- 参数（全部由 Java 传入，本文件不推算当前日期）：
--   :month_start     当月 1 日（WIB）
--   :month_end       当月最后一天（WIB，含）
--   :as_of           本次运行的基准日 = 运行时的 WIB 昨天；月中只算到 as_of（「截至昨天」），is_complete = 月末 ≤ as_of
--   :lookback_start  「首次」的回看起点 = month_start − analytics.rollup.install-lookback-days（默认 92 = 明细保留期）
--   :computed_at     本次运行开始时刻（UTC），只写进 computed_at 列
-- 依赖：v_events_person（身份归属视图，D-10）；tmp_excluded_users（本事务内建好的排除用户临时表）。
--
-- new_installs（分母，口径同 S2 = install.sql）：当月某天 d 是该人的「首次安装」，即 [d − 回看天数, d − 1] 内没有安装。
--   回看天数 = month_start − lookback_start；用 lag 取该人上一次安装日期判定，等价于对当月每天跑 install.sql 的 firsts，
--   所以 new_installs = 当月 agg_daily_install（platform = 'all'）new_installs 之和（L1 断言）。
-- with_pet_profile（分子，D-13 / Dai 2026-11-12 拍板）：上述新装人中，当月（event_date 在本月内且 ≤ as_of）有 pet_added
--   （建档请求成功后才上报）的人数。不要求建档晚于安装，只要求同月；月末安装、次月建档不计入。
--   不用点击即报的 pet_profile_create_submitted。
-- 标准过滤同 activity.sql：生产数据、非可疑、source = 'APP'、按人归属后排除非 USER / 非 REAL 用户。
-- person_key 只在中间结果里用，不落表。比例 = with_pet_profile ÷ new_installs，现算不入库。
-- 先 DELETE 再 INSERT，同一事务内，幂等；当月没人也写一行 0（0 = 没人，无行 = 没算）。

DELETE FROM agg_monthly_install_profile WHERE stat_month = CAST(:month_start AS date);

INSERT INTO agg_monthly_install_profile (stat_month, new_installs, with_pet_profile, is_complete, computed_at)
WITH install_days AS (
    SELECT DISTINCT p.person_key, p.event_date
    FROM v_events_person p
    LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
    WHERE p.event_date BETWEEN CAST(:lookback_start AS date)
                           AND LEAST(CAST(:month_end AS date), CAST(:as_of AS date))
      AND p.event = 'Application Installed'
      AND p.is_production
      AND NOT p.is_suspicious
      AND p.source = 'APP'
      AND x.user_id IS NULL
), with_prev AS (
    SELECT i.person_key,
           i.event_date,
           lag(i.event_date) OVER (PARTITION BY i.person_key ORDER BY i.event_date) AS prev_date
    FROM install_days i
), newcomers AS (
    SELECT DISTINCT w.person_key
    FROM with_prev w
    WHERE w.event_date >= CAST(:month_start AS date)
      AND (w.prev_date IS NULL
           OR w.prev_date < w.event_date - (CAST(:month_start AS date) - CAST(:lookback_start AS date)))
), profiled AS (
    SELECT DISTINCT p.person_key
    FROM v_events_person p
    LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
    WHERE p.event_date BETWEEN CAST(:month_start AS date)
                           AND LEAST(CAST(:month_end AS date), CAST(:as_of AS date))
      AND p.event = 'pet_added'
      AND p.is_production
      AND NOT p.is_suspicious
      AND p.source = 'APP'
      AND x.user_id IS NULL
      AND p.person_key IN (SELECT n.person_key FROM newcomers n)
)
SELECT CAST(:month_start AS date),
       (SELECT count(*) FROM newcomers),
       (SELECT count(*) FROM profiled),
       CAST(:month_end AS date) <= CAST(:as_of AS date),
       CAST(:computed_at AS timestamptz);
