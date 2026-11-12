-- analytics-pipeline Story 4.4：运营位与推送效果（S5，FR-17 / FR-22 / FR-23）。只在埋点库执行。D-5 / D-6 / D-10 / D-12 / D-13。
-- 本文件建：
--   agg_daily_placement / agg_weekly_placement / agg_monthly_placement   运营位曝光、点击、点击人数、点击后 7 天已付款 Toko 订单数
--   agg_daily_push / agg_weekly_push / agg_monthly_push                  推送发送数、打开次数、打开人数（运营推送另带计划 / 接收人数）
-- 周表 / 月表的「人数」与「订单数」是周期内重新从明细去重计算的（D-12），不是日表相加；次数类（曝光、点击、打开、发送）为周期内求和。
-- 汇总表只存 token 与计数，不含用户标识、匿名 id、归属键、订单 token 等可识别个人的字段；不建外键。
-- 比率（点击率、打开率）不入库，按 metric-definitions.yaml 里的口径由本表现算。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

-- ---------------------------------------------------------------- 运营位：日表
CREATE TABLE agg_daily_placement (
    stat_date               date         NOT NULL,
    placement_type          varchar(32)  NOT NULL,
    placement_token         varchar(64)  NOT NULL,
    promotes_toko           boolean,
    impressions             bigint       NOT NULL,
    clicks                  bigint       NOT NULL,
    click_persons           bigint       NOT NULL,
    paid_orders_7d          bigint       NOT NULL,
    orders_window_complete  boolean      NOT NULL,
    computed_at             timestamptz  NOT NULL,
    CONSTRAINT pk_agg_daily_placement PRIMARY KEY (stat_date, placement_type, placement_token),
    CONSTRAINT ck_agg_daily_placement_type CHECK (placement_type IN ('ops_popup', 'toko_banner', 'newbie_coupon'))
);

COMMENT ON TABLE agg_daily_placement IS 'S5 运营位（弹窗 / Toko 横幅 / 新人券弹窗），按 WIB 日 × 运营位一行；当天有曝光或点击才有行。点击率 = clicks ÷ impressions 现算。SQL 见 analytics/rollup/placement.sql';
COMMENT ON COLUMN agg_daily_placement.stat_date IS 'WIB 日期（Asia/Jakarta 自然日，按事件 event_date）';
COMMENT ON COLUMN agg_daily_placement.placement_type IS '运营位类型（事件属性 placement_type）：ops_popup 运营弹窗 / toko_banner Toko 横幅 / newbie_coupon 新人券弹窗';
COMMENT ON COLUMN agg_daily_placement.placement_token IS '运营位公开 token（事件属性 placement_token）：ops_popups.public_token / shop_banners.public_token / 新人券模板 shop_coupons.public_token；不是用户标识';
COMMENT ON COLUMN agg_daily_placement.promotes_toko IS '是否推广 Toko（FR-17 第 3 条），按计算当时的业务库配置判定：弹窗跳商品 / 品类 / APP_PAGE=TOKO 为 true，NONE / EXTERNAL / 其他页面为 false；横幅与新人券恒为 true；配置已删、查不到为空';
COMMENT ON COLUMN agg_daily_placement.impressions IS '曝光次数：ops_popup_shown / toko_banner_shown / toko_newbie_coupon_popup_shown 条数（露出超过 50% 计一次）';
COMMENT ON COLUMN agg_daily_placement.clicks IS '点击次数：ops_popup_tapped / toko_banner_tapped / toko_newbie_coupon_popup_tapped 且 action = register 的条数';
COMMENT ON COLUMN agg_daily_placement.click_persons IS '点击人数：当天点击的去重人数（按 D-10 归属到人）；不能跨天相加，周 / 月见 agg_weekly_placement / agg_monthly_placement';
COMMENT ON COLUMN agg_daily_placement.paid_orders_7d IS '带单（S5，FR-22）：当天点击过该运营位的登录用户，在每次点击之后 7 天内（点击时间 < paid_at ≤ 点击时间 + 7 天）付款的 Toko 订单数（shop_orders.paid_at 非空，退款 / 取消仍算），按订单去重；匿名且之后从未登录的点击无法匹配';
COMMENT ON COLUMN agg_daily_placement.orders_window_complete IS 'true = stat_date + 7 ≤ 计算基准日，7 天带单窗口已走完；false = 带单数仍会随之后的付款增加';
COMMENT ON COLUMN agg_daily_placement.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 运营位：周表 / 月表（D-12）
CREATE TABLE agg_weekly_placement (
    period_start            date         NOT NULL,
    placement_type          varchar(32)  NOT NULL,
    placement_token         varchar(64)  NOT NULL,
    promotes_toko           boolean,
    impressions             bigint       NOT NULL,
    clicks                  bigint       NOT NULL,
    click_persons           bigint       NOT NULL,
    paid_orders_7d          bigint       NOT NULL,
    orders_window_complete  boolean      NOT NULL,
    is_complete             boolean      NOT NULL,
    computed_at             timestamptz  NOT NULL,
    CONSTRAINT pk_agg_weekly_placement PRIMARY KEY (period_start, placement_type, placement_token),
    CONSTRAINT ck_agg_weekly_placement_type CHECK (placement_type IN ('ops_popup', 'toko_banner', 'newbie_coupon')),
    CONSTRAINT ck_agg_weekly_placement_monday CHECK (extract(isodow FROM period_start) = 1)
);

