-- V1.5.0 onboarding-recall Story 2.4 · FR-179 · OR-12：新手任务（7 项账号级）+ 账号级 Lulus Pemula 存量补算。
-- 预分配版本号 V20261115_0320（决策 E7 时间戳制；不得另取），须在 ① V20261115_0300（user_newbie_marks）、
-- ② V20261115_0310（LULUS_PEMULA flag + celebrated_at）、③ V20261115_0315（NEWBIE_GRADUATED 规则行）之后执行。
--
-- 🔴 一次性作业，**走 SQL 直插、不经任何 Java 服务 / 运行时事件**（照 V20261104_2040 / V20261018_1503 头注释）：
--    → 不发 AccountMilestoneCompletedEvent / GrowthLevelChangedEvent、不发里程碑 / 升级通知、不报 newbie_task_completed 埋点；
--    → 补到的完成行与 flag 一律 celebrated_at = 完成时刻（= 已庆祝，之后进里程碑页 / 账号成就页都不补弹）；
--    → 不写待弹庆祝（celebrate_from_level / celebrate_to_level），不改 backfill_popup_pending 与 backfill_* 快照列。
--    部署窗口内的增量由运行时监听补上（NewbieMarkService.markIfAbsent 幂等），本迁移不考虑。
--
-- 口径（账号范围 = 未注销 users.deleted_at IS NULL 且无 account_deletions 行；与 V20261104_2040 逐字对齐）：
--   ① user_newbie_marks（source = 'BACKFILL'，occurred_at = 来源最早时间）——「做过」的事实，含 VIRTUAL：
--      ① FIRST_DIARY        任一宠物（含只读）已完成 GROWTH_MOMENT_FIRST（C-S2 / D-S2 / G-S2）或本人作者的
--                           GROWTH_MOMENT 帖（status = PUBLISHED，含软删）
--      ② FIRST_LIKE_RECEIVED 任一宠物已完成 FIRST_LIKE（C-S15 / D-S15 / G-S8）或他人给本人帖子的赞
--                           （🔴 不按 content_likes.is_automated 过滤：自动点赞计入②，跨线约定）
--      ③ FIRST_FOLLOW       已有 FIRST_FOLLOW_USER flag 或 user_follows.source = 'USER' 或 pet_follows 有行
--      ④ FIRST_LIKE_GIVEN   本人给他人帖子的赞
--      ⑤ FIRST_COMMENT      本人在他人帖子下的一级评论（含软删：删评不撤）
--      ⑥ FIRST_REPLY        本人的二级评论，被回复评论 COALESCE(reply_to_comment_id, parent_id) 的作者非本人（含软删）
--      ⑦ ONE_WEEK           WIB 注册日 + 7 ≤ 今天（WIB）；occurred_at = 注册日 + 7 的 WIB 零点
--      编码显式列举（与 MilestoneAutoCompleteMap 的 GROWTH_MOMENT_FIRST / FIRST_LIKE 一致），不按后缀切分。
--   ② 已按宠物解锁过 Lulus Pemula（C-S16 / D-S16 / G-S9 有完成行）→ 写 LULUS_PEMULA flag
--      （occurred_at = celebrated_at = MIN(completed_at)），**不发成长值**（PRD「已解锁不收回」）。
--   ③ 未解锁且 7 项标记齐全的 REAL USER ACTIVE 账号（与运行时 NewbieGraduationService 的 isActiveRegularUser 对齐）
--      → 写 flag（occurred_at = celebrated_at = 7 项里最晚的时间）+ 成长值 LAUNCH_BACKFILL +20（写死分值，不读 growth_rules），
--      ref_type = 'MILESTONE'、ref_id = 'LULUS_PEMULA'，只给 eligible（与 V20261018_1503 逐字一致）。
--   ④ 全部 LULUS_PEMULA flag × ACTIVE 宠物 × 本物种编码补完成行（completed_at = celebrated_at =
--      GREATEST(flag.occurred_at, 宠物建档时间)）；READ_ONLY 不写（恢复时由 reconcile 钩子补「已完成且已庆祝」）；
--      roster 行缺失的老宠物不写（惰性物化时由 assignRoster 钩子补）。
--
-- 幂等（L1 测试会用 ScriptUtils 在自动提交下重放）：
--   ① ② ③ 的 flag：ON CONFLICT DO NOTHING；④ NOT EXISTS 已有完成行（另有 pet_milestone_id 唯一约束兜底）；
--   ③ 成长值只对本条语句**真正新插入**的 flag 发（RETURNING 链），流水 idem_key = 'BACKFILL150:' || user_id || ':LULUS_PEMULA'
--      （与 GrowthIdemKeys.backfillNewbie 同格式）ON CONFLICT (idem_key) DO NOTHING；另排除已有运行时键
--      'MILESTONE_ACCT:' || user_id || ':LULUS_PEMULA'（GrowthIdemKeys.milestoneAccount）的账号；账户只对本次真正插入了流水的用户累加。
-- 为什么一条数据修改 CTE：L1 用 ScriptUtils 在自动提交下重放，`CREATE TEMP TABLE … ON COMMIT DROP` 会在建表语句提交时消失。
-- 规模：点赞 / 评论表万级到十万级，GROUP BY 一次扫完；不写逐行 PL/pgSQL 循环。

