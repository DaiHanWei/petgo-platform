-- 指标族 toko（analytics-pipeline Story 4.5）：Toko 浏览、漏斗与加购转化（电商 #4 / #5 / #6、产品 #2；D-12 / D-13）。
-- 写入 agg_daily_toko（日）、agg_weekly_toko（WIB 周一至周日）、agg_monthly_toko（WIB 自然月）。
-- 由 TokoFamily 执行：每个日单元跑 compute + daily（period_start = period_end = 统计日）；
-- 周期单元对「已结束、结束日落在重算区间内」的每个周 / 月跑 compute + weekly / monthly
-- （D-12：周期内重新从明细去重、组 = 周期内首次浏览 toko_page 的人，不是日表相加）。日 / 周 / 月复用同一段 compute。
--
-- 参数（全部由 Java 传入，本文件不推算当前日期）：
--   :period_start        周期首日（WIB；日单元 = 统计日）
--   :period_end          周期末日（WIB，含；日单元 = 统计日）
--   :window_end          = period_end + 7（7 天窗口最晚落到的 WIB 日）；明细读取区间 event_date ∈ [period_start, window_end + 1]，
--                        窗口再用 event_time 精确比较
--   :is_final            7 天窗口已全部关闭（period_end ≤ 运行基准日 − 7，Java 判定）
--   :orders_paid         业务库 paid_at 落在周期内的订单数（下单人 USER + REAL；Java 窄查询，D-13）
--   :orders_created_biz  业务库 created_at 落在周期内的订单数（参照列，同上）
--   :computed_at         本次运行开始时刻（UTC），只写进 computed_at 列
-- 依赖（同一单元事务内由 Java 建好的临时表，ON COMMIT DROP）：
--   tmp_excluded_users(user_id)             4.1：业务库非 USER / 非 REAL 用户（按人归属之后再排除一次）
--   tmp_toko_paid_orders(user_id, paid_at)  业务库已付款订单（paid_at IS NOT NULL，退款 / 取消仍算；D-13），
--                                           只含本单元相关用户、paid_at 覆盖 [period_start 0 点 WIB, window_end + 1 0 点 WIB)
-- 以及 v_events_person（身份归属视图，D-10）。两库之间不做跨库 SQL。
--
-- 口径（metric-definitions.yaml 电商#4 / 电商#5 / 电商#6 / 产品#2）：
--   s1 screen_viewed 且 screen = 'toko_page'（'/shop' 路径模板不算）；s2 toko_product_shown；s3 toko_product_detail_viewed；
--   s4 toko_buy_now_tapped 或 toko_add_to_cart_tapped；s5 toko_order_submitted；
--   s6 toko_order_pay_tapped 或 toko_order_payment_succeeded（纯 PawCoin / 零元单不经过 pay_tapped，Story 3.4）；
--   s7 同一用户 paid_at ∈ [t6, t1 + 7 天] 的已付款订单（匿名人一律不通过）。
--   组 = 周期内（event_date）首次触发 s1 的人，t1 = 其周期内最早的 s1；t_k = [t_{k-1}, t1 + 7 天] 内第一条 s_k（跳步即止步）。
--   加购转化：周期内有 toko_add_to_cart_tapped 的人中，存在某次周期内加购（时刻 t）且 paid_at ∈ [t, t + 7 天] 的人（不要求含该商品）。
-- 标准过滤：生产数据、非可疑、source = 'APP'、按人归属后排除非 USER / 非 REAL 用户。汇总表不写可识别个人的列。
-- upsert（ON CONFLICT DO UPDATE），同一事务内，幂等。

-- @section compute
CREATE TEMP TABLE IF NOT EXISTS tmp_toko_events (
    person_key      text         NOT NULL,
    person_user_id  bigint,
    step            integer      NOT NULL,
    event           varchar(128) NOT NULL,
    event_time      timestamptz  NOT NULL,
    event_date      date         NOT NULL
) ON COMMIT DROP;

CREATE INDEX IF NOT EXISTS ix_tmp_toko_events_step ON tmp_toko_events (person_key, step, event_time);

DELETE FROM tmp_toko_events;

