-- 指标族 ratio（analytics-pipeline Story 4.7，S4 / 产品 #3 / 内容 #1，FR-24 / D-12 / D-13）：以日活为分母的比率，写 agg_daily_ratio。
-- 每个（指标族, WIB 日）一个事务，由 RollupService 执行，同一事务内推进 agg_progress；依赖指标族 activity（先算日活）。
--
-- 分子不在这里算：RatioRollup.prepareDay 在埋点事务之外经 PayerCountReader 窄查询业务库，只把人数作为参数传入
-- （两库之间不做跨库 SQL；用户 id 只在 JVM 内存里去重，不进埋点库、不打日志）。
-- 比率与回访用户由 RatioRollup 按取到的分母计算后传入（分母 NULL / 0 → 比率 NULL；分母 NULL → 回访 NULL）。
--
-- 参数（全部由 Java 传入，本文件不推算日期）：
--   :stat_date        统计日（WIB 日期）
--   :dau_source       分母来源 'GA4' | 'SELF'（analytics.dau-source）
--   :dau              分母（denominator 段读出；取不到为 NULL）
--   :toko_buyers / :nontoko_payers / :payers_* / :new_users   业务库人数
--   :s4_ratio / :p3_ratio / :returning_users                  Java 算好的派生列（可为 NULL）
--   :computed_at      本次运行开始时刻（UTC），只写进 computed_at 列

-- @section denominator
-- 分母：GA4 = ga4_daily_snapshot.active_users_all（GA4 全版本，含游客）；SELF = agg_daily_activity 的 all 行 dau_person（自建，含游客，按人）。
-- 永远返回一行；当天没有快照 / 没有 all 行 → NULL。
SELECT CASE CAST(:dau_source AS varchar)
           WHEN 'GA4' THEN (SELECT s.active_users_all
                            FROM ga4_daily_snapshot s
                            WHERE s.stat_date = CAST(:stat_date AS date))
           WHEN 'SELF' THEN (SELECT a.dau_person
                             FROM agg_daily_activity a
                             WHERE a.stat_date = CAST(:stat_date AS date) AND a.platform = 'all')
       END AS dau;

-- @section upsert
-- 每天一行，upsert 幂等；回补窗口内重跑会用当前开关的来源改写 dau_source。
INSERT INTO agg_daily_ratio (stat_date, dau, dau_source, toko_buyers, s4_ratio, nontoko_payers, p3_ratio,
                             payers_ktp, payers_tailsonality, payers_passport, payers_pet_slot, payers_ai_unlock,
                             payers_vet_consult, payers_gift, payers_topup, new_users, returning_users, computed_at)
VALUES (CAST(:stat_date AS date), CAST(:dau AS integer), CAST(:dau_source AS varchar),
        CAST(:toko_buyers AS integer), CAST(:s4_ratio AS numeric), CAST(:nontoko_payers AS integer),
        CAST(:p3_ratio AS numeric), CAST(:payers_ktp AS integer), CAST(:payers_tailsonality AS integer),
        CAST(:payers_passport AS integer), CAST(:payers_pet_slot AS integer), CAST(:payers_ai_unlock AS integer),
        CAST(:payers_vet_consult AS integer), CAST(:payers_gift AS integer), CAST(:payers_topup AS integer),
        CAST(:new_users AS integer), CAST(:returning_users AS integer), CAST(:computed_at AS timestamptz))
ON CONFLICT (stat_date) DO UPDATE SET
    dau                 = EXCLUDED.dau,
    dau_source          = EXCLUDED.dau_source,
    toko_buyers         = EXCLUDED.toko_buyers,
    s4_ratio            = EXCLUDED.s4_ratio,
    nontoko_payers      = EXCLUDED.nontoko_payers,
    p3_ratio            = EXCLUDED.p3_ratio,
    payers_ktp          = EXCLUDED.payers_ktp,
    payers_tailsonality = EXCLUDED.payers_tailsonality,
    payers_passport     = EXCLUDED.payers_passport,
    payers_pet_slot     = EXCLUDED.payers_pet_slot,
    payers_ai_unlock    = EXCLUDED.payers_ai_unlock,
    payers_vet_consult  = EXCLUDED.payers_vet_consult,
    payers_gift         = EXCLUDED.payers_gift,
    payers_topup        = EXCLUDED.payers_topup,
    new_users           = EXCLUDED.new_users,
    returning_users     = EXCLUDED.returning_users,
    computed_at         = EXCLUDED.computed_at;
