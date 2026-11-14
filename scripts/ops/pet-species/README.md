# V1.5.0 宠物物种扩展 · 存量迁移与上线推送导出

> Story 1.4（`_bmad-output/implementation-artifacts/v1.5.0/1-4-物种存量迁移脚本与推送导出SQL.md`）· 架构 D-19 / D-20 · PRD §3.4（FR-208）/ §3.5（FR-224）。
> **不进 Flyway**：映射是生产宠物 ID 级别的数据，stag / 测试库对不上，Flyway 会在每个环境都跑。

| 文件 | 用途 |
|---|---|
| `run-migration.sh` | 迁移入口：开一个事务、灌映射、执行 `apply-mapping.sql`；默认 ROLLBACK（干跑），加 `apply` 才 COMMIT |
| `apply-mapping.sql` | 迁移本体（纯 SQL，无 psql 元命令 / 事务控制；集成测试经 JDBC 执行它） |
| `export-push-groups.sql` | 只读：导出推送 A / B / C 三组用户 ID（`group_a.csv` 等）+ 自检 |
| `sample-mapping.csv` | 映射表格式样例（5 行假数据） |

## 映射 CSV

UTF-8、带表头、逗号分隔，每只宠物一行，列顺序固定：

```
pet_id, pet_name, current_type, target_species, target_breed, confidence, reason
```

- `target_species`：细分类型代码（`CAT` / `DOG` / `RABBIT` / … / `CIVET` / … / `OTHER`，共 27 个，见 `PetSpecies.java`）。出现不认识的值 → 整批报错。
- `target_breed`：照 PRD 附录 A / B 原样抄，对不上留空。
- `confidence`：`HIGH` / `LOW`。**只迁 HIGH**；LOW 保持 `OTHER`，落推送 A 组。
- `pet_id` 重复 → 整批报错。`pet_name` 与库内名字不一致 → 该行跳过（防行错位）。

## 迁移规则（apply-mapping.sql）

- 只处理当前 `pet_species = 'OTHER'` 的宠物（51 只误存成「其他」的猫也在其中，目标 `CAT`）。
- 用户或运营改过类型的（有 `USER` / `ADMIN` 修改记录）跳过。
- 细分类型与大类同一条语句写；**品种已有不覆盖**，只在为空时写入映射品种。
- **不动 `milestone_track`**（里程碑 / 新手任务清单不换，迁移过来的猫仍用「其他」清单，等用户自己改类型时再换）。
- **不动 `pet_type_changed_at`**（迁移不算用户修改，用户可立即改一次）。
- 写修改记录 `source = 'MIGRATION'`；每只宠物至多一条（唯一索引），可重跑——第二次跑迁移数为 0。
- 不发通知、不发埋点。
- 末尾输出统计：`migrated / <物种>` 计数、`skipped / <原因>` 计数（`LOW`、`NO_TARGET`、`TARGET_IS_OTHER`、`PET_NOT_FOUND`、`NOT_OTHER`、`NAME_MISMATCH`、`ALREADY_CHANGED`、`CHANGED_CONCURRENTLY`）。
- `target_species` / `confidence` 大小写、首尾空格不敏感；`pet_id` 为空的行 → 整批报错。

## 执行步骤

连接参数只走 libpq 环境变量（`PGHOST` / `PGPORT` / `PGDATABASE` / `PGUSER`，密码用 `~/.pgpass` 或 `PGPASSWORD`）；脚本里不写、不打印任何凭证。

**时机**：新版 App（1.5.0）上线后执行（旧版读不到细分类型）。映射表延期（OQ-1）则跳过 ①~⑥、只跑 ⑦：B 组为空，未迁移的宠物仍是 `OTHER`，落 A 组。

1. **备份**：`pg_dump -t pet_profiles -t pet_species_changes -Fc -f pet-species-before.dump`
2. **stag 干跑**：`./run-migration.sh mapping.csv`（看统计、抽查）→ 必要时 `./run-migration.sh mapping.csv apply`
3. **🔴 生产执行前须操作者明确确认**（生产基建动作先确认，不得由脚本或代理自行执行）。
4. **生产干跑**：`./run-migration.sh mapping.csv`，核对统计（迁移数、各跳过原因）与映射表预期一致。
5. **生产真跑**：`./run-migration.sh mapping.csv apply`
6. **核对**（只读）：
   ```sql
   SELECT to_species, count(*) FROM pet_species_changes WHERE source = 'MIGRATION' GROUP BY 1 ORDER BY 2 DESC;
   -- 抽查 20 只（PRD 3.4 AC1）：细分类型 / 品种与映射一致、大类正确、milestone_track 未变
   SELECT p.id, p.name, p.pet_species, p.pet_type, p.milestone_track, p.breed, p.pet_type_changed_at
     FROM pet_profiles p JOIN pet_species_changes c ON c.pet_profile_id = p.id AND c.source = 'MIGRATION'
    ORDER BY random() LIMIT 20;
   ```
7. **导出推送分组**：`psql -X -v ON_ERROR_STOP=1 -f export-push-groups.sql`，得到 `group_a.csv` / `group_b.csv` / `group_c.csv`，末尾自检的交集行应全为 0。

## 推送（FR-224，2026-11-25 11:00 WIB，四组同时发）

- A / B / C：运营推送后台用「指定用户」各建一个推送，跳转页选「宠物类型」（`PET_SPECIES`）。**每个推送最多 50,000 个 ID**，超过就切分成多个同文案推送：
  ```bash
  tail -n +2 group_a.csv | split -l 50000 -d - group_a_part_   # 去表头后每 5 万行一份
  ```
- D（没有宠物）：后台「按条件筛选 → 没有宠物」，不用导出。
- 文案见 PRD §3.5 表格；不用 `{{petName}}`。