-- 先按 event_date 区间（分区裁剪）与事件名过滤进临时表，再逐步 join
INSERT INTO tmp_toko_events (person_key, person_user_id, step, event, event_time, event_date)
SELECT p.person_key,
       p.person_user_id,
       CASE p.event
            WHEN 'screen_viewed' THEN 1
            WHEN 'toko_product_shown' THEN 2
            WHEN 'toko_product_detail_viewed' THEN 3
            WHEN 'toko_buy_now_tapped' THEN 4
            WHEN 'toko_add_to_cart_tapped' THEN 4
            WHEN 'toko_order_submitted' THEN 5
            WHEN 'toko_order_pay_tapped' THEN 6
            WHEN 'toko_order_payment_succeeded' THEN 6
       END,
       p.event,
       p.event_time,
       p.event_date
FROM v_events_person p
LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
WHERE p.event_date BETWEEN CAST(:period_start AS date) AND CAST(:window_end AS date) + 1
  AND p.event IN ('screen_viewed', 'toko_product_shown', 'toko_product_detail_viewed', 'toko_buy_now_tapped',
                  'toko_add_to_cart_tapped', 'toko_order_submitted', 'toko_order_pay_tapped',
                  'toko_order_payment_succeeded')
  AND (p.event <> 'screen_viewed' OR p.properties ->> 'screen' = 'toko_page')
  AND p.is_production
  AND NOT p.is_suspicious
  AND p.source = 'APP'
  AND x.user_id IS NULL;

ANALYZE tmp_toko_events;

CREATE TEMP TABLE IF NOT EXISTS tmp_toko_period (
    toko_viewers               integer  NOT NULL,
    funnel_s1_toko_page        integer  NOT NULL,
    funnel_s2_product_shown    integer  NOT NULL,
    funnel_s3_detail_viewed    integer  NOT NULL,
    funnel_s4_buy_or_cart      integer  NOT NULL,
    funnel_s5_order_submitted  integer  NOT NULL,
    funnel_s6_pay_tapped       integer  NOT NULL,
    funnel_s7_paid             integer  NOT NULL,
    orders_submitted           integer  NOT NULL,
    atc_users                  integer  NOT NULL,
    atc_converted_users        integer  NOT NULL
) ON COMMIT DROP;

DELETE FROM tmp_toko_period;

INSERT INTO tmp_toko_period (toko_viewers, funnel_s1_toko_page, funnel_s2_product_shown, funnel_s3_detail_viewed,
                             funnel_s4_buy_or_cart, funnel_s5_order_submitted, funnel_s6_pay_tapped, funnel_s7_paid,
                             orders_submitted, atc_users, atc_converted_users)
