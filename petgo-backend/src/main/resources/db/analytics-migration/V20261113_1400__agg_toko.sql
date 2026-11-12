-- analytics-pipeline Story 4.5：Toko 浏览、漏斗与加购转化（电商 #4 / #5 / #6、产品 #2；D-12 / D-13）。
--   agg_daily_toko     按 WIB 日
--   agg_weekly_toko    按 WIB 周（周一至周日），周期内重新从明细去重（D-12，不是日表相加）
--   agg_monthly_toko   按 WIB 自然月，同上
-- 汇总 SQL：analytics/rollup/toko.sql（TokoFamily 执行）。依赖 7 天窗口：每次运行回补最近 8 个 WIB 日
-- （[昨天 − 7, 昨天]），周 / 月只在结束后计算，结束日落在回补区间内的都重算。
-- 汇总表不含用户 id、匿名 id、人标识等可识别个人的字段；不建外键。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

-- ---------------------------------------------------------------- 日
CREATE TABLE agg_daily_toko (
    stat_date                  date         PRIMARY KEY,
    toko_viewers               integer      NOT NULL,
    funnel_s1_toko_page        integer      NOT NULL,
    funnel_s2_product_shown    integer      NOT NULL,
    funnel_s3_detail_viewed    integer      NOT NULL,
    funnel_s4_buy_or_cart      integer      NOT NULL,
    funnel_s5_order_submitted  integer      NOT NULL,
    funnel_s6_pay_tapped       integer      NOT NULL,
    funnel_s7_paid             integer      NOT NULL,
    orders_submitted           integer      NOT NULL,
    orders_paid                integer      NOT NULL,
    orders_created_biz         integer      NOT NULL,
    atc_users                  integer      NOT NULL,
    atc_converted_users        integer      NOT NULL,
    is_final                   boolean      NOT NULL,
    computed_at                timestamptz  NOT NULL
);

COMMENT ON TABLE agg_daily_toko IS 'Toko 浏览、漏斗与加购转化（电商 #4 / #5 / #6、产品 #2），按 WIB 日；SQL 见 analytics/rollup/toko.sql';
COMMENT ON COLUMN agg_daily_toko.stat_date IS 'WIB 日期（Asia/Jakarta 自然日）';
COMMENT ON COLUMN agg_daily_toko.toko_viewers IS '电商 #4：当期有 screen_viewed 且 screen = ''toko_page'' 的去重人数（按 D-10 归属到人）';
COMMENT ON COLUMN agg_daily_toko.funnel_s1_toko_page IS '电商 #6 第 1 步（组人数）：当期首次浏览 toko_page 的人，t1 = 当期最早一次';
COMMENT ON COLUMN agg_daily_toko.funnel_s2_product_shown IS '电商 #6 第 2 步：[t1, t1 + 7 天] 内、不早于上一步有 toko_product_shown 的人数';
COMMENT ON COLUMN agg_daily_toko.funnel_s3_detail_viewed IS '电商 #6 第 3 步：toko_product_detail_viewed（按顺序，跳步不算）';
COMMENT ON COLUMN agg_daily_toko.funnel_s4_buy_or_cart IS '电商 #6 第 4 步：toko_buy_now_tapped 或 toko_add_to_cart_tapped';
COMMENT ON COLUMN agg_daily_toko.funnel_s5_order_submitted IS '电商 #6 第 5 步：toko_order_submitted';
COMMENT ON COLUMN agg_daily_toko.funnel_s6_pay_tapped IS '电商 #6 第 6 步：toko_order_pay_tapped 或 toko_order_payment_succeeded（纯 PawCoin / 零元单）';
COMMENT ON COLUMN agg_daily_toko.funnel_s7_paid IS '电商 #6 第 7 步：业务库同一用户 paid_at ∈ [t6, t1 + 7 天] 的已付款订单（D-13）；匿名人不计';
COMMENT ON COLUMN agg_daily_toko.orders_submitted IS '产品 #2 分母：当期 toko_order_submitted 事件条数（不去重人；只来自 V1.5.0+ 生产设备）';
COMMENT ON COLUMN agg_daily_toko.orders_paid IS '产品 #2 分子 / 电商 #1：业务库 paid_at 的 WIB 日期落在当期的订单数（下单人 USER + REAL；退款 / 取消仍算）';
COMMENT ON COLUMN agg_daily_toko.orders_created_biz IS '参照列：业务库 created_at 落在当期的订单数（下单人 USER + REAL）';
COMMENT ON COLUMN agg_daily_toko.atc_users IS '电商 #5 分母 / 电商 #7 加购人数：当期有 toko_add_to_cart_tapped 的去重人数';
COMMENT ON COLUMN agg_daily_toko.atc_converted_users IS '电商 #5 分子：其中存在某次当期加购（时刻 t）且 paid_at ∈ [t, t + 7 天] 有已付款订单的人数';
COMMENT ON COLUMN agg_daily_toko.is_final IS 'true = 7 天窗口已全部关闭（统计日 ≤ 计算基准日 − 7），之后不再重算';
COMMENT ON COLUMN agg_daily_toko.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 周
CREATE TABLE agg_weekly_toko (
    week_start                 date         PRIMARY KEY,
    toko_viewers               integer      NOT NULL,
    funnel_s1_toko_page        integer      NOT NULL,
    funnel_s2_product_shown    integer      NOT NULL,
    funnel_s3_detail_viewed    integer      NOT NULL,
    funnel_s4_buy_or_cart      integer      NOT NULL,
    funnel_s5_order_submitted  integer      NOT NULL,
    funnel_s6_pay_tapped       integer      NOT NULL,
    funnel_s7_paid             integer      NOT NULL,
    orders_submitted           integer      NOT NULL,
    orders_paid                integer      NOT NULL,
    orders_created_biz         integer      NOT NULL,
    atc_users                  integer      NOT NULL,
    atc_converted_users        integer      NOT NULL,
    is_final                   boolean      NOT NULL,
    computed_at                timestamptz  NOT NULL,
    CONSTRAINT ck_agg_weekly_toko_monday CHECK (extract(isodow FROM week_start) = 1)
);