COMMENT ON TABLE agg_weekly_placement IS 'S5 运营位按 WIB 周（周一至周日）：曝光 / 点击为周内求和，点击人数与带单数为周内重新去重（D-12，不是日表相加）。SQL 见 analytics/rollup/placement.sql';
COMMENT ON COLUMN agg_weekly_placement.period_start IS '周一（WIB）';
COMMENT ON COLUMN agg_weekly_placement.placement_type IS '同 agg_daily_placement.placement_type';
COMMENT ON COLUMN agg_weekly_placement.placement_token IS '同 agg_daily_placement.placement_token';
COMMENT ON COLUMN agg_weekly_placement.promotes_toko IS '同 agg_daily_placement.promotes_toko（按计算当时的配置）';
COMMENT ON COLUMN agg_weekly_placement.impressions IS '周内曝光次数之和';
COMMENT ON COLUMN agg_weekly_placement.clicks IS '周内点击次数之和';
COMMENT ON COLUMN agg_weekly_placement.click_persons IS '周内去重点击人数（同一人多天点击只算 1 人）';
COMMENT ON COLUMN agg_weekly_placement.paid_orders_7d IS '周内各次点击之后 7 天内付款的 Toko 订单数，按订单去重（同一订单被周内多次点击匹配只算 1 单）';
COMMENT ON COLUMN agg_weekly_placement.orders_window_complete IS 'true = 周末 + 7 ≤ 计算基准日，周内全部点击的带单窗口已走完';
COMMENT ON COLUMN agg_weekly_placement.is_complete IS 'true = 周已结束（周日 ≤ 计算基准日）；false = 周中「截至昨天」的值';
COMMENT ON COLUMN agg_weekly_placement.computed_at IS '本行计算时间（UTC）';

CREATE TABLE agg_monthly_placement (
    period_start            date         NOT NULL,
    placement_type          varchar(32)  NOT NULL,
    placement_token         varchar(64)  NOT NULL,
    promotes_toko           boolean,
    impressions             bigint       NOT NULL,
    clicks                  bigint       NOT NULL,
    click_persons           bigint       NOT NULL,
    paid_orders_7d          bigint       NOT NULL,
    orders_window_complete  boolean      NOT NULL,
    is_complete             boolean      NOT NULL,
    computed_at             timestamptz  NOT NULL,
    CONSTRAINT pk_agg_monthly_placement PRIMARY KEY (period_start, placement_type, placement_token),
    CONSTRAINT ck_agg_monthly_placement_type CHECK (placement_type IN ('ops_popup', 'toko_banner', 'newbie_coupon')),
    CONSTRAINT ck_agg_monthly_placement_month CHECK (period_start = date_trunc('month', period_start)::date)
);

