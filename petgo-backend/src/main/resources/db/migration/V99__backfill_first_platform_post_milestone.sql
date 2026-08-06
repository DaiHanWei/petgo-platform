-- 存量回填：S5「首条平台帖子」（2026-08-05 用户决策）。Flyway 序号接 V98 单调追加（决策 E2）。
--
-- 背景：S5 的判定口径已从「内容类型 = Moment」改为「这条内容是否对外可见」
-- （见 MilestoneAutoCompleteListener#onContentPublished）。但运行时判定只在**发布那一刻**执行 ——
-- 改动之前就已发过公开内容的用户，节点仍是灰的，除非再发一条。本迁移把这批人补上。
--
-- 判定与 Java 侧逐条对齐（改一处必须改另一处，否则新老用户两套口径）：
--   type = 'DAILY'            → 恒算（Moment 本就是平台动态）
--   或 visibility = 'PUBLIC'  → Diary / 科普帖公开时算；PRIVATE 不算（只进作者自己的档案）
--   且 status='PUBLISHED' 且未软删 —— 挂起审核 / 已删的内容不该算数。
--
-- ⚠️ 刻意**不发通知**：Java 侧 complete() 会发 MilestoneCompletedEvent 触发推送，
-- 而回填是一次性追认历史，给所有存量用户推一条「你解锁了里程碑」属于打扰。静默补齐即可。
--
-- 幂等：两段 INSERT 都带 NOT EXISTS 过滤，且 milestone_completions 对 pet_milestone_id 有唯一约束；
-- 重复执行不会产生重复行（Flyway 本身也只跑一次）。

-- ① S5 本体。completed_at 取**第一条合格内容的发布时间**而非 now()：
--    这个节点的语义就是「你第一次在平台发帖」，用 now() 会把所有人的时间线挤到迁移当天。
INSERT INTO milestone_completions (pet_milestone_id, source, completed_at)
SELECT pm.id, 'SYSTEM_AUTO', fp.first_at
FROM pet_profiles p
JOIN pet_milestones pm
  ON pm.pet_profile_id = p.id
 AND pm.code = CASE p.pet_type WHEN 'CAT' THEN 'C-S5' WHEN 'DOG' THEN 'D-S5' ELSE 'G-S5' END
JOIN LATERAL (
    SELECT MIN(cp.created_at) AS first_at
    FROM content_posts cp
    WHERE cp.author_id = p.owner_id
      AND cp.status = 'PUBLISHED'
      AND cp.deleted_at IS NULL
      AND (cp.type = 'DAILY' OR cp.visibility = 'PUBLIC')
) fp ON fp.first_at IS NOT NULL
WHERE NOT EXISTS (
    SELECT 1 FROM milestone_completions mc WHERE mc.pet_milestone_id = pm.id
);

-- ② 连带的聚合节点「Lulus Pemula」（CAT/DOG=S16、OTHER=S9）。
--
-- 为什么必须在同一迁移里做：运行时 S5 完成后会顺带尝试解锁这个聚合（checkAndUnlockLulusPemula），
-- 而纯 SQL 回填不会触发那段 Java。漏掉的话，用户会看到「新手任务 6 件全打勾、聚合奖励却还锁着」，
-- 且只有等他下次录一条健康记录才会被动补上 —— 比不回填还费解。
--
-- 条件与 Java 侧一致：S1–S5 全部已完成，且该档案至少有一条健康记录。
INSERT INTO milestone_completions (pet_milestone_id, source, completed_at)
SELECT lp.id, 'SYSTEM_AUTO', now()
FROM pet_profiles p
JOIN pet_milestones lp
  ON lp.pet_profile_id = p.id
 AND lp.code = CASE p.pet_type WHEN 'CAT' THEN 'C-S16' WHEN 'DOG' THEN 'D-S16' ELSE 'G-S9' END
WHERE NOT EXISTS (
        SELECT 1 FROM milestone_completions mc WHERE mc.pet_milestone_id = lp.id
      )
  AND EXISTS (
        SELECT 1 FROM health_records hr WHERE hr.pet_profile_id = p.id
      )
  -- S1–S5 无一缺席（存在任一未完成的前置 → 不解锁）
  AND NOT EXISTS (
        SELECT 1
        FROM (VALUES ('S1'), ('S2'), ('S3'), ('S4'), ('S5')) AS req(suffix)
        WHERE NOT EXISTS (
            SELECT 1
            FROM pet_milestones pm2
            JOIN milestone_completions mc2 ON mc2.pet_milestone_id = pm2.id
            WHERE pm2.pet_profile_id = p.id
              AND pm2.code = (CASE p.pet_type WHEN 'CAT' THEN 'C' WHEN 'DOG' THEN 'D' ELSE 'G' END)
                             || '-' || req.suffix
        )
      );
