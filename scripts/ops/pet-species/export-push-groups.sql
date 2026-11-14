-- =============================================================================
-- V1.5.0 pet-species Story 1.4 · FR-224 上线推送 A / B / C 三组用户 ID 导出（架构 D-20）
--
-- 只读。迁移脚本（run-migration.sh apply）COMMIT 之后执行：
--     psql -X -v ON_ERROR_STOP=1 -f export-push-groups.sql
-- 输出当前目录 group_a.csv / group_b.csv / group_c.csv（每份一列 user_id，带表头）+ 自检结果。
-- D 组（没有宠物）不导出：运营后台「按条件筛选 → 没有宠物」。
--
-- 基础人群照抄 com.tailtopia.auth.service.OpsAudienceSql.BASE_FILTER（2026-11-14 核对，未漂移）：
--   u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL AND u.enabled = TRUE AND u.status = 'ACTIVE'
-- 只看 pet_profiles.status = 'ACTIVE' 的宠物（待拍板 Q-8 默认）。
-- 每人只进一组，A → B → C 优先：
--   A：有宠物细分类型仍是 OTHER；
--   B：不在 A，有宠物存在 source = 'MIGRATION' 的修改记录；
--   C：不在 A / B，有猫或狗的品种为空，或 lower(trim(breed)) 不在该物种新清单里（手填 / 旧值）。
-- 🔴 cat_breeds / dog_breeds 逐字照 PRD 附录 A，与 App 清单由 petgo_app/test/.../pet_species_export_sql_sync_test.dart 比对：
--    每行一个单引号字符串，列表中间不夹注释。
-- =============================================================================
\set ON_ERROR_STOP 1
-- 三份导出与自检在同一个快照里做（REPEATABLE READ），线上并发改类型不会让同一人落进两组；末尾 ROLLBACK（只读）。
BEGIN ISOLATION LEVEL REPEATABLE READ;