COMMENT ON TABLE agg_monthly_placement IS 'S5 运营位按 WIB 自然月：曝光 / 点击为月内求和，点击人数与带单数为月内重新去重（D-12，不是日表相加）。SQL 见 analytics/rollup/placement.sql';
COMMENT ON COLUMN agg_monthly_placement.period_start IS '当月 1 日（WIB 自然月）';
COMMENT ON COLUMN agg_monthly_placement.placement_type IS '同 agg_daily_placement.placement_type';
COMMENT ON COLUMN agg_monthly_placement.placement_token IS '同 agg_daily_placement.placement_token';
COMMENT ON COLUMN agg_monthly_placement.promotes_toko IS '同 agg_daily_placement.promotes_toko（按计算当时的配置）';
COMMENT ON COLUMN agg_monthly_placement.impressions IS '月内曝光次数之和';
COMMENT ON COLUMN agg_monthly_placement.clicks IS '月内点击次数之和';
COMMENT ON COLUMN agg_monthly_placement.click_persons IS '月内去重点击人数（同一人多天点击只算 1 人）';
COMMENT ON COLUMN agg_monthly_placement.paid_orders_7d IS '月内各次点击之后 7 天内付款的 Toko 订单数，按订单去重';
COMMENT ON COLUMN agg_monthly_placement.orders_window_complete IS 'true = 月末 + 7 ≤ 计算基准日，月内全部点击的带单窗口已走完';
COMMENT ON COLUMN agg_monthly_placement.is_complete IS 'true = 月份已结束（月末 ≤ 计算基准日）；false = 月中「截至昨天」的值';
COMMENT ON COLUMN agg_monthly_placement.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 推送：日表
CREATE TABLE agg_daily_push (
    stat_date                    date         NOT NULL,
    push_type                    varchar(40)  NOT NULL,
    campaign_token               varchar(32)  NOT NULL DEFAULT '',
    promotes_toko                boolean,
    planned_count                int,
    recipient_count              int,
    sent_count                   bigint,
    opened_count                 bigint       NOT NULL,
    opened_persons               bigint       NOT NULL,
    campaign_opened_persons_cum  bigint,
    computed_at                  timestamptz  NOT NULL,
    CONSTRAINT pk_agg_daily_push PRIMARY KEY (stat_date, push_type, campaign_token)
);

COMMENT ON TABLE agg_daily_push IS 'S5 推送（FR-23），按 WIB 日 × 推送类型（× 运营推送活动）一行。运营推送一条一行；券过期推送每天必有一行（发送数可为 0）；其他系统推送当天有打开才有行。打开率 = 打开人数 ÷ 接收人数现算。SQL 见 analytics/rollup/push.sql';
COMMENT ON COLUMN agg_daily_push.stat_date IS 'WIB 日期（Asia/Jakarta 自然日，按事件 event_date）';
COMMENT ON COLUMN agg_daily_push.push_type IS 'push_opened 的 push_type（推送载荷 type 原值：NotificationType 枚举名或 VET_REPLY；缺失为 unknown）。运营推送 = OPS_ANNOUNCEMENT，券过期 = COUPON_EXPIRING';
COMMENT ON COLUMN agg_daily_push.campaign_token IS '运营推送活动 ops_notification_campaigns.public_token（push_opened 的 campaign_token）；非运营推送为空串（便于做主键）；运营推送打开事件缺 token 也记空串';
COMMENT ON COLUMN agg_daily_push.promotes_toko IS '只用于运营推送：活动跳转目标是否推广 Toko（规则同 agg_daily_placement.promotes_toko，按计算当时的配置）；活动查不到或非运营推送为空';
COMMENT ON COLUMN agg_daily_push.planned_count IS '只用于运营推送：ops_notification_campaigns.planned_count（开始发送时解析出的计划人数；存量活动为空）。活动静态属性，每行都带';
COMMENT ON COLUMN agg_daily_push.recipient_count IS '只用于运营推送：ops_notification_campaigns.recipient_count = 写入通知中心人数（近似「接收」：屏蔽推送、推送失败的人也计入，不是推送送达数）。活动静态属性，每行都带';
COMMENT ON COLUMN agg_daily_push.sent_count IS '只用于券过期推送（COUPON_EXPIRING）：当天 source = SERVER 的 toko_coupon_expiry_push_sent 事件数（确实发出推送才上报）；其他类型没有发送记录，为空（见 Story 4.4 A13 勘察）';
COMMENT ON COLUMN agg_daily_push.opened_count IS '当天 push_opened 次数';
COMMENT ON COLUMN agg_daily_push.opened_persons IS '当天 push_opened 去重人数（按 D-10 归属到人）；不能跨天相加，周 / 月见 agg_weekly_push / agg_monthly_push';
COMMENT ON COLUMN agg_daily_push.campaign_opened_persons_cum IS '只用于运营推送：从发送日（WIB）到 stat_date 的去重打开人数，只计发送后 analytics.rollup.push-campaign-days（默认 14）天内的打开，受明细保留期限制。查一条推送的打开率取该活动最新一行的本列 ÷ recipient_count；活动查不到为空';
COMMENT ON COLUMN agg_daily_push.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 推送：周表 / 月表（D-12）
CREATE TABLE agg_weekly_push (
    period_start    date         NOT NULL,
    push_type       varchar(40)  NOT NULL,
    campaign_token  varchar(32)  NOT NULL DEFAULT '',
    sent_count      bigint,
    opened_count    bigint       NOT NULL,
    opened_persons  bigint       NOT NULL,
    is_complete     boolean      NOT NULL,
    computed_at     timestamptz  NOT NULL,
    CONSTRAINT pk_agg_weekly_push PRIMARY KEY (period_start, push_type, campaign_token),
    CONSTRAINT ck_agg_weekly_push_monday CHECK (extract(isodow FROM period_start) = 1)
);