COMMENT ON TABLE agg_weekly_toko IS 'Toko 浏览、漏斗与加购转化，按 WIB 周（周一至周日）；人数在周内重新去重（D-12），不等于日表相加；周结束后才计算';
COMMENT ON COLUMN agg_weekly_toko.week_start IS 'WIB 日期：该周周一';
COMMENT ON COLUMN agg_weekly_toko.funnel_s1_toko_page IS '组 = 该周内首次浏览 toko_page 的人（不是日组相加），t1 = 周内最早一次；其余各步同日表';
COMMENT ON COLUMN agg_weekly_toko.is_final IS 'true = 7 天窗口已全部关闭（周日 ≤ 计算基准日 − 7）';
COMMENT ON COLUMN agg_weekly_toko.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 月
CREATE TABLE agg_monthly_toko (
    month_start                date         PRIMARY KEY,
    toko_viewers               integer      NOT NULL,
    funnel_s1_toko_page        integer      NOT NULL,
    funnel_s2_product_shown    integer      NOT NULL,
    funnel_s3_detail_viewed    integer      NOT NULL,
    funnel_s4_buy_or_cart      integer      NOT NULL,
    funnel_s5_order_submitted  integer      NOT NULL,
    funnel_s6_pay_tapped       integer      NOT NULL,
    funnel_s7_paid             integer      NOT NULL,
    orders_submitted           integer      NOT NULL,
    orders_paid                integer      NOT NULL,
    orders_created_biz         integer      NOT NULL,
    atc_users                  integer      NOT NULL,
    atc_converted_users        integer      NOT NULL,
    is_final                   boolean      NOT NULL,
    computed_at                timestamptz  NOT NULL,
    CONSTRAINT ck_agg_monthly_toko_month CHECK (month_start = date_trunc('month', month_start)::date)
);

COMMENT ON TABLE agg_monthly_toko IS 'Toko 浏览、漏斗与加购转化，按 WIB 自然月；人数在月内重新去重（D-12），不等于日表相加；月结束后才计算';
COMMENT ON COLUMN agg_monthly_toko.month_start IS 'WIB 日期：当月 1 日';
COMMENT ON COLUMN agg_monthly_toko.funnel_s1_toko_page IS '组 = 该月内首次浏览 toko_page 的人（不是日组相加），t1 = 月内最早一次；其余各步同日表';
COMMENT ON COLUMN agg_monthly_toko.is_final IS 'true = 7 天窗口已全部关闭（月末 ≤ 计算基准日 − 7）';
COMMENT ON COLUMN agg_monthly_toko.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 授权
-- 角色名由 Flyway 占位符 readerRole 传入；本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_daily_toko', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_weekly_toko', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_monthly_toko', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
