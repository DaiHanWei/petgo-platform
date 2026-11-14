# Story 1.4: 物种存量迁移脚本与推送导出SQL（运维脚本）

Status: ready-for-dev

> 版本 v1.5.0 · 主题 pet-species（会话 ④）· 分支 `feat/1.5.0-pet-species`
> 口径优先级：`decision-log.md` > PRD §3.4（FR-208）/ §3.5（FR-224 分组）/ §6 > `architecture-v1.5.0-pet-species-delta.md`（D-19、D-20）。
> 前置：1.1（三列）、1.2（`pet_species_changes` 表与唯一索引）。
> 范围层：**只交付脚本 + 测试**，不进 Flyway、不改业务代码；生产执行在上线后由操作者确认再做（不属于本 story）。
> 迁移：无。

## Story

As a 运营,
I want 一份能干跑、能重跑的脚本把识别出物种的存量宠物迁到新类型，并导出三组互不重叠的推送用户清单,
so that 11-25 的上线推送能按 A / B / C / D 四组各发一条。

## Acceptance Criteria

> 层级：`[L0]` 静态（云端可跑）· `[L1]` Docker postgres（本地）。

### AC1 — 目录与 README `[L0]`
1. 新建 `scripts/ops/pet-species/`：`README.md`、`run-migration.sh`、`apply-mapping.sql`、`export-push-groups.sql`、`sample-mapping.csv`（5 行假数据，列头与 PRD 3.4 一致）。
2. README 写清：
   - 映射 CSV 列：`pet_id, pet_name, current_type, target_species, target_breed, confidence, reason`（UTF-8、带表头）。
   - 执行顺序：① 备份（`pg_dump -t pet_profiles -t pet_species_changes`）② stag 干跑 ③ **生产执行前须操作者明确确认**（生产基建动作先确认）④ 干跑看统计 ⑤ `-v apply=1` 真跑 ⑥ 核对查询 ⑦ 导出三组。
   - 时机：新版 App 上线后执行（PRD 3.4）；映射表延期则只跑导出（B 组为空，未迁移的宠物落 A 组）。
   - 运营推送「指定用户」每个最多 50,000 个 ID，超过按文件切分建多个同文案推送；D 组在后台用「按条件筛选 → 没有宠物」。

### AC2 — `run-migration.sh` + `apply-mapping.sql` `[L1]`（D-19）
1. `run-migration.sh <csv> [apply]`：用 `psql -v ON_ERROR_STOP=1 -v csv=... -v apply=0|1` 开一个事务：建 `TEMP TABLE species_mapping(...)` → `\copy species_mapping FROM :'csv' CSV HEADER` → `\i apply-mapping.sql` → `apply=1` 才 `COMMIT`，否则 `ROLLBACK`。连接参数走环境变量（`PGHOST` 等），**脚本里不写任何凭证**。
2. `apply-mapping.sql`（只用纯 SQL，不含 psql 元命令和事务控制，供测试经 JDBC 执行）按序：
   1. 校验：`target_species`（非空行）不在 27 值 → `RAISE EXCEPTION`（用 `DO $$ … $$`）；`pet_id` 重复 → `RAISE EXCEPTION`。
   2. 候选 = `confidence = 'HIGH'` 且 `target_species` 非空 且 宠物存在 且 当前 `pet_species = 'OTHER'` 且 `trim(pet_name)` = 库内 `name`（防行错位）且该宠物没有 `USER` / `ADMIN` 修改记录。
   3. `UPDATE pet_profiles p SET pet_species = m.target_species, pet_type = CASE m.target_species WHEN 'CAT' THEN 'CAT' WHEN 'DOG' THEN 'DOG' ELSE 'OTHER' END, breed = COALESCE(NULLIF(trim(p.breed), ''), NULLIF(left(trim(m.target_breed), 60), '')), updated_at = now() FROM 候选 m WHERE …`——**品种已有不覆盖**、**不动 `milestone_track` 与 `pet_type_changed_at`**。
   4. `INSERT INTO pet_species_changes (pet_profile_id, from_species, to_species, type_changed, group_changed, source, created_at) SELECT …, 'MIGRATION', now() FROM 候选 ON CONFLICT DO NOTHING`（`type_changed` / `group_changed` 按 `PetSpecies` 规则用 CASE 算：CAT / DOG / 14 个独立类型 vs 12 组物种 + OTHER）。
   5. 输出统计（`SELECT … GROUP BY`）：按 `target_species` 的迁移数；跳过原因计数（LOW、名字不符、非 OTHER、已被改过、宠物不存在）。
3. 可重跑：第二次跑迁移数为 0（宠物已非 OTHER）、无报错。
4. 不发通知、不发埋点（待拍板 Q-4 默认）。

