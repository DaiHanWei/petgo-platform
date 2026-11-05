-- V1.4.2 Story 2.2 · FR-154.4 · D-4 第 3 条 / P-7 / P-8 ⑥：账号级里程碑存量补算（「第一次关注一个人」「第一次收藏内容」）。
-- 预分配版本号 V20261104_2040（决策 E7 时间戳制；不得另取），须在 ⑤ V20261104_2030（建 user_milestone_flags + 名册补行）之后执行。
--
-- 🔴 一次性作业，**走 SQL 直插、不经任何 Java 服务 / 运行时事件**（照 V20261018_1503 头注释）：
--    → 不发 AccountMilestoneCompletedEvent / GrowthLevelChangedEvent、不发里程碑 / 升级通知；
--    → 完成行 celebrated_at = completed_at（= 已庆祝，之后进里程碑列表不补弹；照 V20261012_1105）；
--    → 不写待弹庆祝（celebrate_from_level / celebrate_to_level），不改 backfill_popup_pending 与 backfill_* 快照列
--      （那是 1.4.0 存量汇总弹窗用的，不能被本次覆盖 / 再弹一次）。
--    跨档后的宠物名额由 GrowthQueryService 实时读落库等级；只读宠物等下次 reconcile 再恢复（与 1.4.0 补算同口径）。
--
-- 口径：
--   ① flag：user_follows.source = 'USER'（官方号自动关注 SYSTEM 不算）/ content_collections（帖子收藏；place_collections 不读），
--      未注销账号（deleted_at IS NULL；含 VIRTUAL / DEACTIVATED —— flag 是事实）；occurred_at = 该账号最早一条记录。
--      注销已受理（account_deletions 有行，任何状态）的账号不写 flag（→ 也不写完成行、不发分），
--      与运行时 AccountMilestoneService.onFlagTriggered 的 deletions.findByUserId(..).isPresent() 逐字对齐。
--   ② 完成行：flag × ACTIVE 宠物 × 本物种编码（显式码表，与 AccountMilestoneFlag / MilestoneAutoCompleteMap 一致，
--      不按后缀切分：通用物种是 G-S12 / G-S13）；READ_ONLY 不写（恢复时由 2.1 reconcile 钩子补「已完成且已庆祝」）；
--      roster 行缺失的老宠物不写（首次打开列表惰性物化时由 2.1 assignRoster 钩子补）。
--      completed_at = celebrated_at = GREATEST(flag.occurred_at, 宠物建档时间)（时间线上不出现「建档前已完成」）。
--   ③ 成长值：每「账号 × flag」1 条 LAUNCH_BACKFILL +5（写死分值，不读 growth_rules），只给参与成长值的账号
--      （eligible 与 V20261018_1503 逐字一致 = GrowthParticipation.ACTIVE_REGULAR_USER）。
--
-- 幂等（L1 测试会用 ScriptUtils 在自动提交下重放）：
--   ① ON CONFLICT (user_id, flag_key) DO NOTHING；② NOT EXISTS 已有完成行（另有 pet_milestone_id 唯一约束兜底）；
--   ③ 流水 idem_key = 'BACKFILL142:' || user_id || ':' || flag_key（与 GrowthIdemKeys.backfillAccountMilestone 同格式），
--      ON CONFLICT (idem_key) DO NOTHING；另排除已有运行时键 'MILESTONE_ACCT:' || user_id || ':' || flag_key
--      （GrowthIdemKeys.milestoneAccount）的账号，零成本防双发；账户只对本次 RETURNING 真正插入了流水的用户累加。
--   与 1.4.0 补算前缀 'BACKFILL:' || user_id 不同，互不冲突。
-- 为什么一条数据修改 CTE：L1 用 ScriptUtils 在自动提交下重放，`CREATE TEMP TABLE … ON COMMIT DROP` 会在建表语句提交时消失。

