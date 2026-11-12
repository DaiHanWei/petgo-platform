-- 指标族 cart（analytics-pipeline Story 4.6）：购物车停留（电商 #7；D-13）。写入 agg_daily_cart（按加购日，WIB）。
-- 由 CartFamily 执行：每个日单元跑 compute + daily；每次运行回补 [昨天 − 7, 昨天]（7 天观察窗）。
--
-- 参数（全部由 Java 传入，本文件不推算当前日期）：
--   :stat_date        加购日（WIB）
--   :window_end       = stat_date + 7（观察窗最晚落到的 WIB 日）；明细读取区间 event_date ∈ [stat_date, window_end + 1]
--   :window_end_at    观察窗终点（不含）= window_end + 1 的 0 点 WIB（UTC 时刻，Java 算好）；移出 / 改数量 / 订单都要早于它
--   :observed_end_at  数据截至时刻 = min(window_end_at, 计算基准日 + 1 的 0 点 WIB)：仍在车里的件按它截断停留时长
--   :is_final         观察窗已关闭（window_end ≤ 计算基准日，Java 判定）
--   :computed_at      本次运行开始时刻（UTC），只写进 computed_at 列
-- 依赖（同一单元事务内由 Java 建好的临时表，ON COMMIT DROP）：
--   tmp_excluded_users(user_id)   4.1：业务库非 USER / 非 REAL 用户（按人归属之后再排除一次）
--   tmp_cart_orders(user_id, sku_token, product_token, created_at, paid_at)
--                                 业务库 checkout_source = 'CART' 的订单行（下单人 USER + REAL），
--                                 created_at ∈ [stat_date 0 点 WIB（或更早的加购时刻）, window_end_at)
-- 以及 v_events_person（身份归属视图，D-10）。两库之间不做跨库 SQL；商品 / SKU 一律用 public token（D-13）。
--
-- 算法（metric-definitions.yaml 电商#7）：
--   1. 加购件：toko_add_to_cart_tapped 按（人, sku_token）取 stat_date 当天第一条为一件，t_add = 该条时间；add_events 计全部条数。
--   2. 去向（观察窗 [t_add, window_end_at)，取最早发生者）：
--        REMOVED          同人同 SKU 的 toko_cart_item_removed（reason：manual / invalid_cleared）
--        PURCHASED        同一用户、同 sku_token 的购物车订单，created_at ≥ t_add，paid_at 非空
--        ORDERED_UNPAID   同上但 paid_at 为空
--        IN_CART          以上都没有（结束时刻取 observed_end_at，只用于中位数）
--   3. 停留分桶（t_end − t_add，分钟）：< 60、[60, 1440)、[1440, 4320)、≥ 4320；IN_CART 不入分桶（CartDwellBucket 与此一致）。
--   4. 数量 = 观察窗内（早于 t_end）最后一次 toko_cart_quantity_changed.qty_after，没有则用加购 qty，再没有按 1；
--      单价 = 加购 unit_price，缺则取同件后续移出 / 改数量事件带的 unit_price，再缺按 0（不计价值）。
--   5. 缺字段（Story 3.3 复审 R1：购物车重载期间移出 / 改数量可能只带 sku_token）：去向与数量只按 sku_token 匹配；
--      加购件缺 product_token 时依次取同 SKU 其他事件 / 购物车订单行的 product_token，仍取不到只进 TOTAL 行。
--   6. 匿名人（person_user_id 为空）的件只可能是 REMOVED 或 IN_CART。
-- 标准过滤：生产数据、非可疑、source = 'APP'、按人归属后排除非 USER / 非 REAL 用户。汇总表不写可识别个人的列。
-- 先删当天行再插（同一事务内，幂等；避免上次有、这次没有的商品行残留）。

-- @section compute
CREATE TEMP TABLE IF NOT EXISTS tmp_cart_events (
    person_key      text         NOT NULL,
    person_user_id  bigint,
    event           varchar(128) NOT NULL,
    event_time      timestamptz  NOT NULL,
    event_date      date         NOT NULL,
    sku_token       text         NOT NULL,
    product_token   text,
    qty             integer,
    qty_after       integer,
    unit_price      bigint,
    reason          text
) ON COMMIT DROP;

CREATE INDEX IF NOT EXISTS ix_tmp_cart_events_piece ON tmp_cart_events (person_key, sku_token, event, event_time);

