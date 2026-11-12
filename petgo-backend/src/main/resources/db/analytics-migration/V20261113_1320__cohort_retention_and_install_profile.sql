-- analytics-pipeline Story 4.3：留存（按组）与安装后建档（按月）。只在埋点库执行。D-5 / D-6 / D-10 / D-12 / D-13。
-- 本文件建：
--   agg_cohort_retention          按组的留存：INSTALL 组（市场 #1–#3 D1 / D3 / D7）、ONBOARDING 组（产品 #5 W1）
--   agg_monthly_install_profile   按 WIB 自然月：当月新装人数、其中当月建档成功（pet_added）的人数（产品 #1）
-- 汇总表不含用户标识、匿名 id、归属键等可识别个人的字段；不建外键。
-- 比率（按周加权的留存、建档比例）不入库，按 metric-definitions.yaml 里的 SQL 由组表 / 月表现算。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

-- ---------------------------------------------------------------- 按组的留存
CREATE TABLE agg_cohort_retention (
    cohort_type   varchar(16)  NOT NULL,
    cohort_date   date         NOT NULL,
    cohort_size   bigint       NOT NULL,
    d1_retained   bigint,
    d3_retained   bigint,
    d7_retained   bigint,
    w1_retained   bigint,
    computed_at   timestamptz  NOT NULL,
    CONSTRAINT pk_agg_cohort_retention PRIMARY KEY (cohort_type, cohort_date),
    CONSTRAINT ck_agg_cohort_retention_type CHECK (cohort_type IN ('INSTALL', 'ONBOARDING'))
);

COMMENT ON TABLE agg_cohort_retention IS '按组的留存（市场 #1–#3 D1 / D3 / D7、产品 #5 W1）：每个组类型 × WIB 组日期一行，组为空也写 cohort_size = 0 的行（0 = 没人，无行 = 没算）。SQL 见 analytics/rollup/retention.sql；按周加权的留存由本表现算（见 metric-definitions.yaml）';
COMMENT ON COLUMN agg_cohort_retention.cohort_type IS 'INSTALL = 以首次 Application Installed 的 WIB 日期分组（口径同 agg_daily_install.new_installs）；ONBOARDING = 以首次 onboarding_completed 的 WIB 日期分组';
COMMENT ON COLUMN agg_cohort_retention.cohort_date IS '组日期 X（WIB 日期，Asia/Jakarta 自然日）';
COMMENT ON COLUMN agg_cohort_retention.cohort_size IS '组人数：首次事件恰在 X 的去重人数（按 D-10 归属到人；「首次」在明细保留期内判断）';
COMMENT ON COLUMN agg_cohort_retention.d1_retained IS '只用于 INSTALL：组成员中在 X+1 当天有 Application Opened 的人数；空 = 未满 1 天（X+1 晚于计算基准日）或 ONBOARDING 行';
COMMENT ON COLUMN agg_cohort_retention.d3_retained IS '只用于 INSTALL：组成员中在 X+3 当天有 Application Opened 的人数；空 = 未满 3 天（X+3 晚于计算基准日）或 ONBOARDING 行';
COMMENT ON COLUMN agg_cohort_retention.d7_retained IS '只用于 INSTALL：组成员中在 X+7 当天有 Application Opened 的人数；空 = 未满 7 天（X+7 晚于计算基准日）或 ONBOARDING 行';
COMMENT ON COLUMN agg_cohort_retention.w1_retained IS '只用于 ONBOARDING：组成员中在 [X+7, X+13] 任意一天有 Application Opened 的人数；空 = 未满 13 天（X+13 晚于计算基准日）或 INSTALL 行';
COMMENT ON COLUMN agg_cohort_retention.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 安装后建档（按月）
CREATE TABLE agg_monthly_install_profile (
    stat_month        date         PRIMARY KEY,
    new_installs      bigint       NOT NULL,
    with_pet_profile  bigint       NOT NULL,
    is_complete       boolean      NOT NULL,
    computed_at       timestamptz  NOT NULL,
    CONSTRAINT ck_agg_monthly_install_profile_month CHECK (stat_month = date_trunc('month', stat_month)::date)
);

COMMENT ON TABLE agg_monthly_install_profile IS '安装后创建宠物档案（产品 #1），按 WIB 自然月：比例 = with_pet_profile ÷ new_installs，现算不入库。SQL 见 analytics/rollup/install_profile.sql';
COMMENT ON COLUMN agg_monthly_install_profile.stat_month IS '当月 1 日（WIB 自然月）';
COMMENT ON COLUMN agg_monthly_install_profile.new_installs IS '当月新装人数：首次 Application Installed 落在当月的去重人数（口径同 S2，= 当月 agg_daily_install all 行 new_installs 之和）';
COMMENT ON COLUMN agg_monthly_install_profile.with_pet_profile IS '其中当月（event_date 在本月内）有 pet_added（建档成功）的人数；不要求建档晚于安装，只要求同月；月末安装、次月建档不计入';
COMMENT ON COLUMN agg_monthly_install_profile.is_complete IS 'true = 月份已结束（月末 ≤ 计算基准日）；false = 月中「截至昨天」的值';
COMMENT ON COLUMN agg_monthly_install_profile.computed_at IS '本行计算时间（UTC）';

-- ---------------------------------------------------------------- 只读角色授权（A8 逐表白名单）
-- 角色名由 Flyway 占位符 readerRole 传入；本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_cohort_retention', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_monthly_install_profile', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