### AC3 — `export-push-groups.sql` `[L1]`（D-20）
1. 只读；基础人群照抄 `B/auth/service/OpsAudienceSql.BASE_FILTER`（`u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL AND u.enabled = TRUE AND u.status = 'ACTIVE'`），文件里注明出处与核对日期。
2. 只看 `pet_profiles.status = 'ACTIVE'` 的宠物（待拍板 Q-8 默认）。
3. 分组（每人只进一组，A → B → C 优先）：
   - **A**：有宠物 `pet_species = 'OTHER'`。
   - **B**：不在 A；有宠物存在 `pet_species_changes.source = 'MIGRATION'` 的记录。
   - **C**：不在 A / B；有 `pet_type IN ('CAT','DOG')` 的宠物，且其 `breed` 为空，或 `lower(trim(breed))` 不在对应物种的新清单里。新清单写成 `WITH cat_breeds(name) AS (VALUES ('Kucing Kampung'), …)`（猫 39）、`dog_breeds`（狗 43），**逐字照 PRD 附录 A**（含 `Campuran / mix`），与 App 清单由 3.1 的守卫测试 `pet_species_export_sql_sync_test.dart` 比对——所以 VALUES 每行一个、单引号字符串、`--` 注释不夹在列表中间。
4. 三段 `\copy (SELECT user_id …) TO 'group_a.csv' CSV HEADER`（b、c 同）；另给一份不含 `\copy` 的同名视图式查询，供测试与后台只读核对。
5. 末尾自检查询：A∩B、A∩C、B∩C 均为 0；A∪B∪C 与「无 ACTIVE 宠物用户」交集为 0；输出三组人数。

### AC4 — 脚本集成测试 `[L1]`
1. `petgo-backend/src/test/java/com/tailtopia/profile/species/PetSpeciesOpsScriptIntegrationTest`（继承 `ApiIntegrationTest`）：同一 JDBC 连接上建 `species_mapping` 临时表、插测试映射，再执行 `../scripts/ops/pet-species/apply-mapping.sql`（Maven 工作目录是 `petgo-backend/`，用相对路径读文件）。
2. 覆盖：HIGH 迁移（兔、麝香猫、误存的猫 → `pet_type=CAT`、`milestone_track` 仍 OTHER）；LOW 不迁；名字不符跳过；已有品种不覆盖、空品种写入映射品种；用户改过的跳过；重复 `pet_id` 报错；非法物种报错；重跑幂等。
3. 导出：造 A / B / C / D / 只读宠物 / 已注销用户各一，执行导出查询（不含 `\copy` 版），断言分组与互斥（PRD 3.5 AC1）。
4. 迁移后 51 只猫场景：里程碑列表仍是 G 清单、新手任务进度不变（PRD 3.4 AC2）——调 `GET /api/v1/pet-profiles/me/milestones`、`GET /api/v1/me/newbie-tasks` 断言。

## Tasks / Subtasks

- [ ] **T0 开工核对**：确认 1.2 的 `pet_species_changes` 列名、唯一索引名与架构一致；`OpsAudienceSql.BASE_FILTER` 未漂移
- [ ] **T1** `apply-mapping.sql` + `run-migration.sh` + `sample-mapping.csv`（AC2）
- [ ] **T2** `export-push-groups.sql`（AC3）
- [ ] **T3** README（AC1）
- [ ] **T4** 集成测试（AC4）；能编译即可，云端不跑；Completion Notes 标「L1 待本地验收」
- [ ] **T5** `shellcheck run-migration.sh`（云端有就跑，没有记一句）

## Dev Notes

- 为什么不进 Flyway：映射是**生产宠物 ID** 级别的数据，stag / 测试库 ID 对不上；Flyway 迁移会在每个环境都跑。
- 51 只误存的猫在库里就是 `pet_species = 'OTHER'`（1.1 回填自 `pet_type`），所以候选条件只需 `pet_species = 'OTHER'`。
- 迁移改大类（OTHER → CAT）会让 `ck_pet_profiles_species_group` 要求两列同时改——UPDATE 必须同一条语句同时写 `pet_species` 与 `pet_type`。
- 不写 `milestone_track`：PRD 3.3.3「后台迁移不换，迁移过来的猫仍用其他清单，等用户自己改类型时再换」。
- 不写 `pet_type_changed_at`：PRD 3.3.1「迁移不算用户的修改」，迁移过的宠物用户可立即改一次。
- B 组依赖迁移完成；迁移延后（OQ-1）则 B 组为空。

### 测试
- L0：无（脚本无编译产物）；`run-migration.sh` 语法检查。
- L1：`PetSpeciesOpsScriptIntegrationTest`。

## Dev Agent Record

### Agent Model Used

### Completion Notes List

### File List