-- ① 标记 ×7 ----------------------------------------------------------------------------------------------

INSERT INTO user_newbie_marks (user_id, task_key, occurred_at, source)
SELECT x.user_id, 'FIRST_DIARY', MIN(x.at), 'BACKFILL'
FROM (SELECT p.owner_id AS user_id, mc.completed_at AS at
        FROM milestone_completions mc
        JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
        JOIN pet_profiles p ON p.id = pm.pet_profile_id
       WHERE pm.code IN ('C-S2', 'D-S2', 'G-S2')
      UNION ALL
      SELECT cp.author_id, cp.created_at
        FROM content_posts cp
       WHERE cp.type = 'GROWTH_MOMENT' AND cp.status = 'PUBLISHED') x
JOIN users u ON u.id = x.user_id AND u.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
GROUP BY x.user_id
ON CONFLICT (user_id, task_key) DO NOTHING;

INSERT INTO user_newbie_marks (user_id, task_key, occurred_at, source)
SELECT x.user_id, 'FIRST_LIKE_RECEIVED', MIN(x.at), 'BACKFILL'
FROM (SELECT p.owner_id AS user_id, mc.completed_at AS at
        FROM milestone_completions mc
        JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
        JOIN pet_profiles p ON p.id = pm.pet_profile_id
       WHERE pm.code IN ('C-S15', 'D-S15', 'G-S8')
      UNION ALL
      SELECT cp.author_id, l.created_at
        FROM content_likes l
        JOIN content_posts cp ON cp.id = l.post_id
       WHERE l.user_id <> cp.author_id) x
JOIN users u ON u.id = x.user_id AND u.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
GROUP BY x.user_id
ON CONFLICT (user_id, task_key) DO NOTHING;

INSERT INTO user_newbie_marks (user_id, task_key, occurred_at, source)
SELECT x.user_id, 'FIRST_FOLLOW', MIN(x.at), 'BACKFILL'
FROM (SELECT fl.user_id, fl.occurred_at AS at
        FROM user_milestone_flags fl
       WHERE fl.flag_key = 'FIRST_FOLLOW_USER'
      UNION ALL
      SELECT f.follower_id, f.created_at
        FROM user_follows f
       WHERE f.source = 'USER'
      UNION ALL
      SELECT pf.follower_id, pf.created_at
        FROM pet_follows pf) x
JOIN users u ON u.id = x.user_id AND u.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
GROUP BY x.user_id
ON CONFLICT (user_id, task_key) DO NOTHING;

INSERT INTO user_newbie_marks (user_id, task_key, occurred_at, source)
SELECT l.user_id, 'FIRST_LIKE_GIVEN', MIN(l.created_at), 'BACKFILL'
FROM content_likes l
JOIN content_posts cp ON cp.id = l.post_id AND cp.author_id <> l.user_id
JOIN users u ON u.id = l.user_id AND u.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
GROUP BY l.user_id
ON CONFLICT (user_id, task_key) DO NOTHING;

INSERT INTO user_newbie_marks (user_id, task_key, occurred_at, source)
SELECT c.author_id, 'FIRST_COMMENT', MIN(c.created_at), 'BACKFILL'
FROM comments c
JOIN content_posts cp ON cp.id = c.post_id AND cp.author_id <> c.author_id
JOIN users u ON u.id = c.author_id AND u.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
WHERE c.parent_id IS NULL
GROUP BY c.author_id
ON CONFLICT (user_id, task_key) DO NOTHING;

INSERT INTO user_newbie_marks (user_id, task_key, occurred_at, source)
SELECT c.author_id, 'FIRST_REPLY', MIN(c.created_at), 'BACKFILL'
FROM comments c
JOIN comments t ON t.id = COALESCE(c.reply_to_comment_id, c.parent_id) AND t.author_id <> c.author_id
JOIN users u ON u.id = c.author_id AND u.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
WHERE c.parent_id IS NOT NULL
GROUP BY c.author_id
ON CONFLICT (user_id, task_key) DO NOTHING;

