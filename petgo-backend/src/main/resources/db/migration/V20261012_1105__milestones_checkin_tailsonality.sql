-- V1.3.4 Story 4.1 · FR-126 · AD-18：里程碑新增「第一次场所打卡」「解锁性格测试结果」——名册补行 + 存量回填。
-- 预分配版本号 V20261012_1105（决策 E7 时间戳制；不得另取）。
--
-- 新增 code（MilestoneCatalog 末尾追加，既有 code / sort_order 一个不变）：
--   CAT  C-S17(32) 打卡 / C-S18(33) 性格测试
--   DOG  D-S17(32) 打卡 / D-S18(33) 性格测试
--   OTHER G-S10(17) 打卡 / G-S11(18) 性格测试
--
-- ⚠️ 直接写表、不经 MilestoneCompletionService：不发 MilestoneCompletedEvent / 通知 / 埋点。
--    celebrated_at = completed_at：老用户进列表页不补弹（PRD「存量回填」，沿用 V20260910_1108 语义）。
-- 幂等：三段 INSERT 均带 NOT EXISTS；milestone_completions(pet_milestone_id) 另有唯一约束兜底。

-- ① 名册补行：只给「已有名册」的宠物补（assignRoster 见到任一行就整段跳过，
--    若给无名册宠物只插两行，它将永远拿不到其余条目；无名册宠物首次打开列表时会惰性生成完整名册）。
INSERT INTO pet_milestones (pet_profile_id, code, level, trigger_type, sort_order, created_at)
SELECT p.id, v.code, 'S', 'SYSTEM_AUTO', v.sort_order, now()
FROM pet_profiles p
JOIN (VALUES ('CAT',   'C-S17', 32), ('CAT',   'C-S18', 33),
             ('DOG',   'D-S17', 32), ('DOG',   'D-S18', 33),
             ('OTHER', 'G-S10', 17), ('OTHER', 'G-S11', 18)) AS v(pet_type, code, sort_order)
  ON v.pet_type = p.pet_type
WHERE EXISTS (SELECT 1 FROM pet_milestones r WHERE r.pet_profile_id = p.id)
  AND NOT EXISTS (
      SELECT 1 FROM pet_milestones m WHERE m.pet_profile_id = p.id AND m.code = v.code);

-- ② 打卡回填：completed_at = 该宠物首次打卡时间（place_checkin_pets → place_checkins.checked_at 最小值）。
INSERT INTO milestone_completions (pet_milestone_id, source, completed_at, celebrated_at)
SELECT pm.id, 'SYSTEM_AUTO', fc.first_at, fc.first_at
FROM pet_profiles p
JOIN pet_milestones pm
  ON pm.pet_profile_id = p.id
 AND pm.code = CASE p.pet_type WHEN 'CAT' THEN 'C-S17' WHEN 'DOG' THEN 'D-S17' ELSE 'G-S10' END
JOIN LATERAL (
    SELECT MIN(pc.checked_at) AS first_at
    FROM place_checkin_pets cp
    JOIN place_checkins pc ON pc.id = cp.checkin_id
    WHERE cp.pet_profile_id = p.id
) fc ON fc.first_at IS NOT NULL
WHERE NOT EXISTS (SELECT 1 FROM milestone_completions mc WHERE mc.pet_milestone_id = pm.id);

-- ③ 性格测试回填：只认完整解读（unlocked_at）；只单独买配型（match_unlocked_at 非空、unlocked_at 为空）不算。
INSERT INTO milestone_completions (pet_milestone_id, source, completed_at, celebrated_at)
SELECT pm.id, 'SYSTEM_AUTO', fu.first_at, fu.first_at
FROM pet_profiles p
JOIN pet_milestones pm
  ON pm.pet_profile_id = p.id
 AND pm.code = CASE p.pet_type WHEN 'CAT' THEN 'C-S18' WHEN 'DOG' THEN 'D-S18' ELSE 'G-S11' END
JOIN LATERAL (
    SELECT MIN(t.unlocked_at) AS first_at
    FROM tailsonality_results t
    WHERE t.pet_profile_id = p.id
      AND t.unlocked_at IS NOT NULL
) fu ON fu.first_at IS NOT NULL
WHERE NOT EXISTS (SELECT 1 FROM milestone_completions mc WHERE mc.pet_milestone_id = pm.id);