-- ① flag
INSERT INTO user_milestone_flags (user_id, flag_key, occurred_at)
SELECT f.follower_id, 'FIRST_FOLLOW_USER', MIN(f.created_at)
FROM user_follows f
JOIN users u ON u.id = f.follower_id AND u.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
WHERE f.source = 'USER'
GROUP BY f.follower_id
ON CONFLICT (user_id, flag_key) DO NOTHING;

INSERT INTO user_milestone_flags (user_id, flag_key, occurred_at)
SELECT c.user_id, 'FIRST_COLLECT_CONTENT', MIN(c.created_at)
FROM content_collections c
JOIN users u ON u.id = c.user_id AND u.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
GROUP BY c.user_id
ON CONFLICT (user_id, flag_key) DO NOTHING;

-- ② ACTIVE 宠物完成且已庆祝（显式码表，不拼后缀）
INSERT INTO milestone_completions (pet_milestone_id, source, completed_at, celebrated_at)
SELECT pm.id, 'SYSTEM_AUTO', GREATEST(fl.occurred_at, p.created_at), GREATEST(fl.occurred_at, p.created_at)
FROM user_milestone_flags fl
JOIN pet_profiles p ON p.owner_id = fl.user_id AND p.status = 'ACTIVE'
JOIN (VALUES ('FIRST_FOLLOW_USER',     'CAT',   'C-S19'),
             ('FIRST_FOLLOW_USER',     'DOG',   'D-S19'),
             ('FIRST_FOLLOW_USER',     'OTHER', 'G-S12'),
             ('FIRST_COLLECT_CONTENT', 'CAT',   'C-S20'),
             ('FIRST_COLLECT_CONTENT', 'DOG',   'D-S20'),
             ('FIRST_COLLECT_CONTENT', 'OTHER', 'G-S13')) AS v(flag_key, pet_type, code)
  ON v.flag_key = fl.flag_key AND v.pet_type = p.pet_type
JOIN pet_milestones pm ON pm.pet_profile_id = p.id AND pm.code = v.code
WHERE NOT EXISTS (SELECT 1 FROM milestone_completions mc WHERE mc.pet_milestone_id = pm.id);

-- ③ 成长值（一条 CTE；eligible 与 V20261018_1503 逐字一致）
WITH eligible AS (
    SELECT u.id AS user_id
    FROM users u
    WHERE u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id AND ad.status <> 'PENDING')
), ins AS (
    INSERT INTO growth_ledger (user_id, rule_code, delta, ref_type, ref_id, idem_key, wib_date, created_at)
    SELECT fl.user_id, 'LAUNCH_BACKFILL', 5, 'MILESTONE', fl.flag_key,
           'BACKFILL142:' || fl.user_id || ':' || fl.flag_key, (now() AT TIME ZONE 'Asia/Jakarta')::date, now()
    FROM user_milestone_flags fl
    JOIN eligible e ON e.user_id = fl.user_id
    WHERE NOT EXISTS (SELECT 1 FROM growth_ledger g
                      WHERE g.idem_key = 'MILESTONE_ACCT:' || fl.user_id || ':' || fl.flag_key)
    ON CONFLICT (idem_key) DO NOTHING
    RETURNING user_id, delta
), per_user AS (
    SELECT user_id, SUM(delta)::int AS add_total
    FROM ins
    GROUP BY user_id
)
INSERT INTO growth_accounts (user_id, lifetime_total, balance, level, backfill_popup_pending, version,
        created_at, updated_at)
SELECT pu.user_id, pu.add_total, 0,
       (SELECT COALESCE(MAX(l.level), 1) FROM growth_levels l WHERE l.threshold <= pu.add_total),
       false, 0, now(), now()
FROM per_user pu
ON CONFLICT (user_id) DO UPDATE SET
    lifetime_total = growth_accounts.lifetime_total + EXCLUDED.lifetime_total,
    level = (SELECT COALESCE(MAX(l.level), 1) FROM growth_levels l
             WHERE l.threshold <= growth_accounts.lifetime_total + EXCLUDED.lifetime_total),
    version = growth_accounts.version + 1,
    updated_at = now();
