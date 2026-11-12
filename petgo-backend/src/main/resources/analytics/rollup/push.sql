-- 指标族 push（analytics-pipeline Story 4.4）：S5 推送发送数、打开次数、打开人数（FR-23）。
-- 写入 agg_daily_push（日）、agg_weekly_push（WIB 周一至周日）、agg_monthly_push（WIB 自然月）。
-- 由 PushFamily 执行：每个日单元跑 compute + daily（period_start = period_end = 统计日）；
-- 周期单元对与重算窗口相交的每个周 / 月跑 compute + weekly / monthly（D-12：打开人数周期内重新从明细去重，不是日表相加）。
--
-- 参数（全部由 Java 传入，本文件不推算当前日期）：
--   :period_start   周期首日（WIB；日单元 = 统计日）
--   :period_end     周期末日（WIB，含；日单元 = 统计日）。明细只读到 LEAST(period_end, as_of)
--   :as_of          本次运行的基准日 = 运行时的 WIB 昨天
--   :campaign_days  只用于 daily：运营推送累计打开人数的跟踪天数（analytics.rollup.push-campaign-days，默认 14；
--                   配置非法时为 0 = 停用累计打开人数与跟踪期补行）
--   :computed_at    本次运行开始时刻（UTC），只写进 computed_at 列
-- 依赖（同一单元事务内由 Java 建好的临时表，ON COMMIT DROP）：
--   tmp_excluded_users(user_id)   4.1：业务库非 USER / 非 REAL 用户（VET_REPLY 推送兽医点开也报 push_opened，在此排除；3-2 复审 R4 / Q5）
--   tmp_campaigns(campaign_token, sent_date, planned_count, recipient_count, promotes_toko)
--                                 只用于 daily：业务库 ops_notification_campaigns 窄查询（status 不是 PENDING / CANCELED），
--                                 sent_date = COALESCE(started_at, scheduled_at, created_at) 的 WIB 日期（Java 换算）
-- 以及 v_events_person（身份归属视图，D-10）。两库之间不做跨库 SQL。
--
-- 打开：push_opened（source = 'APP'），push_type = 属性 push_type（缺失 / 空串记 unknown，超长截到 40）；
--       campaign_token 只对 push_type = 'OPS_ANNOUNCEMENT' 取属性 campaign_token（缺失或超过 32 字符记空串），其他类型一律空串。
-- 发送：只有券过期推送有发送记录 —— COUPON_EXPIRING 行的 sent_count = toko_coupon_expiry_push_sent（source = 'SERVER'）条数；
--       其他系统推送没有发送记录（Story 4.4 A13 勘察），sent_count 为空、只记打开。
-- 运营推送（daily）：每个活动一行（发送当天、或发送后 campaign_days 天内、或当天有打开）；planned_count / recipient_count
--       为活动静态属性每行都带；campaign_opened_persons_cum = [发送日, LEAST(统计日, 发送日 + campaign_days − 1)] 内的去重打开人数。
-- COUPON_EXPIRING 行每天 / 每周期必写（发送 0 次也写 0：0 = 没发，无行 = 没算）；其他类型当期有打开才有行。
-- 标准过滤：生产数据、非可疑、按人归属后排除非 USER / 非 REAL 用户。汇总表不写 user_id / 匿名 id / person_key。
-- 先 DELETE 再 INSERT，同一事务内，幂等。

-- @section compute
CREATE TEMP TABLE IF NOT EXISTS tmp_push_period (
    push_type       varchar(40)  NOT NULL,
    campaign_token  varchar(32)  NOT NULL,
    sent_count      bigint,
    opened_count    bigint       NOT NULL,
    opened_persons  bigint       NOT NULL
) ON COMMIT DROP;

DELETE FROM tmp_push_period;

INSERT INTO tmp_push_period (push_type, campaign_token, sent_count, opened_count, opened_persons)
WITH opens AS (
    SELECT o.push_type,
           CASE WHEN o.push_type = 'OPS_ANNOUNCEMENT' AND length(o.raw_token) BETWEEN 1 AND 32
                THEN o.raw_token ELSE '' END AS campaign_token,
           o.person_key
    FROM (
        SELECT COALESCE(NULLIF(left(p.properties ->> 'push_type', 40), ''), 'unknown') AS push_type,
               p.properties ->> 'campaign_token' AS raw_token,
               p.person_key
        FROM v_events_person p
        LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
        WHERE p.event_date BETWEEN CAST(:period_start AS date)
                               AND LEAST(CAST(:period_end AS date), CAST(:as_of AS date))
          AND p.event = 'push_opened'
          AND p.is_production
          AND NOT p.is_suspicious
          AND p.source = 'APP'
          AND x.user_id IS NULL
    ) o
), opened AS (
    SELECT o.push_type,
           o.campaign_token,
           count(*) AS opened_count,
           count(DISTINCT o.person_key) AS opened_persons
    FROM opens o
    GROUP BY o.push_type, o.campaign_token
), sent AS (
    SELECT count(*) AS sent_count
    FROM v_events_person p
    LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
    WHERE p.event_date BETWEEN CAST(:period_start AS date)
                           AND LEAST(CAST(:period_end AS date), CAST(:as_of AS date))
      AND p.event = 'toko_coupon_expiry_push_sent'
      AND p.is_production
      AND NOT p.is_suspicious
      AND p.source = 'SERVER'
      AND x.user_id IS NULL
), keys AS (
    SELECT o.push_type, o.campaign_token FROM opened o
    UNION
    SELECT 'COUPON_EXPIRING', ''
)
SELECT k.push_type,
       k.campaign_token,
       CASE WHEN k.push_type = 'COUPON_EXPIRING' AND k.campaign_token = '' THEN (SELECT s.sent_count FROM sent s) END,
       COALESCE(o.opened_count, 0),
       COALESCE(o.opened_persons, 0)
