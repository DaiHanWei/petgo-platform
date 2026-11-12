-- 指标族 placement（analytics-pipeline Story 4.4）：S5 运营位曝光、点击、点击人数、点击后 7 天已付款 Toko 订单数（FR-17 / FR-22）。
-- 写入 agg_daily_placement（日）、agg_weekly_placement（WIB 周一至周日）、agg_monthly_placement（WIB 自然月）。
-- 由 PlacementFamily 执行：每个日单元跑 compute + daily（period_start = period_end = 统计日）；
-- 周期单元对与重算窗口相交的每个周 / 月跑 compute + weekly / monthly（D-12：周期内重新从明细去重，不是日表相加）。
--
-- 参数（全部由 Java 传入，本文件不推算当前日期）：
--   :period_start  周期首日（WIB；日单元 = 统计日）
--   :period_end    周期末日（WIB，含；日单元 = 统计日）。明细只读到 LEAST(period_end, as_of)
--   :as_of         本次运行的基准日 = 运行时的 WIB 昨天
--   :computed_at   本次运行开始时刻（UTC），只写进 computed_at 列
-- 依赖（同一单元事务内由 Java 建好的临时表，ON COMMIT DROP）：
--   tmp_excluded_users(user_id)                                 4.1：业务库非 USER / 非 REAL 用户
--   tmp_placement_config(placement_type, placement_token, promotes_toko)
--                                                               业务库运营位配置经 PlacementTokoRule 判定后的结果（查不到 = 不在表里 → 空）
--   tmp_paid_orders(user_id, order_token, paid_at)              业务库窄查询：点击过的用户在点击日 0 点 WIB 至 + 8 天内的已付款订单
--                                                               （shop_orders.paid_at 非空，退款 / 取消仍算，D-13）
-- 以及 v_events_person（身份归属视图，D-10）。两库之间不做跨库 SQL：业务数据经 Java 写进临时表。
--
-- 事件映射（只按事件属性 placement_type + placement_token 统计；属性与事件名不一致、缺 token、token 超过 64 字符的事件跳过，
-- 条数由 PlacementFamily 写汇总日志）：
--   ops_popup      曝光 ops_popup_shown                 点击 ops_popup_tapped（ops_popup_closed 不算点击）
--   toko_banner    曝光 toko_banner_shown               点击 toko_banner_tapped
--   newbie_coupon  曝光 toko_newbie_coupon_popup_shown  点击 toko_newbie_coupon_popup_tapped 且 action = 'register'（close 不算）
-- 标准过滤：生产数据、非可疑、source = 'APP'、按人归属后排除非 USER / 非 REAL 用户（匿名事件经关联归到兽医 / 虚拟号也排除）。
-- 带单：同一运营位、同一用户的点击，之后 7 天内（点击时间 < paid_at ≤ 点击时间 + 7 天）的已付款订单按订单 token 去重计数；
--       匿名点击（归属不到用户）无法匹配订单。订单 token 只用来去重，不落汇总表。
-- 汇总表不写 user_id / 匿名 id / person_key；先 DELETE 再 INSERT，同一事务内，幂等。

-- @section compute
CREATE TEMP TABLE IF NOT EXISTS tmp_placement_period (
    placement_type   varchar(32)  NOT NULL,
    placement_token  varchar(64)  NOT NULL,
    promotes_toko    boolean,
    impressions      bigint       NOT NULL,
    clicks           bigint       NOT NULL,
    click_persons    bigint       NOT NULL,
    paid_orders_7d   bigint       NOT NULL
) ON COMMIT DROP;

DELETE FROM tmp_placement_period;

INSERT INTO tmp_placement_period (placement_type, placement_token, promotes_toko, impressions, clicks, click_persons,
                                  paid_orders_7d)