WITH g AS (
    -- 组：周期内首次浏览 toko_page 的人；t1 = 周期内最早一次
    SELECT e.person_key,
           max(e.person_user_id) AS user_id,
           min(e.event_time) AS t1
    FROM tmp_toko_events e
    WHERE e.step = 1
      AND e.event_date BETWEEN CAST(:period_start AS date) AND CAST(:period_end AS date)
    GROUP BY e.person_key
), f2 AS (
    SELECT g.person_key, g.user_id, g.t1,
           (SELECT min(e.event_time) FROM tmp_toko_events e
            WHERE e.person_key = g.person_key AND e.step = 2
              AND e.event_time >= g.t1 AND e.event_time <= g.t1 + interval '7 days') AS t2
    FROM g
), f3 AS (
    SELECT f.person_key, f.user_id, f.t1, f.t2,
           (SELECT min(e.event_time) FROM tmp_toko_events e
            WHERE e.person_key = f.person_key AND e.step = 3
              AND e.event_time >= f.t2 AND e.event_time <= f.t1 + interval '7 days') AS t3
    FROM f2 f
), f4 AS (
    SELECT f.person_key, f.user_id, f.t1, f.t2, f.t3,
           (SELECT min(e.event_time) FROM tmp_toko_events e
            WHERE e.person_key = f.person_key AND e.step = 4
              AND e.event_time >= f.t3 AND e.event_time <= f.t1 + interval '7 days') AS t4
    FROM f3 f
), f5 AS (
    SELECT f.person_key, f.user_id, f.t1, f.t2, f.t3, f.t4,
           (SELECT min(e.event_time) FROM tmp_toko_events e
            WHERE e.person_key = f.person_key AND e.step = 5
              AND e.event_time >= f.t4 AND e.event_time <= f.t1 + interval '7 days') AS t5
    FROM f4 f
), f6 AS (
    SELECT f.person_key, f.user_id, f.t1, f.t2, f.t3, f.t4, f.t5,
           (SELECT min(e.event_time) FROM tmp_toko_events e
            WHERE e.person_key = f.person_key AND e.step = 6
              AND e.event_time >= f.t5 AND e.event_time <= f.t1 + interval '7 days') AS t6
    FROM f5 f
), f7 AS (
    -- 第 7 步：业务库已付款订单，同一用户、paid_at ∈ [t6, t1 + 7 天]；匿名人（user_id 为空）不通过
    SELECT f.t2, f.t3, f.t4, f.t5, f.t6,
           (f.t6 IS NOT NULL AND f.user_id IS NOT NULL
            AND EXISTS (SELECT 1 FROM tmp_toko_paid_orders o
                        WHERE o.user_id = f.user_id
                          AND o.paid_at >= f.t6 AND o.paid_at <= f.t1 + interval '7 days')) AS paid
    FROM f6 f
), funnel AS (
    SELECT count(*) AS s1,
           count(f.t2) AS s2,
           count(f.t3) AS s3,
           count(f.t4) AS s4,
           count(f.t5) AS s5,
           count(f.t6) AS s6,
           count(*) FILTER (WHERE f.paid) AS s7
    FROM f7 f
), submitted AS (
    -- 产品 #2 分母：事件条数，不去重人
    SELECT count(*) AS n
    FROM tmp_toko_events e
    WHERE e.event = 'toko_order_submitted'
      AND e.event_date BETWEEN CAST(:period_start AS date) AND CAST(:period_end AS date)
), atc AS (
    -- 电商 #5：周期内加购的人；存在某次加购（时刻 t）后 [t, t + 7 天] 内有已付款订单即算转化
    SELECT e.person_key,
           bool_or(e.person_user_id IS NOT NULL
                   AND EXISTS (SELECT 1 FROM tmp_toko_paid_orders o
                               WHERE o.user_id = e.person_user_id
                                 AND o.paid_at >= e.event_time
                                 AND o.paid_at <= e.event_time + interval '7 days')) AS converted
    FROM tmp_toko_events e
    WHERE e.event = 'toko_add_to_cart_tapped'
      AND e.event_date BETWEEN CAST(:period_start AS date) AND CAST(:period_end AS date)
    GROUP BY e.person_key
)
SELECT fu.s1,
       fu.s1,
       fu.s2,
       fu.s3,
       fu.s4,
       fu.s5,
       fu.s6,
       fu.s7,
       su.n,
       (SELECT count(*) FROM atc),
       (SELECT count(*) FROM atc a WHERE a.converted)
FROM funnel fu
CROSS JOIN submitted su;

-- @section daily
INSERT INTO agg_daily_toko (stat_date, toko_viewers, funnel_s1_toko_page, funnel_s2_product_shown,
                            funnel_s3_detail_viewed, funnel_s4_buy_or_cart, funnel_s5_order_submitted,
                            funnel_s6_pay_tapped, funnel_s7_paid, orders_submitted, orders_paid, orders_created_biz,
                            atc_users, atc_converted_users, is_final, computed_at)
SELECT CAST(:period_start AS date),
       t.toko_viewers, t.funnel_s1_toko_page, t.funnel_s2_product_shown, t.funnel_s3_detail_viewed,
       t.funnel_s4_buy_or_cart, t.funnel_s5_order_submitted, t.funnel_s6_pay_tapped, t.funnel_s7_paid,
       t.orders_submitted, CAST(:orders_paid AS integer), CAST(:orders_created_biz AS integer),
       t.atc_users, t.atc_converted_users,
       CAST(:is_final AS boolean),
       CAST(:computed_at AS timestamptz)
FROM tmp_toko_period t
ON CONFLICT (stat_date) DO UPDATE SET
    toko_viewers = EXCLUDED.toko_viewers,
    funnel_s1_toko_page = EXCLUDED.funnel_s1_toko_page,
    funnel_s2_product_shown = EXCLUDED.funnel_s2_product_shown,
    funnel_s3_detail_viewed = EXCLUDED.funnel_s3_detail_viewed,
    funnel_s4_buy_or_cart = EXCLUDED.funnel_s4_buy_or_cart,
    funnel_s5_order_submitted = EXCLUDED.funnel_s5_order_submitted,
    funnel_s6_pay_tapped = EXCLUDED.funnel_s6_pay_tapped,
    funnel_s7_paid = EXCLUDED.funnel_s7_paid,
    orders_submitted = EXCLUDED.orders_submitted,
    orders_paid = EXCLUDED.orders_paid,
    orders_created_biz = EXCLUDED.orders_created_biz,
    atc_users = EXCLUDED.atc_users,
    atc_converted_users = EXCLUDED.atc_converted_users,
    is_final = EXCLUDED.is_final,
    computed_at = EXCLUDED.computed_at;

