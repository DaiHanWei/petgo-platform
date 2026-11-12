-- 指标族 product（analytics-pipeline Story 4.6）：商品维度（电商 #8 / #9；D-13）。写入 agg_daily_product（WIB 日）。
-- 由 ProductFamily 执行：每个日单元跑 compute + daily，再跑 unknown（只取一个计数打日志）；每次运行回补 [昨天 − 2, 昨天]（D-6）。
--
-- 参数（全部由 Java 传入，本文件不推算当前日期）：
--   :stat_date    统计日（WIB）
--   :computed_at  本次运行开始时刻（UTC），只写进 computed_at 列
-- 依赖（同一单元事务内由 Java 建好的临时表，ON COMMIT DROP）：
--   tmp_excluded_users(user_id)   4.1：业务库非 USER / 非 REAL 用户（按人归属之后再排除一次）
--   tmp_product_snapshot(product_token, product_name, is_active, sellable_stock, paid_qty_30d, stock_captured_at)
--                                 业务库全部商品（含下架）一行：名称、上下架、可售库存 Σ max(actual − locked, 0)、
--                                 paid_at ∈ [stat_date − 29 0 点 WIB, stat_date + 1 0 点 WIB) 的已付款件数 Σ qty（下单人 USER + REAL，
--                                 不扣退款）、库存读取时间
-- 以及 v_events_person（身份归属视图，D-10）。两库之间不做跨库 SQL；商品一律用 public token（D-13）。
--
-- 口径（metric-definitions.yaml 电商#8 / 电商#9）：
--   detail_views = 当天 toko_product_detail_viewed 条数；add_to_cart = 当天 toko_add_to_cart_tapped 条数（按 product_token）。
--   行集合 = 业务库商品全集（left join 明细计数）；明细里有、业务库查不到的 token（已删除商品）丢弃，unknown 段计数打日志。
--   🔴 库存快照（sellable_stock、is_sold_out、stock_captured_at）与名称 / 上下架只在 (stat_date, product_token) 行首次插入时写：
--   ON CONFLICT DO UPDATE 只更新计数列、paid_qty_30d 与 computed_at，回补不覆盖已记录的快照。
-- 标准过滤：生产数据、非可疑、source = 'APP'、按人归属后排除非 USER / 非 REAL 用户。

-- @section compute
CREATE TEMP TABLE IF NOT EXISTS tmp_product_counts (
    product_token  text     PRIMARY KEY,
    detail_views   integer  NOT NULL,
    add_to_cart    integer  NOT NULL
) ON COMMIT DROP;

DELETE FROM tmp_product_counts;

INSERT INTO tmp_product_counts (product_token, detail_views, add_to_cart)
SELECT p.properties ->> 'product_token',
       count(*) FILTER (WHERE p.event = 'toko_product_detail_viewed'),
       count(*) FILTER (WHERE p.event = 'toko_add_to_cart_tapped')
FROM v_events_person p
LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
WHERE p.event_date = CAST(:stat_date AS date)
  AND p.event IN ('toko_product_detail_viewed', 'toko_add_to_cart_tapped')
  AND length(p.properties ->> 'product_token') > 0
  AND p.is_production
  AND NOT p.is_suspicious
  AND p.source = 'APP'
  AND x.user_id IS NULL
GROUP BY p.properties ->> 'product_token';

-- @section daily
INSERT INTO agg_daily_product (stat_date, product_token, product_name, is_active, detail_views, add_to_cart,
                               paid_qty_30d, sellable_stock, is_sold_out, stock_captured_at, computed_at)
SELECT CAST(:stat_date AS date),
       s.product_token,
       s.product_name,
       s.is_active,
       COALESCE(c.detail_views, 0),
       COALESCE(c.add_to_cart, 0),
       s.paid_qty_30d,
       s.sellable_stock,
       s.sellable_stock = 0,
       s.stock_captured_at,
       CAST(:computed_at AS timestamptz)
FROM tmp_product_snapshot s
LEFT JOIN tmp_product_counts c ON c.product_token = s.product_token
ON CONFLICT (stat_date, product_token) DO UPDATE SET
    detail_views = EXCLUDED.detail_views,
    add_to_cart = EXCLUDED.add_to_cart,
    paid_qty_30d = EXCLUDED.paid_qty_30d,
    computed_at = EXCLUDED.computed_at;

-- @section unknown
-- 明细里出现、业务库查不到的商品 token 个数（已删除商品；丢弃，只打日志）
SELECT count(*)
FROM tmp_product_counts c
LEFT JOIN tmp_product_snapshot s ON s.product_token = c.product_token
WHERE s.product_token IS NULL;
