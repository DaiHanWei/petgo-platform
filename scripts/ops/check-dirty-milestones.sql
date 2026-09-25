-- 排查：「其他」类宠物（OTHER）被健康记录错点亮的里程碑规模
--
-- 成因：健康记录 → 里程碑的完成映射按「物种前缀 + 语义后缀」寻址
--       VACCINE→M3 / DEWORM→M4 对猫狗成立，对 OTHER 全错：
--       G-M3 = 陪伴满 30 天、G-M4 = 成长日历记录满 10 条
--
-- 判据：G-M3「陪伴满 30 天」若在建档不满 30 天时就完成，一定是错点亮
--       （真达成不可能在 30 天内）。G-M4 用「当前记录数仍不足 10 条」近似。
--
-- 只读，不改任何数据。
--
-- 跑法：
--   ssh dai@62.146.239.156 'docker exec -i petgo-postgres psql -U petgo -d petgo' \
--     < scripts/ops/check-dirty-milestones.sql

\pset pager off

\echo '===== ① G-M3「陪伴满 30 天」错点亮规模（判据确定，非估算）====='
SELECT
  COUNT(*)                         AS "错点亮条数",
  COUNT(DISTINCT p.id)             AS "涉及宠物数",
  MIN(mc.completed_at)::date       AS "最早",
  MAX(mc.completed_at)::date       AS "最晚"
FROM milestone_completions mc
JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
JOIN pet_profiles   p  ON p.id  = pm.pet_profile_id
WHERE p.pet_type = 'OTHER'
  AND pm.code    = 'G-M3'
  AND mc.completed_at < p.created_at + INTERVAL '30 days';

\echo ''
\echo '===== ② 成因佐证：同一时刻附近是否真有健康记录 ====='
SELECT
  COUNT(*) FILTER (WHERE hit) AS "有健康记录佐证",
  COUNT(*) FILTER (WHERE NOT hit) AS "无佐证需人工看"
FROM (
  SELECT EXISTS (
           SELECT 1 FROM health_records hr
           WHERE hr.pet_profile_id = p.id
             AND hr.type IN ('VACCINE','DEWORM')
             AND hr.created_at BETWEEN mc.completed_at - INTERVAL '30 seconds'
                                   AND mc.completed_at + INTERVAL '30 seconds'
         ) AS hit
  FROM milestone_completions mc
  JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
  JOIN pet_profiles   p  ON p.id  = pm.pet_profile_id
  WHERE p.pet_type = 'OTHER'
    AND pm.code    = 'G-M3'
    AND mc.completed_at < p.created_at + INTERVAL '30 days'
) t;

\echo ''
\echo '===== ③ G-M4「记录满 10 条」疑似错点亮（近似判据）====='
SELECT
  COUNT(*)             AS "疑似条数",
  COUNT(DISTINCT p.id) AS "涉及宠物数"
FROM milestone_completions mc
JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
JOIN pet_profiles   p  ON p.id  = pm.pet_profile_id
WHERE p.pet_type = 'OTHER'
  AND pm.code    = 'G-M4'
  AND EXISTS (
        SELECT 1 FROM health_records hr
        WHERE hr.pet_profile_id = p.id
          AND hr.type = 'DEWORM'
          AND hr.created_at BETWEEN mc.completed_at - INTERVAL '30 seconds'
                                AND mc.completed_at + INTERVAL '30 seconds'
      );

\echo ''
\echo '===== ④ 分母：OTHER 类宠物总数 / 受影响占比 ====='
SELECT
  (SELECT COUNT(*) FROM pet_profiles WHERE pet_type = 'OTHER') AS "OTHER宠物总数",
  (SELECT COUNT(DISTINCT p.id)
     FROM milestone_completions mc
     JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
     JOIN pet_profiles   p  ON p.id  = pm.pet_profile_id
    WHERE p.pet_type = 'OTHER'
      AND pm.code IN ('G-M3','G-M4')
      AND mc.completed_at < p.created_at + INTERVAL '30 days') AS "受影响宠物数";
