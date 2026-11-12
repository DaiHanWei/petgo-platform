-- 指标族 retention（analytics-pipeline Story 4.3）：按组的留存。写入 agg_cohort_retention。
--   INSTALL 组    （市场 #1–#3）：组日期 X = 首次 Application Installed 的 WIB 日期；
--                 d1 / d3 / d7_retained = 组成员中在 X+1 / X+3 / X+7 当天有 Application Opened 的人数。
--   ONBOARDING 组（产品 #5）   ：组日期 X = 首次 onboarding_completed 的 WIB 日期；
--                 w1_retained = 组成员中在 [X+7, X+13] 任意一天有 Application Opened 的人数。
-- 每个（指标族, 组日期 X）一个事务，由 RollupService 执行（recomputeDays = 16：13 天观察窗口 + 3 天晚到，D-6）。
--
-- 参数（全部由 Java 传入，本文件不推算当前日期）：
--   :stat_date       组日期 X（WIB 日期）
--   :as_of           本次运行的基准日 = 运行时的 WIB 昨天（手动重跑也传当前的 as_of）
--   :lookback_start  「首次」的回看起点 = stat_date − analytics.rollup.install-lookback-days（默认 92 = 明细保留期）
--   :computed_at     本次运行开始时刻（UTC），只写进 computed_at 列
-- 依赖：v_events_person（身份归属视图，D-10）；tmp_excluded_users（本事务内建好的排除用户临时表）。
--
-- 成熟度：某列是否「已满 N 天」只看 X+N ≤ as_of；未满写 NULL（只用于 INSTALL 的 d* 列在 ONBOARDING 行恒为 NULL，反之亦然）。
-- 🔴「首次安装」与 install.sql 的 firsts 口径完全相同（同一回看区间、同一标准过滤、按 person_key 取最早 event_date），
--    所以 INSTALL 组人数 = agg_daily_install（platform = 'all'）同日的 new_installs（L1 断言）。
-- onboarding_completed 同样只认首次：同一人重装后再走一次引导，只按第一次（在明细保留期内判断）分组。
-- 安装当天（X）的 Application Opened 不算任何留存；按 event_date 定日（不按发生时刻或收到时间切天）。
-- 标准过滤同 activity.sql：生产数据、非可疑、source = 'APP'、按人归属后排除非 USER / 非 REAL 用户。
-- person_key 只在中间结果里用，不落表。两种 cohort_type 各写一行，组为空也写 cohort_size = 0（0 = 没人，无行 = 没算）。
-- 先 DELETE 再 INSERT，同一事务内，幂等。

DELETE FROM agg_cohort_retention WHERE cohort_date = CAST(:stat_date AS date);

INSERT INTO agg_cohort_retention (cohort_type, cohort_date, cohort_size, d1_retained, d3_retained, d7_retained,
                                  w1_retained, computed_at)
WITH firsts AS (
    SELECT p.event, p.person_key, min(p.event_date) AS first_date
    FROM v_events_person p
    LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
    WHERE p.event_date BETWEEN CAST(:lookback_start AS date) AND CAST(:stat_date AS date)
      AND p.event IN ('Application Installed', 'onboarding_completed')
      AND p.is_production
      AND NOT p.is_suspicious
      AND p.source = 'APP'
      AND x.user_id IS NULL
    GROUP BY p.event, p.person_key
), cohort AS (
    SELECT CASE f.event WHEN 'Application Installed' THEN 'INSTALL' ELSE 'ONBOARDING' END AS cohort_type,
           f.person_key
    FROM firsts f
    WHERE f.first_date = CAST(:stat_date AS date)
), opened AS (
    SELECT DISTINCT p.person_key, p.event_date
    FROM v_events_person p
    LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
    WHERE p.event_date BETWEEN CAST(:stat_date AS date) + 1
                           AND LEAST(CAST(:stat_date AS date) + 13, CAST(:as_of AS date))
      AND p.event = 'Application Opened'
      AND p.is_production
      AND NOT p.is_suspicious
      AND p.source = 'APP'
      AND x.user_id IS NULL
      AND p.person_key IN (SELECT c.person_key FROM cohort c)
), member AS (
    SELECT c.cohort_type,
           c.person_key,
           bool_or(o.event_date = CAST(:stat_date AS date) + 1) AS on_d1,
           bool_or(o.event_date = CAST(:stat_date AS date) + 3) AS on_d3,
           bool_or(o.event_date = CAST(:stat_date AS date) + 7) AS on_d7,
           bool_or(o.event_date BETWEEN CAST(:stat_date AS date) + 7 AND CAST(:stat_date AS date) + 13) AS on_w1
    FROM cohort c
    LEFT JOIN opened o ON o.person_key = c.person_key
    GROUP BY c.cohort_type, c.person_key
), types (cohort_type) AS (
    VALUES ('INSTALL'), ('ONBOARDING')
)
SELECT t.cohort_type,
       CAST(:stat_date AS date),
       count(m.person_key),
       CASE WHEN t.cohort_type = 'INSTALL' AND CAST(:stat_date AS date) + 1 <= CAST(:as_of AS date)
            THEN count(m.person_key) FILTER (WHERE m.on_d1) END,
       CASE WHEN t.cohort_type = 'INSTALL' AND CAST(:stat_date AS date) + 3 <= CAST(:as_of AS date)
            THEN count(m.person_key) FILTER (WHERE m.on_d3) END,
       CASE WHEN t.cohort_type = 'INSTALL' AND CAST(:stat_date AS date) + 7 <= CAST(:as_of AS date)
            THEN count(m.person_key) FILTER (WHERE m.on_d7) END,
       CASE WHEN t.cohort_type = 'ONBOARDING' AND CAST(:stat_date AS date) + 13 <= CAST(:as_of AS date)
            THEN count(m.person_key) FILTER (WHERE m.on_w1) END,
       CAST(:computed_at AS timestamptz)
FROM types t
LEFT JOIN member m ON m.cohort_type = t.cohort_type
GROUP BY t.cohort_type;
