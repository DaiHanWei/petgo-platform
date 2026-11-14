-- =============================================================================
-- V1.5.0 pet-species Story 1.4 · FR-208 存量宠物物种迁移（架构 D-19）
--
-- 纯 SQL：不含 psql 元命令、不含事务控制（BEGIN / COMMIT 由 run-migration.sh 包；测试经 JDBC 在同一连接上执行）。
-- 前提：当前会话已有临时表 species_mapping（列见 README），并已灌入映射 CSV。
--
-- 规则（PRD 3.4 / 3.3）：
--   * 只迁 confidence = HIGH、target_species 非空、宠物存在、当前 pet_species = 'OTHER'、名字与库内一致（防行错位）、
--     且从没被用户 / 运营改过类型（无 USER / ADMIN 修改记录）的宠物；
--   * pet_species 与 pet_type（大类）同一条 UPDATE 写（ck_pet_profiles_species_group 要求两列一致）；
--   * 品种已有不覆盖，只在为空时写映射品种（截到 60 字，同列长）；
--   * 🔴 不动 milestone_track（迁移不换里程碑 / 新手任务清单）、不动 pet_type_changed_at（迁移不占用户冷却）；
--   * 写修改记录 source = 'MIGRATION'（唯一索引 uq_pet_species_changes_migration 保证可重跑）；
--   * 不发通知、不发埋点（待拍板 Q-4）。
-- 可重跑：第二次跑时宠物已非 OTHER → 判为 NOT_OTHER，迁移数 0。
-- =============================================================================

-- ① 校验：非法 target_species / 重复 pet_id → 整批报错（run-migration.sh 开了 ON_ERROR_STOP，事务随之回滚）。
DO $$
DECLARE
    bad TEXT;
    dup BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM species_mapping WHERE pet_id IS NULL) THEN
        RAISE EXCEPTION 'species_mapping: 有 pet_id 为空的行（CSV 错位？）';
    END IF;
    SELECT string_agg(DISTINCT trim(target_species), ', ') INTO bad
      FROM species_mapping
     WHERE NULLIF(trim(target_species), '') IS NOT NULL
       AND upper(trim(target_species)) NOT IN ('CAT','DOG','RABBIT','HAMSTER','BIRD','CHICKEN','FISH','LIZARD','TURTLE',
           'SUGAR_GLIDER','SNAKE','DUCK','FROG','GUINEA_PIG','CIVET','HEDGEHOG','HERMIT_CRAB','OTTER','SHEEP','COW',
           'SPIDER','SQUIRREL','GOAT','CRAB','INSECT','MOUSE','OTHER');
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'species_mapping: 非法 target_species: %', bad;
    END IF;
    SELECT pet_id INTO dup FROM species_mapping GROUP BY pet_id HAVING count(*) > 1 LIMIT 1;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'species_mapping: pet_id 重复: %', dup;
    END IF;
END $$;

-- ② 逐行判定（每行一个 verdict；MIGRATE 才迁）。
DROP TABLE IF EXISTS pg_temp.species_mapping_eval;
CREATE TEMP TABLE species_mapping_eval AS
SELECT m.pet_id,
       NULLIF(upper(trim(m.target_species)), '') AS target_species,
       m.target_breed,
       CASE
           WHEN upper(trim(coalesce(m.confidence, ''))) <> 'HIGH' THEN 'LOW'
           WHEN NULLIF(trim(m.target_species), '') IS NULL THEN 'NO_TARGET'
           WHEN upper(trim(m.target_species)) = 'OTHER' THEN 'TARGET_IS_OTHER'
           WHEN p.id IS NULL THEN 'PET_NOT_FOUND'
           WHEN p.pet_species <> 'OTHER' THEN 'NOT_OTHER'
           WHEN trim(coalesce(m.pet_name, '')) <> trim(p.name) THEN 'NAME_MISMATCH'
           WHEN EXISTS (SELECT 1 FROM pet_species_changes c
                         WHERE c.pet_profile_id = p.id AND c.source IN ('USER', 'ADMIN')) THEN 'ALREADY_CHANGED'
           ELSE 'MIGRATE'
       END AS verdict
  FROM species_mapping m
  LEFT JOIN pet_profiles p ON p.id = m.pet_id;

-- ③④ 迁移 + 修改记录：先 UPDATE（再次确认仍是 OTHER，防判定后被用户改掉），再按真正改到的行写记录，
--      统计也以真正改到的行为准（verdict 改为 MIGRATED）。细分类型与大类同一条语句写；品种只补空；
--      不动 milestone_track / pet_type_changed_at。
WITH migrated AS (
    UPDATE pet_profiles p
       SET pet_species = e.target_species,
           pet_type = CASE e.target_species WHEN 'CAT' THEN 'CAT' WHEN 'DOG' THEN 'DOG' ELSE 'OTHER' END,
           breed = COALESCE(NULLIF(trim(p.breed), ''), NULLIF(left(trim(e.target_breed), 60), '')),
           updated_at = now()
      FROM species_mapping_eval e
     WHERE e.verdict = 'MIGRATE'
       AND p.id = e.pet_id
       AND p.pet_species = 'OTHER'
    RETURNING p.id, p.pet_species
),
logged AS (
    INSERT INTO pet_species_changes (pet_profile_id, from_species, to_species, type_changed, group_changed, source,
                                     created_at)
    SELECT m.id,
           'OTHER',
           m.pet_species,
           -- pickerType：14 个独立类型是自己，「其他」组 12 个物种归 OTHER → 只有独立类型算「改了类型」
           m.pet_species NOT IN ('CIVET','HEDGEHOG','HERMIT_CRAB','OTTER','SHEEP','COW','SPIDER','SQUIRREL','GOAT',
                                 'CRAB','INSECT','MOUSE','OTHER'),
           -- group：只有 CAT / DOG 的大类不是 OTHER
           m.pet_species IN ('CAT', 'DOG'),
           'MIGRATION',
           now()
      FROM migrated m
    ON CONFLICT DO NOTHING
    RETURNING pet_profile_id
)
UPDATE species_mapping_eval e
   SET verdict = 'MIGRATED'
  FROM migrated m
 WHERE e.pet_id = m.id;

-- 判定为 MIGRATE 却没改到（判定之后被并发改掉）→ 归为跳过原因 CHANGED_CONCURRENTLY。
UPDATE species_mapping_eval SET verdict = 'CHANGED_CONCURRENTLY' WHERE verdict = 'MIGRATE';

-- ⑤ 统计：按目标物种的迁移数 + 跳过原因计数。
SELECT 'migrated' AS kind, target_species AS item, count(*) AS n
  FROM species_mapping_eval WHERE verdict = 'MIGRATED' GROUP BY target_species
UNION ALL
SELECT 'skipped' AS kind, verdict AS item, count(*) AS n
  FROM species_mapping_eval WHERE verdict <> 'MIGRATED' GROUP BY verdict
ORDER BY kind, item;
