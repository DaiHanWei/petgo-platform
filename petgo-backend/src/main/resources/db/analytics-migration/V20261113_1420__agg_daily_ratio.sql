-- analytics-pipeline Story 4.7：以日活为分母的比率指标（只在埋点库执行）。FR-22 / FR-24 / D-12 / D-13。
-- 本文件建：
--   agg_daily_ratio   每 WIB 日一行：S4（电商交易占日活）、产品 #3（非 Toko 付费解锁占日活）、内容 #1（新用户 / 回访用户）
-- 分子全部来自业务库窄查询（RatioRollup 经 PayerCountReader 在埋点事务之外读出人数，只把人数写进来，不跨库 SQL）；
-- 分母按 analytics.dau-source 取 ga4_daily_snapshot.active_users_all（GA4）或 agg_daily_activity 的 all 行 dau_person（SELF）。
-- 只有日表：比率类月指标 = 当月每日比率的算术平均，由本表现算（D-12 / 通用规则），不建月表。
-- 不含用户标识、匿名 id 等可识别个人的字段；不建外键。计数列一律 integer。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

CREATE TABLE agg_daily_ratio (
    stat_date            date          PRIMARY KEY,
    dau                  integer,
    dau_source           varchar(8)    NOT NULL,
    toko_buyers          integer       NOT NULL,
    s4_ratio             numeric(9,6),
    nontoko_payers       integer       NOT NULL,
    p3_ratio             numeric(9,6),
    payers_ktp           integer       NOT NULL,
    payers_tailsonality  integer       NOT NULL,
    payers_passport      integer       NOT NULL,
    payers_pet_slot      integer       NOT NULL,
    payers_ai_unlock     integer       NOT NULL,
    payers_vet_consult   integer       NOT NULL,
    payers_gift          integer       NOT NULL,
    payers_topup         integer       NOT NULL,
    new_users            integer       NOT NULL,
    returning_users      integer,
    computed_at          timestamptz   NOT NULL,
    CONSTRAINT ck_agg_daily_ratio_source CHECK (dau_source IN ('GA4', 'SELF'))
);

COMMENT ON TABLE agg_daily_ratio IS '以日活为分母的比率（S4、产品 #3、内容 #1），每 WIB 日一行；分子读业务库（只存人数），分母按 analytics.dau-source。月比率 = 当月每日比率的算术平均（跳过分母为空的日子），由本表现算，不建月表。SQL 见 analytics/rollup/ratio.sql';
COMMENT ON COLUMN agg_daily_ratio.stat_date IS 'WIB 日期（Asia/Jakarta 自然日）；业务库时间列按 [当天 0 点 WIB, 次日 0 点 WIB) 归日';
COMMENT ON COLUMN agg_daily_ratio.dau IS '分母日活：dau_source = GA4 取 ga4_daily_snapshot.active_users_all（GA4 全版本，按设备、含游客）；SELF 取 agg_daily_activity 的 all 行 dau_person（自建，含游客，按人）。取不到（GA4 当天未拉到 / stag 无 GA4）为 NULL，不用 0 顶替；GA4 回拉补上后 3 天回补窗口内自动填上';
COMMENT ON COLUMN agg_daily_ratio.dau_source IS '本行实际使用的分母来源 GA4 | SELF。切换开关后只有回补窗口（3 天）内的行被改写为新来源，更早的行保留旧来源——以每行本列为准';
COMMENT ON COLUMN agg_daily_ratio.toko_buyers IS 'S4 分子：shop_orders.paid_at 落在当天的去重下单人数（D-13：退款 / 取消仍算；下单人 USER + REAL）';
COMMENT ON COLUMN agg_daily_ratio.s4_ratio IS 'S4 = toko_buyers ÷ dau，六位小数四舍五入；dau 为 NULL 或 0 时为 NULL；超过 999.999999 时封顶（分母异常小，防写入溢出）';
COMMENT ON COLUMN agg_daily_ratio.nontoko_payers IS '产品 #3 分子：payers_ktp / tailsonality / passport / pet_slot / ai_unlock / vet_consult 六类的去重并集人数（不是六列相加）；不含送礼与 PawCoin 充值';
COMMENT ON COLUMN agg_daily_ratio.p3_ratio IS '产品 #3 = nontoko_payers ÷ dau，六位小数四舍五入；dau 为 NULL 或 0 时为 NULL；超过 999.999999 时封顶（分母异常小，防写入溢出）';
COMMENT ON COLUMN agg_daily_ratio.payers_ktp IS 'KTP 高清解锁付费人数：QRIS = payment_intents purpose = ID_HD 且 status = PAID（按 updated_at 归日，近似到账时刻）；PawCoin = id_card_hd_purchases pay_channel = PAWCOIN（按 purchased_at）；两渠道合并去重';
COMMENT ON COLUMN agg_daily_ratio.payers_tailsonality IS 'Tailsonality 结果 + 配型解锁付费人数：keepsake_purchases sku IN (TAILSONALITY, TS_MATCH) 且 status = PAID（按 paid_at；DUPLICATE_PAID / ORPHAN_PAID 不算）';
COMMENT ON COLUMN agg_daily_ratio.payers_passport IS '护照快照 + 登机牌付费人数：keepsake_purchases sku IN (PASSPORT_SNAP, BOARDING_PASS) 且 status = PAID（按 paid_at）';
COMMENT ON COLUMN agg_daily_ratio.payers_pet_slot IS '宠物名额月租付费人数：pet_slot_purchases status = PAID 且 kind IN (RENT, RENEW)（排除运营补发 ADMIN_GRANT；按 paid_at）';
COMMENT ON COLUMN agg_daily_ratio.payers_ai_unlock IS 'AI 问诊解锁付费人数：ai_consult_orders status = COMPLETED（按 paid_at）';
COMMENT ON COLUMN agg_daily_ratio.payers_vet_consult IS '兽医问诊付费人数：consult_orders paid_at 非空（含之后退款的，D-13；按 paid_at）';
COMMENT ON COLUMN agg_daily_ratio.payers_gift IS '参照（不进分子）：送礼付款人数（按送礼人去重），gift_orders status IN (DELIVERED, UNDELIVERED_REFUND_PENDING, UNDELIVERED_REFUNDED, REFUND_MANUAL)，按 delivered_at、无则 created_at';
COMMENT ON COLUMN agg_daily_ratio.payers_topup IS '参照（不进分子）：PawCoin 充值人数，payment_intents purpose = PAWCOIN_TOPUP 且 status = PAID（按 updated_at）';
COMMENT ON COLUMN agg_daily_ratio.new_users IS '内容 #1 新用户：业务库 users.created_at 落在当天、role = USER 且 account_type = REAL 的人数（含旧版 App 注册；与 S3 的 signup_succeeded 事件口径不同）';
COMMENT ON COLUMN agg_daily_ratio.returning_users IS '内容 #1 回访用户 = dau − new_users；dau 为 NULL 时为 NULL；可能为负（GA4 与业务库口径差），不截断';
COMMENT ON COLUMN agg_daily_ratio.computed_at IS '本行最近一次计算时间（UTC）';

-- ---------------------------------------------------------------- 只读角色授权
-- 角色名由 Flyway 占位符 readerRole 传入；本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_daily_ratio', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