DELETE FROM tmp_cart_events;

-- 先按 event_date 区间（分区裁剪）与事件名过滤进临时表；数值属性先校验格式再转型（坏值当缺失）
INSERT INTO tmp_cart_events (person_key, person_user_id, event, event_time, event_date, sku_token, product_token, qty,
                             qty_after, unit_price, reason)
SELECT p.person_key,
       p.person_user_id,
       p.event,
       p.event_time,
       p.event_date,
       p.properties ->> 'sku_token',
       CASE WHEN length(p.properties ->> 'product_token') BETWEEN 1 AND 32 THEN p.properties ->> 'product_token' END,
       CASE WHEN p.properties ->> 'qty' ~ '^[0-9]{1,6}$' THEN CAST(p.properties ->> 'qty' AS integer) END,
       CASE WHEN p.properties ->> 'qty_after' ~ '^[0-9]{1,6}$' THEN CAST(p.properties ->> 'qty_after' AS integer) END,
       CASE WHEN p.properties ->> 'unit_price' ~ '^[0-9]{1,12}$' THEN CAST(p.properties ->> 'unit_price' AS bigint) END,
       p.properties ->> 'reason'
FROM v_events_person p
LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id
WHERE p.event_date BETWEEN CAST(:stat_date AS date) AND CAST(:window_end AS date) + 1
  AND p.event IN ('toko_add_to_cart_tapped', 'toko_cart_item_removed', 'toko_cart_quantity_changed')
  AND length(p.properties ->> 'sku_token') > 0
  AND p.is_production
  AND NOT p.is_suspicious
  AND p.source = 'APP'
  AND x.user_id IS NULL;

ANALYZE tmp_cart_events;

CREATE TEMP TABLE IF NOT EXISTS tmp_cart_items (
    person_key      text         NOT NULL,
    user_id         bigint,
    sku_token       text         NOT NULL,
    product_token   text,
    t_add           timestamptz  NOT NULL,
    add_events      integer      NOT NULL,
    add_qty         integer,
    add_unit_price  bigint,
    removed_at      timestamptz,
    removed_reason  text,
    ordered_at      timestamptz,
    order_paid      boolean,
    outcome         varchar(16),
    t_end           timestamptz,
    dwell_minutes   integer,
    final_qty       integer,
    unit_price      bigint
) ON COMMIT DROP;

DELETE FROM tmp_cart_items;

-- 1. 加购件：同一人同一 SKU 当天第一条
INSERT INTO tmp_cart_items (person_key, user_id, sku_token, product_token, t_add, add_events, add_qty, add_unit_price)
SELECT DISTINCT ON (e.person_key, e.sku_token)
       e.person_key,
       e.person_user_id,
       e.sku_token,
       e.product_token,
       e.event_time,
       count(*) OVER (PARTITION BY e.person_key, e.sku_token),
       e.qty,
       e.unit_price
FROM tmp_cart_events e
WHERE e.event = 'toko_add_to_cart_tapped'
  AND e.event_date = CAST(:stat_date AS date)
ORDER BY e.person_key, e.sku_token, e.event_time;

-- 5. 商品 token 兜底：同 SKU 的其他事件，再取业务库购物车订单行（SKU → 商品是固定关系）
UPDATE tmp_cart_items i
SET product_token = COALESCE(
        (SELECT max(e.product_token) FROM tmp_cart_events e
         WHERE e.sku_token = i.sku_token AND e.product_token IS NOT NULL),
        (SELECT max(o.product_token) FROM tmp_cart_orders o
         WHERE o.sku_token = i.sku_token AND length(o.product_token) BETWEEN 1 AND 32))
WHERE i.product_token IS NULL;

-- 2. 最早一次移出（观察窗内）
UPDATE tmp_cart_items i
SET removed_at = r.event_time,
    removed_reason = r.reason
FROM (
    SELECT DISTINCT ON (i2.person_key, i2.sku_token) i2.person_key, i2.sku_token, e.event_time, e.reason
    FROM tmp_cart_items i2
    JOIN tmp_cart_events e ON e.person_key = i2.person_key AND e.sku_token = i2.sku_token
    WHERE e.event = 'toko_cart_item_removed'
      AND e.event_time >= i2.t_add
      AND e.event_time < CAST(:window_end_at AS timestamptz)
    ORDER BY i2.person_key, i2.sku_token, e.event_time
) r
WHERE r.person_key = i.person_key AND r.sku_token = i.sku_token;