INSERT INTO user_newbie_marks (user_id, task_key, occurred_at, source)
SELECT u.id, 'ONE_WEEK',
       (((u.created_at AT TIME ZONE 'Asia/Jakarta')::date + 7)::timestamp AT TIME ZONE 'Asia/Jakarta'), 'BACKFILL'
FROM users u
WHERE u.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
  AND (u.created_at AT TIME ZONE 'Asia/Jakarta')::date + 7 <= (now() AT TIME ZONE 'Asia/Jakarta')::date
ON CONFLICT (user_id, task_key) DO NOTHING;

-- ② 已按宠物解锁过 Lulus Pemula → 账号 flag（不发成长值）-----------------------------------------------------

INSERT INTO user_milestone_flags (user_id, flag_key, occurred_at, celebrated_at)
SELECT p.owner_id, 'LULUS_PEMULA', MIN(mc.completed_at), MIN(mc.completed_at)
FROM milestone_completions mc
JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
JOIN pet_profiles p ON p.id = pm.pet_profile_id
JOIN users u ON u.id = p.owner_id AND u.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
WHERE pm.code IN ('C-S16', 'D-S16', 'G-S9')
GROUP BY p.owner_id
ON CONFLICT (user_id, flag_key) DO NOTHING;

-- ③ 未解锁且 7 项齐全 → flag + 成长值 +20（一条 CTE；eligible 与 V20261018_1503 逐字一致）-----------------

WITH eligible AS (
    SELECT u.id AS user_id
    FROM users u
    WHERE u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id AND ad.status <> 'PENDING')
), graduated AS (
    SELECT m.user_id, MAX(m.occurred_at) AS at
    FROM user_newbie_marks m
    JOIN users u ON u.id = m.user_id AND u.role = 'USER' AND u.account_type = 'REAL' AND u.status = 'ACTIVE'
        AND u.deleted_at IS NULL
        AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)
    WHERE m.task_key IN ('FIRST_DIARY', 'FIRST_LIKE_RECEIVED', 'FIRST_FOLLOW', 'FIRST_LIKE_GIVEN',
                         'FIRST_COMMENT', 'FIRST_REPLY', 'ONE_WEEK')
    GROUP BY m.user_id
    HAVING COUNT(DISTINCT m.task_key) = 7
), new_flags AS (
    INSERT INTO user_milestone_flags (user_id, flag_key, occurred_at, celebrated_at)
    SELECT g.user_id, 'LULUS_PEMULA', g.at, g.at
    FROM graduated g
    ON CONFLICT (user_id, flag_key) DO NOTHING
    RETURNING user_id
), ins AS (
    INSERT INTO growth_ledger (user_id, rule_code, delta, ref_type, ref_id, idem_key, wib_date, created_at)
    SELECT nf.user_id, 'LAUNCH_BACKFILL', 20, 'MILESTONE', 'LULUS_PEMULA',
           'BACKFILL150:' || nf.user_id || ':LULUS_PEMULA', (now() AT TIME ZONE 'Asia/Jakarta')::date, now()
    FROM new_flags nf
    JOIN eligible e ON e.user_id = nf.user_id
    WHERE NOT EXISTS (SELECT 1 FROM growth_ledger g
                      WHERE g.idem_key = 'MILESTONE_ACCT:' || nf.user_id || ':LULUS_PEMULA')
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

-- ④ LULUS_PEMULA flag × ACTIVE 宠物补「已完成且已庆祝」（显式码表，不拼后缀）--------------------------------

INSERT INTO milestone_completions (pet_milestone_id, source, completed_at, celebrated_at)
SELECT pm.id, 'SYSTEM_AUTO', GREATEST(fl.occurred_at, p.created_at), GREATEST(fl.occurred_at, p.created_at)
FROM user_milestone_flags fl
JOIN pet_profiles p ON p.owner_id = fl.user_id AND p.status = 'ACTIVE'
JOIN (VALUES ('LULUS_PEMULA', 'CAT',   'C-S16'),
             ('LULUS_PEMULA', 'DOG',   'D-S16'),
             ('LULUS_PEMULA', 'OTHER', 'G-S9')) AS v(flag_key, pet_type, code)
  ON v.flag_key = fl.flag_key AND v.pet_type = p.pet_type
JOIN pet_milestones pm ON pm.pet_profile_id = p.id AND pm.code = v.code
WHERE NOT EXISTS (SELECT 1 FROM milestone_completions mc WHERE mc.pet_milestone_id = pm.id);