-- BEGIN push_groups_view（纯 SQL；测试与后台只读核对执行这一段）
DROP VIEW IF EXISTS pg_temp.pet_species_push_groups;
CREATE TEMP VIEW pet_species_push_groups AS
WITH base_users AS (
    SELECT u.id AS user_id
      FROM users u
     WHERE u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL
       AND u.enabled = TRUE AND u.status = 'ACTIVE'
),
active_pets AS (
    SELECT p.id, p.owner_id, p.pet_type, p.pet_species, p.breed
      FROM pet_profiles p
      JOIN base_users b ON b.user_id = p.owner_id
     WHERE p.status = 'ACTIVE'
),
cat_breeds(name) AS (
    VALUES
        ('Kucing Kampung'),
        ('Kucing Anggora'),
        ('Kucing Persia'),
        ('British Shorthair'),
        ('Kucing Siam'),
        ('Ragdoll'),
        ('Maine Coon'),
        ('Himalaya'),
        ('Scottish Fold'),
        ('Kucing Bengal'),
        ('Turkish Angora'),
        ('American Shorthair'),
        ('Munchkin'),
        ('Exotic Shorthair'),
        ('Russian Blue'),
        ('Norwegian Forest Cat'),
        ('British Longhair'),
        ('Ocicat'),
        ('Siberian'),
        ('Birman'),
        ('Khao Manee'),
        ('Kucing Sphinx'),
        ('Oriental Shorthair'),
        ('Snowshoe'),
        ('Turkish Van'),
        ('Burmese'),
        ('Japanese Bobtail / Bobtail'),
        ('Li Hua / Dragon Li'),
        ('Scottish Straight'),
        ('Manx'),
        ('Bombay'),
        ('Chartreux'),
        ('Devon Rex'),
        ('Kinkalow'),
        ('Kucing Abyssinian'),
        ('Minuet / Napoleon'),
        ('Raas / Kucing Raas'),
        ('Tonkinese'),
        ('Campuran / mix')
),
dog_breeds(name) AS (
    VALUES
        ('Anjing Kampung'),
        ('Pudel / Poodle'),
        ('Pomeranian'),
        ('Shih Tzu'),
        ('Golden Retriever'),
        ('Chihuahua'),
        ('Siberian Husky'),
        ('Maltese'),
        ('Corgi'),
        ('Maltipoo'),
        ('Anjing Gembala Jerman'),
        ('Kintamani / Anjing Bali'),
        ('Dachshund'),
        ('Pitbull Terrier'),
        ('Labrador Retriever'),
        ('Beagle'),
        ('Bulldog'),
        ('Bichon Frise'),
        ('Rottweiler'),
        ('French Bulldog'),
        ('Samoyed'),
        ('Chow Chow'),
        ('Pug'),
        ('Alaskan Malamute'),
        ('Jack Russell Terrier'),
        ('Shih-poo'),
        ('Yorkshire Terrier'),
        ('Akita'),
        ('Shiba Inu'),
        ('Pekingese'),
        ('Dobermann'),
        ('Miniature Schnauzer'),
        ('Spitz'),
        ('American Bully'),
        ('Basenji'),
        ('Basset Hound'),
        ('Jindo'),
        ('Miniature Pinscher'),
        ('Border Collie'),
        ('Boxer'),
        ('Thai Ridgeback'),
        ('Great Dane'),
        ('Campuran / mix')
),
grp_a AS (
    SELECT DISTINCT ap.owner_id AS user_id
      FROM active_pets ap
     WHERE ap.pet_species = 'OTHER'
),
grp_b AS (
    SELECT DISTINCT ap.owner_id AS user_id
      FROM active_pets ap
     WHERE EXISTS (SELECT 1 FROM pet_species_changes c
                    WHERE c.pet_profile_id = ap.id AND c.source = 'MIGRATION')
       AND ap.owner_id NOT IN (SELECT user_id FROM grp_a)
),
grp_c AS (
    SELECT DISTINCT ap.owner_id AS user_id
      FROM active_pets ap
     WHERE ap.pet_type IN ('CAT', 'DOG')
       AND (NULLIF(trim(ap.breed), '') IS NULL
            OR (ap.pet_type = 'CAT'
                AND lower(trim(ap.breed)) NOT IN (SELECT lower(name) FROM cat_breeds))
            OR (ap.pet_type = 'DOG'
                AND lower(trim(ap.breed)) NOT IN (SELECT lower(name) FROM dog_breeds)))
       AND ap.owner_id NOT IN (SELECT user_id FROM grp_a)
       AND ap.owner_id NOT IN (SELECT user_id FROM grp_b)
)
SELECT user_id, 'A' AS grp FROM grp_a
UNION ALL
SELECT user_id, 'B' AS grp FROM grp_b
UNION ALL
SELECT user_id, 'C' AS grp FROM grp_c;
-- END push_groups_view

-- 导出（运营推送「指定用户」每个最多 50,000 个 ID，超过按 README 切分）。
\copy (SELECT user_id FROM pet_species_push_groups WHERE grp = 'A' ORDER BY user_id) TO 'group_a.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT user_id FROM pet_species_push_groups WHERE grp = 'B' ORDER BY user_id) TO 'group_b.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT user_id FROM pet_species_push_groups WHERE grp = 'C' ORDER BY user_id) TO 'group_c.csv' WITH (FORMAT csv, HEADER true)

-- 自检：三组人数；两两交集为 0；三组与「无 ACTIVE 宠物」用户交集为 0（应全为 0）。
SELECT grp, count(*) AS users FROM pet_species_push_groups GROUP BY grp ORDER BY grp;
SELECT 'A∩B' AS pair, count(*) AS overlap FROM (SELECT user_id FROM pet_species_push_groups WHERE grp = 'A'
       INTERSECT SELECT user_id FROM pet_species_push_groups WHERE grp = 'B') x
UNION ALL
SELECT 'A∩C', count(*) FROM (SELECT user_id FROM pet_species_push_groups WHERE grp = 'A'
       INTERSECT SELECT user_id FROM pet_species_push_groups WHERE grp = 'C') x
UNION ALL
SELECT 'B∩C', count(*) FROM (SELECT user_id FROM pet_species_push_groups WHERE grp = 'B'
       INTERSECT SELECT user_id FROM pet_species_push_groups WHERE grp = 'C') x
UNION ALL
SELECT '(A∪B∪C)∩无ACTIVE宠物', count(*) FROM pet_species_push_groups g
 WHERE NOT EXISTS (SELECT 1 FROM pet_profiles p WHERE p.owner_id = g.user_id AND p.status = 'ACTIVE');

ROLLBACK;