WITH ev AS (
    SELECT p.person_key,
           p.person_user_id,
           p.event_time,
           CASE WHEN p.event IN ('ops_popup_shown', 'toko_banner_shown', 'toko_newbie_coupon_popup_shown')
                THEN 'I' ELSE 'C' END AS kind,
           p.properties ->> 'placement_type' AS placement_type,
           p.properties ->> 'placement_token' AS placement_token
    FROM v_events_person p
    LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
    WHERE p.event_date BETWEEN CAST(:period_start AS date)
                           AND LEAST(CAST(:period_end AS date), CAST(:as_of AS date))
      AND p.event IN ('ops_popup_shown', 'ops_popup_tapped',
                      'toko_banner_shown', 'toko_banner_tapped',
                      'toko_newbie_coupon_popup_shown', 'toko_newbie_coupon_popup_tapped')
      AND p.is_production
      AND NOT p.is_suspicious
      AND p.source = 'APP'
      AND x.user_id IS NULL
      AND (   (p.event IN ('ops_popup_shown', 'ops_popup_tapped')
               AND p.properties ->> 'placement_type' = 'ops_popup')
           OR (p.event IN ('toko_banner_shown', 'toko_banner_tapped')
               AND p.properties ->> 'placement_type' = 'toko_banner')
           OR (p.event = 'toko_newbie_coupon_popup_shown'
               AND p.properties ->> 'placement_type' = 'newbie_coupon')
           OR (p.event = 'toko_newbie_coupon_popup_tapped'
               AND p.properties ->> 'placement_type' = 'newbie_coupon'
               AND p.properties ->> 'action' = 'register'))
      AND length(p.properties ->> 'placement_token') BETWEEN 1 AND 64
), agg AS (
    SELECT e.placement_type,
           e.placement_token,
           count(*) FILTER (WHERE e.kind = 'I') AS impressions,
           count(*) FILTER (WHERE e.kind = 'C') AS clicks,
           count(DISTINCT e.person_key) FILTER (WHERE e.kind = 'C') AS click_persons
    FROM ev e
    GROUP BY e.placement_type, e.placement_token
), orders AS (
    SELECT c.placement_type,
           c.placement_token,
           count(DISTINCT o.order_token) AS paid_orders
    FROM ev c
    JOIN tmp_paid_orders o ON o.user_id = c.person_user_id
                          AND o.paid_at > c.event_time
                          AND o.paid_at <= c.event_time + interval '7 days'
    WHERE c.kind = 'C'
    GROUP BY c.placement_type, c.placement_token
)
SELECT a.placement_type,
       a.placement_token,
       cfg.promotes_toko,
       a.impressions,
       a.clicks,
       a.click_persons,
       COALESCE(o.paid_orders, 0)
FROM agg a
LEFT JOIN orders o ON o.placement_type = a.placement_type AND o.placement_token = a.placement_token
LEFT JOIN tmp_placement_config cfg ON cfg.placement_type = a.placement_type
                                  AND cfg.placement_token = a.placement_token;

-- @section daily
DELETE FROM agg_daily_placement WHERE stat_date = CAST(:period_start AS date);

INSERT INTO agg_daily_placement (stat_date, placement_type, placement_token, promotes_toko, impressions, clicks,
                                 click_persons, paid_orders_7d, orders_window_complete, computed_at)
SELECT CAST(:period_start AS date),
       t.placement_type,
       t.placement_token,
       t.promotes_toko,
       t.impressions,
       t.clicks,
       t.click_persons,
       t.paid_orders_7d,
       CAST(:period_end AS date) + 7 <= CAST(:as_of AS date),
       CAST(:computed_at AS timestamptz)
FROM tmp_placement_period t;

-- @section weekly
DELETE FROM agg_weekly_placement WHERE period_start = CAST(:period_start AS date);

INSERT INTO agg_weekly_placement (period_start, placement_type, placement_token, promotes_toko, impressions, clicks,
                                  click_persons, paid_orders_7d, orders_window_complete, is_complete, computed_at)
SELECT CAST(:period_start AS date),
       t.placement_type,
       t.placement_token,
       t.promotes_toko,
       t.impressions,
       t.clicks,
       t.click_persons,
       t.paid_orders_7d,
       CAST(:period_end AS date) + 7 <= CAST(:as_of AS date),
       CAST(:period_end AS date) <= CAST(:as_of AS date),
       CAST(:computed_at AS timestamptz)
FROM tmp_placement_period t;

-- @section monthly
DELETE FROM agg_monthly_placement WHERE period_start = CAST(:period_start AS date);

INSERT INTO agg_monthly_placement (period_start, placement_type, placement_token, promotes_toko, impressions, clicks,
                                   click_persons, paid_orders_7d, orders_window_complete, is_complete, computed_at)
SELECT CAST(:period_start AS date),
       t.placement_type,
       t.placement_token,
       t.promotes_toko,
       t.impressions,
       t.clicks,
       t.click_persons,
       t.paid_orders_7d,
       CAST(:period_end AS date) + 7 <= CAST(:as_of AS date),
       CAST(:period_end AS date) <= CAST(:as_of AS date),
       CAST(:computed_at AS timestamptz)
FROM tmp_placement_period t;
