-- V1.4.0 ① Tailrent Level · Story 2.2：存量用户上线补算 + 存量虚拟号 / 官方号展示等级（架构 AD-13 · P-6 · P-7）。
--
-- 🔴 一次性作业，**走 SQL 直插、不经任何 Java 服务 / 运行时事件** → 不发 GrowthLevelChangedEvent、不发升级通知、
--    不写待弹庆祝（celebrate_*）；只置 backfill_popup_pending = true 让 App 弹一次「存量汇总」（照 V77 头注释的写法）。
--    ② 的宠物名额按 GrowthQueryService 实时读落库等级，不受「不发事件」影响。
--
-- 号 V20261018_1503 为预分配号，须在 V20261018_1500（建表 + seed）之后执行。
--
-- 每个参与成长值的真实用户（与 GrowthParticipation.ACTIVE_REGULAR_USER 同一口径：role USER / REAL / 未注销 / 未停用 /
-- 注销作业未开始）：EXP = 已完成里程碑分值（S5 / M15 / L25，同 code 最多计 5 次） + 历史活跃天数 × 3。
--   · 活跃天数取 user_active_days 行数 —— 该表 2026-09-25 起才有数据、历史无法回填（P-6 接受，不找代理口径）。
--   · 分值写死在这里、不读 growth_rules：迁移只跑一次，与上线 seed 一致即可。
--   · 发帖与互动不补算（PRD）。total = 0 的用户不建账户行、不写流水（无行 = Lv1）。
--
-- 幂等（L1 测试会重放本脚本）：流水 idem_key = 'BACKFILL:' || user_id（与 GrowthIdemKeys.backfill 同格式）唯一，
-- 账户只对本次 RETURNING 真正插入了流水的用户累加 → 重放不重复入账、不翻倍、不改快照。
-- 为什么一条数据修改 CTE：L1 用 ScriptUtils 在自动提交下重放，`CREATE TEMP TABLE … ON COMMIT DROP` 会在建表语句提交时消失。

-- 0) 补算快照列（存量汇总弹窗 E1 的小账单用；AD-11 缺 milestoneCount，本 story 补齐）。
ALTER TABLE growth_accounts
    ADD COLUMN IF NOT EXISTS backfill_active_days     INT NULL,
    ADD COLUMN IF NOT EXISTS backfill_milestone_count INT NULL,
    ADD COLUMN IF NOT EXISTS backfill_milestone_exp   INT NULL;

COMMENT ON COLUMN growth_accounts.backfill_active_days IS '上线一次性补算的快照（活跃天数），仅供存量汇总弹窗显示，不参与任何计算';
COMMENT ON COLUMN growth_accounts.backfill_milestone_count IS '上线一次性补算的快照（计入的里程碑次数），仅供存量汇总弹窗显示，不参与任何计算';
COMMENT ON COLUMN growth_accounts.backfill_milestone_exp IS '上线一次性补算的快照（里程碑 EXP），仅供存量汇总弹窗显示，不参与任何计算';

-- 1) 真实用户补算：一条语句。
WITH eligible AS (
    SELECT u.id AS user_id
    FROM users u
    WHERE u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id AND ad.status <> 'PENDING')
), days AS (
    SELECT d.user_id, COUNT(*)::int AS active_days
    FROM user_active_days d
    JOIN eligible e ON e.user_id = d.user_id
    GROUP BY d.user_id
), per_code AS (
    SELECT p.owner_id AS user_id, pm.code, pm.level, LEAST(COUNT(*), 5)::int AS n
    FROM milestone_completions mc
    JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
    JOIN pet_profiles p ON p.id = pm.pet_profile_id
    JOIN eligible e ON e.user_id = p.owner_id
    GROUP BY p.owner_id, pm.code, pm.level
), ms AS (
    SELECT user_id,
           SUM(n)::int AS milestone_count,
           -- S = 5 / M = 15 / L = 25（PRD / AD-13 原值）
           SUM(n * CASE level WHEN 'S' THEN 5 WHEN 'M' THEN 15 WHEN 'L' THEN 25 ELSE 0 END)::int AS milestone_exp
    FROM per_code
    GROUP BY user_id
), calc AS (
    SELECT e.user_id,
           COALESCE(d.active_days, 0) AS active_days,
           COALESCE(m.milestone_count, 0) AS milestone_count,
           COALESCE(m.milestone_exp, 0) AS milestone_exp,
           COALESCE(d.active_days, 0) * 3 + COALESCE(m.milestone_exp, 0) AS total
    FROM eligible e
    LEFT JOIN days d ON d.user_id = e.user_id
    LEFT JOIN ms m ON m.user_id = e.user_id
), ins AS (
    INSERT INTO growth_ledger (user_id, rule_code, delta, idem_key, wib_date, created_at)
    SELECT user_id, 'LAUNCH_BACKFILL', total, 'BACKFILL:' || user_id, (now() AT TIME ZONE 'Asia/Jakarta')::date, now()
    FROM calc
    WHERE total > 0
    ON CONFLICT (idem_key) DO NOTHING
    RETURNING user_id, delta
)
INSERT INTO growth_accounts (user_id, lifetime_total, balance, level, backfill_popup_pending, version,
        backfill_active_days, backfill_milestone_count, backfill_milestone_exp, created_at, updated_at)
SELECT i.user_id, i.delta, 0,
       (SELECT COALESCE(MAX(l.level), 1) FROM growth_levels l WHERE l.threshold <= i.delta),
       true, 0, c.active_days, c.milestone_count, c.milestone_exp, now(), now()
FROM ins i
JOIN calc c ON c.user_id = i.user_id
ON CONFLICT (user_id) DO UPDATE SET
    lifetime_total = growth_accounts.lifetime_total + EXCLUDED.lifetime_total,
    level = (SELECT COALESCE(MAX(l.level), 1) FROM growth_levels l
             WHERE l.threshold <= growth_accounts.lifetime_total + EXCLUDED.lifetime_total),
    backfill_popup_pending = true,
    backfill_active_days = EXCLUDED.backfill_active_days,
    backfill_milestone_count = EXCLUDED.backfill_milestone_count,
    backfill_milestone_exp = EXCLUDED.backfill_milestone_exp,
    version = growth_accounts.version + 1,
    updated_at = now();

-- 2) 存量虚拟号与官方号（role = ADMIN，P-7）：建账户行 + 随机展示等级 6 / 7 / 8，不写流水、不补算。
--    含已停用（enabled = false）的虚拟号：重新启用后仍要有等级。重放不改已有展示等级（否则每次重新随机）。
INSERT INTO growth_accounts (user_id, lifetime_total, balance, level, display_level, backfill_popup_pending, version,
        created_at, updated_at)
SELECT u.id, 0, 0, 1, 6 + floor(random() * 3)::int, false, 0, now(), now()
FROM users u
WHERE u.deleted_at IS NULL AND (u.account_type = 'VIRTUAL' OR u.role = 'ADMIN')
ON CONFLICT (user_id) DO UPDATE SET display_level = EXCLUDED.display_level,
    version = growth_accounts.version + 1, updated_at = now()
WHERE growth_accounts.display_level IS NULL;