-- @section weekly
INSERT INTO agg_weekly_toko (week_start, toko_viewers, funnel_s1_toko_page, funnel_s2_product_shown,
                             funnel_s3_detail_viewed, funnel_s4_buy_or_cart, funnel_s5_order_submitted,
                             funnel_s6_pay_tapped, funnel_s7_paid, orders_submitted, orders_paid, orders_created_biz,
                             atc_users, atc_converted_users, is_final, computed_at)
SELECT CAST(:period_start AS date),
       t.toko_viewers, t.funnel_s1_toko_page, t.funnel_s2_product_shown, t.funnel_s3_detail_viewed,
       t.funnel_s4_buy_or_cart, t.funnel_s5_order_submitted, t.funnel_s6_pay_tapped, t.funnel_s7_paid,
       t.orders_submitted, CAST(:orders_paid AS integer), CAST(:orders_created_biz AS integer),
       t.atc_users, t.atc_converted_users,
       CAST(:is_final AS boolean),
       CAST(:computed_at AS timestamptz)
FROM tmp_toko_period t
ON CONFLICT (week_start) DO UPDATE SET
    toko_viewers = EXCLUDED.toko_viewers,
    funnel_s1_toko_page = EXCLUDED.funnel_s1_toko_page,
    funnel_s2_product_shown = EXCLUDED.funnel_s2_product_shown,
    funnel_s3_detail_viewed = EXCLUDED.funnel_s3_detail_viewed,
    funnel_s4_buy_or_cart = EXCLUDED.funnel_s4_buy_or_cart,
    funnel_s5_order_submitted = EXCLUDED.funnel_s5_order_submitted,
    funnel_s6_pay_tapped = EXCLUDED.funnel_s6_pay_tapped,
    funnel_s7_paid = EXCLUDED.funnel_s7_paid,
    orders_submitted = EXCLUDED.orders_submitted,
    orders_paid = EXCLUDED.orders_paid,
    orders_created_biz = EXCLUDED.orders_created_biz,
    atc_users = EXCLUDED.atc_users,
    atc_converted_users = EXCLUDED.atc_converted_users,
    is_final = EXCLUDED.is_final,
    computed_at = EXCLUDED.computed_at;

-- @section monthly
INSERT INTO agg_monthly_toko (month_start, toko_viewers, funnel_s1_toko_page, funnel_s2_product_shown,
                              funnel_s3_detail_viewed, funnel_s4_buy_or_cart, funnel_s5_order_submitted,
                              funnel_s6_pay_tapped, funnel_s7_paid, orders_submitted, orders_paid, orders_created_biz,
                              atc_users, atc_converted_users, is_final, computed_at)
SELECT CAST(:period_start AS date),
       t.toko_viewers, t.funnel_s1_toko_page, t.funnel_s2_product_shown, t.funnel_s3_detail_viewed,
       t.funnel_s4_buy_or_cart, t.funnel_s5_order_submitted, t.funnel_s6_pay_tapped, t.funnel_s7_paid,
       t.orders_submitted, CAST(:orders_paid AS integer), CAST(:orders_created_biz AS integer),
       t.atc_users, t.atc_converted_users,
       CAST(:is_final AS boolean),
       CAST(:computed_at AS timestamptz)
FROM tmp_toko_period t
ON CONFLICT (month_start) DO UPDATE SET
    toko_viewers = EXCLUDED.toko_viewers,
    funnel_s1_toko_page = EXCLUDED.funnel_s1_toko_page,
    funnel_s2_product_shown = EXCLUDED.funnel_s2_product_shown,
    funnel_s3_detail_viewed = EXCLUDED.funnel_s3_detail_viewed,
    funnel_s4_buy_or_cart = EXCLUDED.funnel_s4_buy_or_cart,
    funnel_s5_order_submitted = EXCLUDED.funnel_s5_order_submitted,
    funnel_s6_pay_tapped = EXCLUDED.funnel_s6_pay_tapped,
    funnel_s7_paid = EXCLUDED.funnel_s7_paid,
    orders_submitted = EXCLUDED.orders_submitted,
    orders_paid = EXCLUDED.orders_paid,
    orders_created_biz = EXCLUDED.orders_created_biz,
    atc_users = EXCLUDED.atc_users,
    atc_converted_users = EXCLUDED.atc_converted_users,
    is_final = EXCLUDED.is_final,
    computed_at = EXCLUDED.computed_at;
