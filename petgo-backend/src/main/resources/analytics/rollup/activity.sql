-- 指标族 activity（analytics-pipeline Story 4.1）：自建日活（S1 自建口径，含游客，按人）与登录用户日活，分平台。
-- 写入 agg_daily_activity。每个（指标族, WIB 日）一个事务，由 RollupService 执行，同一事务内推进 agg_progress。
--
-- 参数（全部由 Java 传入，本文件不推算日期）：
--   :stat_date    统计日（WIB 日期）
--   :computed_at  本次运行开始时刻（UTC），只写进 computed_at 列
-- 依赖：v_events_person（身份归属视图，D-10）；tmp_excluded_users（本事务内由 RollupContext 建好的排除用户临时表：
--       业务库 role <> 'USER' 或 account_type <> 'REAL' 的用户 id）。
--
-- 标准过滤：event_date = 统计日、生产数据、非可疑、source = 'APP'（H5 访客不是 App 设备）、按「人」归属后再排除
-- 非 USER / 非 REAL 用户（匿名事件经关联表归到兽医 / 虚拟号时，明细层的生产标记管不到，必须在这里再排一次）。
--
-- dau_person     count(DISTINCT person_key)：同一人的登录前、登录后、登出后事件合并为一人。
-- dau_logged_in  只数事件发生时已登录的（明细 user_id 非空），count(DISTINCT user_id)，不经关联表推断。
-- 平台：各平台一行 + platform = 'all' 一行；'all' 是整体单独去重（同一人两端都用，各平台各算一次、all 只算一次）。
-- 'all' 行无论当天有无数据都写（0 行 = 没算，0 值 = 没人）。
-- 先 DELETE 再 INSERT：某平台某天可能从有到无，单纯 upsert 删不掉旧行；整体在同一事务内，幂等。

DELETE FROM agg_daily_activity WHERE stat_date = CAST(:stat_date AS date);

INSERT INTO agg_daily_activity (stat_date, platform, dau_person, dau_logged_in, computed_at)
SELECT CAST(:stat_date AS date),
       CASE WHEN GROUPING(o.platform) = 1 THEN 'all' ELSE o.platform END,
       count(DISTINCT o.person_key),
       count(DISTINCT o.user_id),
       CAST(:computed_at AS timestamptz)
FROM (
    SELECT p.person_key,
           p.user_id,
           COALESCE(left(p.platform, 16), 'unknown') AS platform
    FROM v_events_person p
    LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
    WHERE p.event_date = CAST(:stat_date AS date)
      AND p.event = 'Application Opened'
      AND p.is_production
      AND NOT p.is_suspicious
      AND p.source = 'APP'
      AND x.user_id IS NULL
) o
GROUP BY GROUPING SETS ((o.platform), ());