FROM keys k
LEFT JOIN opened o ON o.push_type = k.push_type AND o.campaign_token = k.campaign_token;

-- @section daily
DELETE FROM agg_daily_push WHERE stat_date = CAST(:period_start AS date);

INSERT INTO agg_daily_push (stat_date, push_type, campaign_token, promotes_toko, planned_count, recipient_count,
                            sent_count, opened_count, opened_persons, campaign_opened_persons_cum, computed_at)
WITH day_rows AS (
    SELECT t.push_type, t.campaign_token, t.sent_count, t.opened_count, t.opened_persons
    FROM tmp_push_period t
    UNION ALL
    -- 当天发送、或仍在跟踪期内但当天没有打开的运营推送活动：补一行 0，让「该活动最新一行」始终是截至当天的累计值
    SELECT 'OPS_ANNOUNCEMENT', c.campaign_token, NULL, 0, 0
    FROM tmp_campaigns c
    WHERE (c.sent_date = CAST(:period_start AS date)
           OR CAST(:period_start AS date) BETWEEN c.sent_date
                                              AND c.sent_date + (CAST(:campaign_days AS int) - 1))
      AND NOT EXISTS (SELECT 1 FROM tmp_push_period t
                      WHERE t.push_type = 'OPS_ANNOUNCEMENT' AND t.campaign_token = c.campaign_token)
), cum AS (
    SELECT c.campaign_token,
           count(DISTINCT p.person_key) AS persons
    FROM tmp_campaigns c
    JOIN v_events_person p ON p.event_date BETWEEN c.sent_date
                                               AND LEAST(CAST(:period_start AS date),
                                                         c.sent_date + (CAST(:campaign_days AS int) - 1))
                          AND p.event = 'push_opened'
                          AND p.properties ->> 'push_type' = 'OPS_ANNOUNCEMENT'
                          AND p.properties ->> 'campaign_token' = c.campaign_token
    LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
    WHERE c.campaign_token IN (SELECT r.campaign_token FROM day_rows r WHERE r.push_type = 'OPS_ANNOUNCEMENT')
      AND p.is_production
      AND NOT p.is_suspicious
      AND p.source = 'APP'
      AND x.user_id IS NULL
    GROUP BY c.campaign_token
)
SELECT CAST(:period_start AS date),
       r.push_type,
       r.campaign_token,
       c.promotes_toko,
       c.planned_count,
       c.recipient_count,
       r.sent_count,
       r.opened_count,
       r.opened_persons,
       CASE WHEN c.campaign_token IS NOT NULL AND CAST(:campaign_days AS int) > 0
            THEN COALESCE(m.persons, 0) END,
       CAST(:computed_at AS timestamptz)
FROM day_rows r
LEFT JOIN tmp_campaigns c ON r.push_type = 'OPS_ANNOUNCEMENT' AND c.campaign_token = r.campaign_token
LEFT JOIN cum m ON m.campaign_token = c.campaign_token;

-- @section weekly
DELETE FROM agg_weekly_push WHERE period_start = CAST(:period_start AS date);

INSERT INTO agg_weekly_push (period_start, push_type, campaign_token, sent_count, opened_count, opened_persons,
                             is_complete, computed_at)
SELECT CAST(:period_start AS date),
       t.push_type,
       t.campaign_token,
       t.sent_count,
       t.opened_count,
       t.opened_persons,
       CAST(:period_end AS date) <= CAST(:as_of AS date),
       CAST(:computed_at AS timestamptz)
FROM tmp_push_period t;

-- @section monthly
DELETE FROM agg_monthly_push WHERE period_start = CAST(:period_start AS date);

INSERT INTO agg_monthly_push (period_start, push_type, campaign_token, sent_count, opened_count, opened_persons,
                              is_complete, computed_at)
SELECT CAST(:period_start AS date),
       t.push_type,
       t.campaign_token,
       t.sent_count,
       t.opened_count,
       t.opened_persons,
       CAST(:period_end AS date) <= CAST(:as_of AS date),
       CAST(:computed_at AS timestamptz)
FROM tmp_push_period t;
