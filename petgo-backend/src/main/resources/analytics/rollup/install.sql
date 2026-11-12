-- 指标族 install（analytics-pipeline Story 4.1）：新装数（S2）与注册数（S3 分子），分平台。
-- 写入 agg_daily_install。每个（指标族, WIB 日）一个事务，由 RollupService 执行，同一事务内推进 agg_progress。
--
-- 参数（全部由 Java 传入，本文件不推算日期）：
--   :stat_date       统计日（WIB 日期）
--   :lookback_start  「首次」的回看起点 = stat_date − analytics.rollup.install-lookback-days（默认 92 = 明细保留期）
--   :computed_at     本次运行开始时刻（UTC），只写进 computed_at 列
-- 依赖：v_events_person（身份归属视图，D-10）；tmp_excluded_users（本事务内建好的排除用户临时表）。
--
-- 方法：在 [lookback_start, stat_date] 内取 Application Installed / signup_succeeded，按事件 × person_key 取最早一条；
-- 最早一条落在 stat_date 的人计入当天。平台取该人最早那条事件的平台（= 当天最早一条），所以各平台相加 = all。
-- Application Updated 不计（升级不算新装，FR-3）。
-- 标准过滤同 activity.sql：生产数据、非可疑、source = 'APP'、按人归属后排除非 USER / 非 REAL 用户。
-- 「安装（未登录）→ 注册 → 次日打开」是同一个人：安装事件按 D-10 ② 归到之后首次登录的用户。
-- 🔴 Story 4.3 的「首次安装」片段必须与本文件 firsts 的口径一致（有 L1 断言两边人数相等）。
-- 'all' 行无论当天有无数据都写。先 DELETE 再 INSERT，同一事务内，幂等。

DELETE FROM agg_daily_install WHERE stat_date = CAST(:stat_date AS date);

INSERT INTO agg_daily_install (stat_date, platform, new_installs, signups, computed_at)
WITH base AS (
    SELECT p.event,
           p.person_key,
           p.event_date,
           p.event_time,
           p.message_id,
           COALESCE(left(p.platform, 16), 'unknown') AS platform
    FROM v_events_person p
    LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
    WHERE p.event_date BETWEEN CAST(:lookback_start AS date) AND CAST(:stat_date AS date)
      AND p.event IN ('Application Installed', 'signup_succeeded')
      AND p.is_production
      AND NOT p.is_suspicious
      AND p.source = 'APP'
      AND x.user_id IS NULL
), firsts AS (
    SELECT DISTINCT ON (b.event, b.person_key) b.event, b.person_key, b.event_date, b.platform
    FROM base b
    ORDER BY b.event, b.person_key, b.event_date, b.event_time, b.message_id
), today AS (
    SELECT f.event, f.platform FROM firsts f WHERE f.event_date = CAST(:stat_date AS date)
)
SELECT CAST(:stat_date AS date),
       CASE WHEN GROUPING(t.platform) = 1 THEN 'all' ELSE t.platform END,
       count(*) FILTER (WHERE t.event = 'Application Installed'),
       count(*) FILTER (WHERE t.event = 'signup_succeeded'),
       CAST(:computed_at AS timestamptz)
FROM today t
GROUP BY GROUPING SETS ((t.platform), ());
