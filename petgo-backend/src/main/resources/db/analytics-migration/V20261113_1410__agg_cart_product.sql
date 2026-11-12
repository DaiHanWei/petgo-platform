-- analytics-pipeline Story 4.6：购物车与商品（电商 #7 / #8 / #9；D-13）。
--   agg_daily_cart     购物车「加购件」按加购日（WIB）聚合：每个商品一行（scope = PRODUCT）+ 全体一行（scope = TOTAL）
--   agg_daily_product  商品维度每日计数（详情浏览、加购）+ 业务库库存快照与近 30 天已付款件数
-- 汇总 SQL：analytics/rollup/cart.sql（CartFamily，每次回补 [昨天 − 7, 昨天]：7 天观察窗）、
--           analytics/rollup/product.sql（ProductFamily，每次回补 [昨天 − 2, 昨天]）。
-- 购物车只存聚合：「是哪个用户、什么时间」的逐件明细只在 events_detail 保留期（3 个月）内可查。
-- 汇总表不含用户 id、匿名 id、人标识等可识别个人的字段；商品一律用 public token（D-13）；不建外键。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

-- ---------------------------------------------------------------- 购物车
CREATE TABLE agg_daily_cart (
    stat_date             date          NOT NULL,
    scope                 varchar(8)    NOT NULL,
    product_token         varchar(32)   NOT NULL DEFAULT '',
    add_events            integer       NOT NULL,
    add_items             integer       NOT NULL,
    removed_items         integer       NOT NULL,
    removed_manual        integer       NOT NULL,
    removed_invalid       integer       NOT NULL,
    purchased_items       integer       NOT NULL,
    ordered_unpaid_items  integer       NOT NULL,
    in_cart_items         integer       NOT NULL,
    dwell_lt_1h           integer       NOT NULL,
    dwell_1h_1d           integer       NOT NULL,
    dwell_1d_3d           integer       NOT NULL,
    dwell_3d_7d           integer       NOT NULL,
    dwell_p50_minutes     integer,
    cart_value_idr        bigint        NOT NULL,
    carts                 integer,
    items_per_cart_avg    numeric(8,2),
    is_final              boolean       NOT NULL,
    computed_at           timestamptz   NOT NULL,
    CONSTRAINT pk_agg_daily_cart PRIMARY KEY (stat_date, scope, product_token),
    CONSTRAINT ck_agg_daily_cart_scope CHECK (scope IN ('PRODUCT', 'TOTAL')
        AND ((scope = 'TOTAL') = (product_token = '')))
);