-- 2. 最早一笔购物车订单（同一用户、同 SKU，观察窗内）；匿名人不匹配
UPDATE tmp_cart_items i
SET ordered_at = o.created_at,
    order_paid = o.paid
FROM (
    SELECT DISTINCT ON (i2.person_key, i2.sku_token) i2.person_key, i2.sku_token, c.created_at,
           c.paid_at IS NOT NULL AS paid
    FROM tmp_cart_items i2
    JOIN tmp_cart_orders c ON c.user_id = i2.user_id AND c.sku_token = i2.sku_token
    WHERE i2.user_id IS NOT NULL
      AND c.created_at >= i2.t_add
      AND c.created_at < CAST(:window_end_at AS timestamptz)
    ORDER BY i2.person_key, i2.sku_token, c.created_at
) o
WHERE o.person_key = i.person_key AND o.sku_token = i.sku_token;

-- 2. 去向与结束时刻：取最早发生者（同一时刻按订单算：下单即由服务端删车行）
UPDATE tmp_cart_items i
SET outcome = CASE
                  WHEN i.ordered_at IS NOT NULL AND (i.removed_at IS NULL OR i.ordered_at <= i.removed_at)
                      THEN CASE WHEN i.order_paid THEN 'PURCHASED' ELSE 'ORDERED_UNPAID' END
                  WHEN i.removed_at IS NOT NULL THEN 'REMOVED'
                  ELSE 'IN_CART'
              END,
    t_end = CASE
                WHEN i.ordered_at IS NOT NULL AND (i.removed_at IS NULL OR i.ordered_at <= i.removed_at)
                    THEN i.ordered_at
                WHEN i.removed_at IS NOT NULL THEN i.removed_at
                ELSE GREATEST(CAST(:observed_end_at AS timestamptz), i.t_add)
            END;

-- 3. 停留分钟；4. 数量与单价
UPDATE tmp_cart_items i
SET dwell_minutes = CAST(floor(extract(epoch FROM (i.t_end - i.t_add)) / 60) AS integer),
    final_qty = COALESCE(
        (SELECT e.qty_after FROM tmp_cart_events e
         WHERE e.person_key = i.person_key AND e.sku_token = i.sku_token
           AND e.event = 'toko_cart_quantity_changed'
           AND e.qty_after IS NOT NULL
           AND e.event_time >= i.t_add AND e.event_time < i.t_end
         ORDER BY e.event_time DESC
         LIMIT 1),
        i.add_qty,
        1),
    unit_price = COALESCE(
        i.add_unit_price,
        (SELECT e.unit_price FROM tmp_cart_events e
         WHERE e.person_key = i.person_key AND e.sku_token = i.sku_token
           AND e.event IN ('toko_cart_item_removed', 'toko_cart_quantity_changed')
           AND e.unit_price IS NOT NULL
           AND e.event_time >= i.t_add AND e.event_time <= i.t_end
         ORDER BY e.event_time DESC
         LIMIT 1),
        0);

-- @section daily
DELETE FROM agg_daily_cart WHERE stat_date = CAST(:stat_date AS date);

INSERT INTO agg_daily_cart (stat_date, scope, product_token, add_events, add_items, removed_items, removed_manual,
                            removed_invalid, purchased_items, ordered_unpaid_items, in_cart_items, dwell_lt_1h,
                            dwell_1h_1d, dwell_1d_3d, dwell_3d_7d, dwell_p50_minutes, cart_value_idr, carts,
                            items_per_cart_avg, is_final, computed_at)
