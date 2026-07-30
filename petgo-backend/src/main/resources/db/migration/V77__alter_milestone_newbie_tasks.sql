-- Story 7.3（FR-47）：聚合里程碑「Lulus Pemula」老用户批量回溯（**一次性作业**）。
-- 号 V77 单调追加（当前最大 V76=init_health_records；架构旧稿 V59 作废，决策 E2 顺延）。
--
-- ⚠️ 本迁移**仅数据回溯，不改 schema**——不新增列/表/约束（Lulus Pemula 复用既有
--    pet_milestones/milestone_completions 结构；catalog 里 C-S16/D-S16/G-S9 是编译期常量）。
--    故 ddl-auto=validate 不受影响。
--
-- 两步（均 WHERE NOT EXISTS 幂等，重跑安全）：
--   ① 给每个存量档案补 Lulus Pemula roster 行（新档案由 assignRoster 物化，无需回溯）。
--      code/sortOrder 按 pet_type：CAT→C-S16/31、DOG→D-S16/31、OTHER→G-S9/16（末位，不扰动现有序）。
--   ② 对「6 新手任务全达成」的档案静默补完成行：S1–S5 里程碑全完成(计数=5) 且 health_records≥1。
--      **走 SQL 直插、不经运行时事件** → 不回灌存量用户历史通知（噪音）；运行时新达成才发通知。

-- ① Lulus Pemula roster 行回填
INSERT INTO pet_milestones (pet_profile_id, code, level, trigger_type, sort_order, created_at)
SELECT p.id,
       CASE p.pet_type WHEN 'CAT' THEN 'C-S16'
                       WHEN 'DOG' THEN 'D-S16'
                       ELSE 'G-S9' END,
       'S', 'SYSTEM_AUTO',
       CASE p.pet_type WHEN 'OTHER' THEN 16 ELSE 31 END,
       now()
FROM pet_profiles p
WHERE NOT EXISTS (
    SELECT 1 FROM pet_milestones m
    WHERE m.pet_profile_id = p.id
      AND m.code = CASE p.pet_type WHEN 'CAT' THEN 'C-S16'
                                   WHEN 'DOG' THEN 'D-S16'
                                   ELSE 'G-S9' END
);

-- ② 已满足 6 任务的档案静默补 Lulus Pemula 完成（不发通知）
INSERT INTO milestone_completions (pet_milestone_id, source, completed_at)
SELECT lp.id, 'SYSTEM_AUTO', now()
FROM pet_milestones lp
JOIN pet_profiles p ON p.id = lp.pet_profile_id
WHERE lp.code IN ('C-S16', 'D-S16', 'G-S9')
  -- 尚未完成（幂等）
  AND NOT EXISTS (
      SELECT 1 FROM milestone_completions mc WHERE mc.pet_milestone_id = lp.id)
  -- S1–S5 里程碑全完成（该宠物 roster 中 S1..S5 且有完成行的正好 5 个）
  AND (
      SELECT COUNT(*) FROM pet_milestones pm
      JOIN milestone_completions mc2 ON mc2.pet_milestone_id = pm.id
      WHERE pm.pet_profile_id = p.id
        AND split_part(pm.code, '-', 2) IN ('S1', 'S2', 'S3', 'S4', 'S5')
  ) = 5
  -- 第 6 任务：至少 1 条健康记录
  AND EXISTS (
      SELECT 1 FROM health_records hr WHERE hr.pet_profile_id = p.id);