COMMENT ON TABLE agg_daily_cart IS '电商 #7 购物车停留：每件加购（同一人同一 SKU 当天第一次加购）按加购日聚合，观察窗 = 加购后到 加购日 + 7 天 24:00 WIB；只存聚合、不存人。按商品求和时只取 scope = ''PRODUCT''（TOTAL 行是全体值，混加会翻倍）；SQL 见 analytics/rollup/cart.sql';
COMMENT ON COLUMN agg_daily_cart.stat_date IS 'WIB 日期（Asia/Jakarta 自然日）：加购日';
COMMENT ON COLUMN agg_daily_cart.scope IS 'PRODUCT = 一个商品一行；TOTAL = 全体一行（product_token = ''''，含取不到商品 token 的件）';
COMMENT ON COLUMN agg_daily_cart.product_token IS '商品公开 token（shop_products.public_token）；TOTAL 行为空串';
COMMENT ON COLUMN agg_daily_cart.add_events IS '当天 toko_add_to_cart_tapped 条数（同一人同一 SKU 多次加购都计）';
COMMENT ON COLUMN agg_daily_cart.add_items IS '加购件数：同一人同一 SKU 当天多次加购只算一件，加购时刻 = 当天第一次';
COMMENT ON COLUMN agg_daily_cart.removed_items IS '去向 = 已移出：观察窗内最早发生的是同人同 SKU 的 toko_cart_item_removed（= removed_manual + removed_invalid + 其他 reason）';
COMMENT ON COLUMN agg_daily_cart.removed_manual IS '其中 reason = manual（手动删除）';
COMMENT ON COLUMN agg_daily_cart.removed_invalid IS '其中 reason = invalid_cleared（清空失效商品 / 结算页移除不可购买行）';
COMMENT ON COLUMN agg_daily_cart.purchased_items IS '去向 = 已购买：观察窗内最早发生的是同一用户、同 sku_token 的 checkout_source = CART 订单（created_at ≥ 加购时刻）且 paid_at 非空（D-13，退款 / 取消仍算）；「立即购买」不算';
COMMENT ON COLUMN agg_daily_cart.ordered_unpaid_items IS '去向 = 下单未付款：同上但 paid_at 为空（已离开购物车；超时取消的单也在这里）';
COMMENT ON COLUMN agg_daily_cart.in_cart_items IS '去向 = 仍在车里：观察窗内既无移出也无购物车订单（其他设备 / 旧版 App / 后台清空不可观测，也落在这里）';
COMMENT ON COLUMN agg_daily_cart.dwell_lt_1h IS '停留时长（加购 → 移出 / 下单）< 1 小时的件数；仍在车里的不入分桶';
COMMENT ON COLUMN agg_daily_cart.dwell_1h_1d IS '停留时长 [1 小时, 1 天) 的件数';
COMMENT ON COLUMN agg_daily_cart.dwell_1d_3d IS '停留时长 [1 天, 3 天) 的件数';
COMMENT ON COLUMN agg_daily_cart.dwell_3d_7d IS '停留时长 ≥ 3 天的件数（观察窗最长约 8 天，超过 7 天的也在这里）';
COMMENT ON COLUMN agg_daily_cart.dwell_p50_minutes IS '全部件停留时长中位数（分钟，取实际值）；仍在车里的按观察窗结束（未关闭时按数据截至时刻）截断；无件为空';
COMMENT ON COLUMN agg_daily_cart.cart_value_idr IS '购物车总价值（IDR）= Σ 单价 × 数量：单价 = 加购时 App 上报的 unit_price（非成交价），数量 = 观察窗内最后一次改数量的 qty_after，没有则用加购 qty';
COMMENT ON COLUMN agg_daily_cart.carts IS '仅 TOTAL 行：当天有加购件的去重人数（按 D-10 归属到人）；PRODUCT 行为空';
COMMENT ON COLUMN agg_daily_cart.items_per_cart_avg IS '仅 TOTAL 行：每车商品数 = add_items ÷ carts；PRODUCT 行为空';
COMMENT ON COLUMN agg_daily_cart.is_final IS 'true = 7 天观察窗已关闭（加购日 + 7 ≤ 计算基准日），之后不再重算';
COMMENT ON COLUMN agg_daily_cart.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 商品
CREATE TABLE agg_daily_product (
    stat_date          date          NOT NULL,
    product_token      varchar(32)   NOT NULL,
    product_name       varchar(120)  NOT NULL,
    is_active          boolean       NOT NULL,
    detail_views       integer       NOT NULL,
    add_to_cart        integer       NOT NULL,
    paid_qty_30d       bigint        NOT NULL,
    sellable_stock     bigint        NOT NULL,
    is_sold_out        boolean       NOT NULL,
    stock_captured_at  timestamptz   NOT NULL,
    computed_at        timestamptz   NOT NULL,
    CONSTRAINT pk_agg_daily_product PRIMARY KEY (stat_date, product_token)
);

COMMENT ON TABLE agg_daily_product IS '电商 #8 / #9 商品维度：每个业务库商品（含下架）每天一行；计数可按周期相加后排序（不预排名）；SQL 见 analytics/rollup/product.sql';
COMMENT ON COLUMN agg_daily_product.stat_date IS 'WIB 日期（Asia/Jakarta 自然日）';
COMMENT ON COLUMN agg_daily_product.product_token IS '商品公开 token（shop_products.public_token）';
COMMENT ON COLUMN agg_daily_product.product_name IS '业务库商品名称（行首次写入时的快照，非 PII）';
COMMENT ON COLUMN agg_daily_product.is_active IS '是否上架（行首次写入时的快照）';
COMMENT ON COLUMN agg_daily_product.detail_views IS '电商 #8 / #9：当天 toko_product_detail_viewed 条数';
COMMENT ON COLUMN agg_daily_product.add_to_cart IS '电商 #8：当天 toko_add_to_cart_tapped 条数';
COMMENT ON COLUMN agg_daily_product.paid_qty_30d IS '电商 #9：paid_at ∈ [统计日 − 29, 统计日] 的 WIB 日内的已付款订单行件数 Σ qty（下单人 USER + REAL；不扣退款，D-13）；回补时随之更新';
COMMENT ON COLUMN agg_daily_product.sellable_stock IS '快照：可售库存 Σ max(actual − locked, 0)（全部 SKU）；只在行首次写入时记录，回补不覆盖';
COMMENT ON COLUMN agg_daily_product.is_sold_out IS '快照：sellable_stock = 0；只在行首次写入时记录';
COMMENT ON COLUMN agg_daily_product.stock_captured_at IS '库存快照读取时间（UTC，汇总任务运行时，约次日 03:00 WIB）；只在行首次写入时记录';
COMMENT ON COLUMN agg_daily_product.computed_at IS '本行最近一次计算时间（UTC）';

-- ---------------------------------------------------------------- 授权
-- 角色名由 Flyway 占位符 readerRole 传入；本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_daily_cart', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_daily_product', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