WITH per_product AS (
    -- PRODUCT 行：每个取得到商品 token 的商品一行
    SELECT 'PRODUCT' AS scope,
           i.product_token,
           sum(i.add_events) AS add_events,
           count(*) AS add_items,
           count(*) FILTER (WHERE i.outcome = 'REMOVED') AS removed_items,
           count(*) FILTER (WHERE i.outcome = 'REMOVED' AND i.removed_reason = 'manual') AS removed_manual,
           count(*) FILTER (WHERE i.outcome = 'REMOVED' AND i.removed_reason = 'invalid_cleared') AS removed_invalid,
           count(*) FILTER (WHERE i.outcome = 'PURCHASED') AS purchased_items,
           count(*) FILTER (WHERE i.outcome = 'ORDERED_UNPAID') AS ordered_unpaid_items,
           count(*) FILTER (WHERE i.outcome = 'IN_CART') AS in_cart_items,
           count(*) FILTER (WHERE i.outcome <> 'IN_CART' AND i.dwell_minutes < 60) AS dwell_lt_1h,
           count(*) FILTER (WHERE i.outcome <> 'IN_CART' AND i.dwell_minutes >= 60 AND i.dwell_minutes < 1440)
               AS dwell_1h_1d,
           count(*) FILTER (WHERE i.outcome <> 'IN_CART' AND i.dwell_minutes >= 1440 AND i.dwell_minutes < 4320)
               AS dwell_1d_3d,
           count(*) FILTER (WHERE i.outcome <> 'IN_CART' AND i.dwell_minutes >= 4320) AS dwell_3d_7d,
           percentile_disc(0.5) WITHIN GROUP (ORDER BY i.dwell_minutes) AS dwell_p50_minutes,
           sum(i.unit_price * i.final_qty) AS cart_value_idr,
           CAST(NULL AS bigint) AS carts
    FROM tmp_cart_items i
    WHERE i.product_token IS NOT NULL
    GROUP BY i.product_token
), total AS (
    -- TOTAL 行：全体（含取不到商品 token 的件）；没有任何加购也写一行 0
    SELECT 'TOTAL' AS scope,
           '' AS product_token,
           COALESCE(sum(i.add_events), 0) AS add_events,
           count(*) AS add_items,
           count(*) FILTER (WHERE i.outcome = 'REMOVED') AS removed_items,
           count(*) FILTER (WHERE i.outcome = 'REMOVED' AND i.removed_reason = 'manual') AS removed_manual,
           count(*) FILTER (WHERE i.outcome = 'REMOVED' AND i.removed_reason = 'invalid_cleared') AS removed_invalid,
           count(*) FILTER (WHERE i.outcome = 'PURCHASED') AS purchased_items,
           count(*) FILTER (WHERE i.outcome = 'ORDERED_UNPAID') AS ordered_unpaid_items,
           count(*) FILTER (WHERE i.outcome = 'IN_CART') AS in_cart_items,
           count(*) FILTER (WHERE i.outcome <> 'IN_CART' AND i.dwell_minutes < 60) AS dwell_lt_1h,
           count(*) FILTER (WHERE i.outcome <> 'IN_CART' AND i.dwell_minutes >= 60 AND i.dwell_minutes < 1440)
               AS dwell_1h_1d,
           count(*) FILTER (WHERE i.outcome <> 'IN_CART' AND i.dwell_minutes >= 1440 AND i.dwell_minutes < 4320)
               AS dwell_1d_3d,
           count(*) FILTER (WHERE i.outcome <> 'IN_CART' AND i.dwell_minutes >= 4320) AS dwell_3d_7d,
           percentile_disc(0.5) WITHIN GROUP (ORDER BY i.dwell_minutes) AS dwell_p50_minutes,
           COALESCE(sum(i.unit_price * i.final_qty), 0) AS cart_value_idr,
           count(DISTINCT i.person_key) AS carts
    FROM tmp_cart_items i
), g AS (
    SELECT * FROM per_product
    UNION ALL
    SELECT * FROM total
)
SELECT CAST(:stat_date AS date),
       g.scope,
       g.product_token,
       g.add_events,
       g.add_items,
       g.removed_items,
       g.removed_manual,
       g.removed_invalid,
       g.purchased_items,
       g.ordered_unpaid_items,
       g.in_cart_items,
       g.dwell_lt_1h,
       g.dwell_1h_1d,
       g.dwell_1d_3d,
       g.dwell_3d_7d,
       g.dwell_p50_minutes,
       -- 单价 / 数量是客户端上报值：求和是 numeric，封顶到 bigint / numeric(8,2) 上限，防伪造大值让整族汇总持续失败（复审 R1）
       LEAST(g.cart_value_idr, 9223372036854775807),
       g.carts,
       CASE WHEN g.scope = 'TOTAL' THEN LEAST(round(g.add_items::numeric / NULLIF(g.carts, 0), 2), 999999.99) END,
       CAST(:is_final AS boolean),
       CAST(:computed_at AS timestamptz)
FROM g;