COMMENT ON TABLE agg_weekly_push IS 'S5 推送按 WIB 周（周一至周日）：发送数与打开次数为周内求和，打开人数为周内重新去重（D-12）。SQL 见 analytics/rollup/push.sql';
COMMENT ON COLUMN agg_weekly_push.period_start IS '周一（WIB）';
COMMENT ON COLUMN agg_weekly_push.push_type IS '同 agg_daily_push.push_type';
COMMENT ON COLUMN agg_weekly_push.campaign_token IS '同 agg_daily_push.campaign_token';
COMMENT ON COLUMN agg_weekly_push.sent_count IS '只用于 COUPON_EXPIRING：周内 toko_coupon_expiry_push_sent（SERVER）事件数之和；其他类型为空';
COMMENT ON COLUMN agg_weekly_push.opened_count IS '周内 push_opened 次数之和';
COMMENT ON COLUMN agg_weekly_push.opened_persons IS '周内去重打开人数（同一人多天打开只算 1 人）';
COMMENT ON COLUMN agg_weekly_push.is_complete IS 'true = 周已结束（周日 ≤ 计算基准日）；false = 周中「截至昨天」的值';
COMMENT ON COLUMN agg_weekly_push.computed_at IS '本行计算时间（UTC）';

CREATE TABLE agg_monthly_push (
    period_start    date         NOT NULL,
    push_type       varchar(40)  NOT NULL,
    campaign_token  varchar(32)  NOT NULL DEFAULT '',
    sent_count      bigint,
    opened_count    bigint       NOT NULL,
    opened_persons  bigint       NOT NULL,
    is_complete     boolean      NOT NULL,
    computed_at     timestamptz  NOT NULL,
    CONSTRAINT pk_agg_monthly_push PRIMARY KEY (period_start, push_type, campaign_token),
    CONSTRAINT ck_agg_monthly_push_month CHECK (period_start = date_trunc('month', period_start)::date)
);

COMMENT ON TABLE agg_monthly_push IS 'S5 推送按 WIB 自然月：发送数与打开次数为月内求和，打开人数为月内重新去重（D-12）。SQL 见 analytics/rollup/push.sql';
COMMENT ON COLUMN agg_monthly_push.period_start IS '当月 1 日（WIB 自然月）';
COMMENT ON COLUMN agg_monthly_push.push_type IS '同 agg_daily_push.push_type';
COMMENT ON COLUMN agg_monthly_push.campaign_token IS '同 agg_daily_push.campaign_token';
COMMENT ON COLUMN agg_monthly_push.sent_count IS '只用于 COUPON_EXPIRING：月内 toko_coupon_expiry_push_sent（SERVER）事件数之和；其他类型为空';
COMMENT ON COLUMN agg_monthly_push.opened_count IS '月内 push_opened 次数之和';
COMMENT ON COLUMN agg_monthly_push.opened_persons IS '月内去重打开人数（同一人多天打开只算 1 人）';
COMMENT ON COLUMN agg_monthly_push.is_complete IS 'true = 月份已结束（月末 ≤ 计算基准日）；false = 月中「截至昨天」的值';
COMMENT ON COLUMN agg_monthly_push.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 只读角色授权（A8 逐表白名单）
-- 角色名由 Flyway 占位符 readerRole 传入；本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_daily_placement', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_weekly_placement', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_monthly_placement', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_daily_push', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_weekly_push', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_monthly_push', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
