---
title: "V1.5.0 pet-species 主题 · 架构增量"
status: complete
completedAt: '2026-11-14'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.5.0/宠物物种扩展/1-5-0宠物物种扩展prd.md
  - _bmad-output/planning-artifacts/v1.5.0/宠物物种扩展/ui-1-5-0-pet-species-integrated-v1.html（App 26 屏，拆页-App/）
  - _bmad-output/planning-artifacts/v1.5.0/宠物物种扩展/ui-admin-1-5-0-pet-species-integrated-v1.html（后台 6 屏，拆页-后台/）
  - _bmad-output/planning-artifacts/v1.5.0/decision-log.md（2026-11-13 宠物物种扩展各条）
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md（2026-11-14 V1.5.0 六会话共用地基）
  - _bmad-output/implementation-artifacts/v1.5.0/0-1-六会话共用地基.md
version: v1.5.0
theme: pet-species
branch: feat/1.5.0-pet-species（从 dev_1.5.0 地基 9680309cf 之后切出）
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: dev_1.5.0 @ 9680309cf（2026-11-14 核实）
---

# V1.5.0 pet-species 主题 · 架构增量

> 只写本主题新增或改变的技术决定，均对照 `dev_1.5.0` @ 9680309cf 核实。**权威顺序**：`decision-log.md`（2026-11-13 宠物物种扩展各条）> PRD（变更记录日期最晚的一条为准）> 本文件 > UI 稿。与代码现状冲突处已在 §1.2 列出、以代码为准。
>
> 🔴 **本线最后合并**（在送礼优化、首日与新手任务、健康记录提醒三条线之后）。所有对显示 / 选择器的改动都按「只改显示、不改别人逻辑、新增字段只追加」写，合并后的对齐由 Story 4.2 专门处理（§7）。

## 1. 项目上下文

### 1.1 需求概览

| 需求 | 内容 | 架构含义 |
|---|---|---|
| FR-205 | 类型 3 → 15，各类型品种清单；「其他」按 12 个物种分组 | 新增「细分类型」`pet_species`；原 `pet_type` 降为「大类」只由服务端推出；App 清单 + 统一显示函数 |
| FR-206 | 类型 / 品种选择抽屉加搜索、「Pakai "xxx"」手填 | 纯 App：一个新抽屉组件，三处共用（建档 / 编辑 / 身份证建卡）；共用匹配函数 |
| FR-207 | 类型可改，90 天一次（WIB） | 更新接口接 `species`；冷却校验；改记录表；大类改变时里程碑 / 新手任务清单切换 |
| FR-208 | 存量「其他」与误存的猫迁到新类型 | 一次性运维 SQL（不进 Flyway），按 Albar 逐只映射 CSV，只迁 HIGH |
| FR-224 | 上线推送 4 组；跳转页 `PET_SPECIES` | 导出 SQL（A / B / C 三组用户 ID）；App `ops_target.dart` 加值与落地逻辑；后台跳转说明文案 |
| AB-42 | 后台用户详情显示细分类型、修改记录、运营代改 | 用户详情「宠物档案」页签改卡片；代改弹层沿用 `confirm-modal`；新权限码 + 审计 |

**非功能**：
- 兼容：旧版 App（≤ 1.4.2）不传 `species`，读 `petType` 仍是三值；创建 / 编辑 / 建卡都不能把已迁移的兔子改回 OTHER（§4.5）。
- 一致性：`pet_species` 与 `pet_type` 由 DB CHECK 绑死（§3.2），任何写入只经实体单一入口。
- 护栏：Flyway 时间戳号；改 CHECK 一律 DROP + ADD 全集；不引新依赖（App 去变音符号自写小表，不加包）；日志不记宠物名 / 品种原文以外的 PII（品种不是 PII，但修改原因可能含工单号，不进日志）。

### 1.2 已核实的代码现状（dev_1.5.0 @ 9680309cf，与 PRD 写法有出入处以代码为准）

后端前缀 `B/` = `petgo-backend/src/main/java/com/tailtopia/`；App 前缀 `A/` = `petgo_app/lib/`。

1. `B/profile/domain/PetType.java` = `CAT / DOG / OTHER`；`pet_profiles.pet_type VARCHAR(16) NOT NULL` + `ck_pet_profiles_pet_type`（`V25__add_pet_type.sql`）。`PetProfile.petType` 无 setter（「创建后不可改」写在注释里）。
2. `PetProfileUpdateRequest` 语义是「null = 不改」，`breed` 传空串 = 清空（`ProfileService.update` 里 `blankToNull`）。编辑接口 `PATCH /api/v1/pet-profiles/me?petId=`；`petWriteGuard.requireWritable` 拦只读宠物。
3. `PetProfileCreateRequest.petType` 是 `@NotBlank String`；`ProfileService.parsePetType` 非法值 422。
4. **里程碑清单按 `pet_type` 物化**：`MilestoneService.assignRoster(petId, petType)`（`pet_milestones`，唯一键 `(pet_profile_id, code)`，code 前缀 C / D / G）。**按 `getPetType()` 算里程碑 code 的调用点共 22 处**：`MilestoneService:129`、`MilestoneScheduledCompleter:49`、`NewbieTaskService:55,63`、`MilestoneCompletionService:78,92,100,118,127,185,193,260,267,271`、`AccountMilestoneService:110,120,158`、`VisitorProjectionService:285,298`、`TimelineService:649,664`、`ProfileService:138`。**FR-208 的 51 只猫大类变 CAT 但清单不换**，所以这些调用点不能再读 `pet_type`（§3.3 `milestone_track`）。
5. `milestone_completions` FK → `pet_milestones`（无级联）；`MilestoneCompletion.ofCelebrated(...)` 已存在（「已完成且已庆祝、不发事件」，`V20261012_1105` 回填语义）。成长值 / 通知由 `MilestoneCompletedEvent` 驱动（`GrowthActivityListener`、`MilestoneNotifyListener`），**不发事件 = 不发奖励**。
6. `PetMilestoneRepository` 读路径 5 个：`findByPetProfileIdOrderBySortOrderAsc`、`existsByPetProfileId`、`findByPetProfileIdAndCode`、`deleteByPetProfileId`、`findIdsByOwnerIdAndCodeIn`（JPQL）。
7. 身份证：`CreateIdCardRequest.petType` 白名单 `CAT|DOG|OTHER`；`IdCard.petType VARCHAR(16)` 快照；卡号物种码 `CardNumberService.speciesCode`（狗 01 / 猫 02 / 其他 00）与「编号沿用」查询 `IdCardRepository.findNumberSourceCard(..., petType)` 都按这个快照值。**PRD「快照列改存细分类型」不采用**：会让 `findNumberSourceCard` 与物种码失配，改为加新列（§3.5）。
8. AI 分诊：`PetProfileQueryService:213` 用 `petType.name()` 构造 `TriagePetContext.species`；`GeminiDeveloperApiClient.speciesLabel` 三值映射（`OTHER` → 「其他伴侣动物」）。
9. **兽医端 `PetIdentityView.species` 已被占用、语义是大类**（`VetConsultService` / `VetInboxItem` / `VetSessionView` 透传）。本线不改它的语义（§4.6、待拍板 Q-5）。
10. `OpsAppPage`（后端）已由地基加 `STARTER_PACK`、`PET_SPECIES`，后台四语标签已在；**App `A/features/ops/domain/ops_target.dart` 仍只有 5 个值**（② 加 `starterPack`、本线加 `petSpecies`，同一枚举两边追加 → 合并必冲突，按「两值都留、`starterPack` 在前」解）。跳转说明「5 个固定页」在 `admin.opsPopups` / `ops-target-fields.html` 的提示词条里。
11. 后台用户详情「宠物档案」页签：`fragments/drawer-users.html:93-110` 一宠一卡，类型徽标直接显示枚举名（`p.petType()`），头像恒 🐾；数据来自 `AdminUserService:442` → `AdminUserDetailView.PetRow(id, name, petType, breed, ...)`。权限码是常量（`AdminPermissions`，无迁移），页面权限登记在 `AdminPageCatalog`（`users` 页的写权限列表）。审计入口 `AdminAuditService.record(actorId, AuditActions.X, targetType, targetId, summary)`。
12. 运营通知「指定用户」收**数字用户 ID**，上限 `MAX_IDS = 50_000`；基础人群过滤 `OpsAudienceSql.BASE_FILTER = u.role='USER' AND u.account_type='REAL' AND u.deleted_at IS NULL AND u.enabled = TRUE AND u.status='ACTIVE'`；「按条件筛选」已有 `hasPet=false`。
13. App 现状显示调用点（**全部要收进统一函数**，§5.2 表）：`pet_form_fields.dart` `petTypeDisplay`；`pet_age.dart` `petSpeciesLabel`；`pet_species_avatar.dart` `petSpeciesEmoji / petSpeciesTint / PetSpeciesAvatar`；`pet_info_card.dart:65-101`；`pet_profile_edit_page.dart:337`、`pet_profile_create_page.dart:473`（头像占位）；`gift_panel_sheet.dart:318`、`gift_page.dart:410`；`boarding_pass_card.dart:225`；`followed_pet_row.dart:30-41`；`recommended_pet_card.dart:214`；`pet_card_page.dart:96,327`（`_speciesLabel` 未知值兜底成「猫」，且头像占位写死 🐱——存量 bug，顺手修）；身份证卡面 `ktp_fields.dart:75` `spesies`、`new_card_text.dart:16` `NewCardDefaults.spesies`（SIM / 居住证）、`student_card.dart:59` `_species`。
14. App **按大类做逻辑**、本线**不改**的点：`ktp_fields.dart:112` `speciesCodeFor`（预览卡号）、`tailsonality_*`（题库只有猫狗）、`shop_reco_grid.dart:44`（DOG / CAT / UNIVERSAL）、`age_card_page.dart` / `pet_insights_page.dart:203`（年龄卡只有猫狗）、`milestone_celebration.dart:236,636`（按 code 前缀）、`profile_created_flow.dart:38`（建档时 C-S1 / D-S1 / G-S1，建档时 track = 大类，不受影响）。兽医页 `vet_*_page.dart` 的 emoji 见待拍板 Q-5。
15. App 现状选择器：`SpeciesField._pick` 是 3 行 `ListTile` 的 `showModalBottomSheet`；`BreedField` 有清单 + 「Lainnya」弹 `AlertDialog` 手填；`breedsForPetType` 只有猫 22 / 狗 35。身份证建卡 `id_card_pet_type_fields.dart`（333 行）是独立的输入框下拉组件 `IdCardSpeciesDropdown / IdCardBreedDropdown`。
16. App 编辑页编辑的是「当前宠物」（`petProfileProvider` ← `effectivePetIdProvider`），切换当前宠物用 `currentPetIdProvider.notifier.select(petId)`；路由 `/profile/edit`、`/profile/create`。选宠物抽屉 `showPetPickerSheet(multiSelect: false)`；确认抽屉 `showConfirmSheet`。
17. 埋点：后端 `shared.analytics.AnalyticsClient.capture(distinctId, event, props)`（自建埋点线合入后换实现，接口不变）；App `core/analytics/analytics.dart` 白名单门面，`test/analytics/v112_events_test.dart` 已登记 `pet_` 前缀。

### 1.3 跨线依赖与合并风险

| 别的线 | 会撞的地方 | 本线的写法 |
|---|---|---|
| ① 健康提醒（`feat/1.5.0-health-reminder`） | `pet_profiles` 加列（养猫 / 怀孕哺乳）、`PetProfile` 实体 / DTO / `PetProfileUpdateRequest`、编辑档案页（UI 归 ①） | 只**追加**字段与构造参数；编辑页只改类型框与品种框两块；健康规则读 `pet_type`（大类），本线不动 |
| ② 送礼优化 → 超值礼包 | `gift_panel_sheet.dart` / `gift_page.dart` 类型显示；App `OpsAppPage` 枚举（`starterPack`） | 只把 switch 换成统一函数调用；枚举冲突两值都留 |
| ③ 首日与新手任务 | 建档页（流程）、新手任务清单；FR-174 同物种匹配「只读物种字段、不写死枚举」 | 建档页只改类型 / 品种框与红字；FR-174 合并后改读 `pet_species`（Story 4.2） |
| 地基 0.1 | `OpsAppPage.PET_SPECIES`、后台标签 | 只实现 App 值与跳转、后台提示文案；**不碰 `ck_notifications_type`**（本线不新增通知类型） |

## 2. 起步与技术栈

棕地增量，不选模板，不加依赖。
- App 去变音符号：自写 `foldDiacritics(String)`（Latin-1 + Latin Extended-A 映射表，约 60 个字符），不引 `diacritic` 等包。
- App 抽屉：自写 `PetSpeciesPickerSheet` / `PetBreedPickerSheet`（共用一个外壳），头部照 `PetPickerSheet`（拖拽条 36×4 + 标题 18/w700），选中态照身份证下拉 `_option`（浅紫底 + 紫字 + 勾）。
- 护栏不变：异步只用 `@Async` + DB 状态机；禁 MQ / 缓存 / 新中间件；`ddl-auto=validate`。

## 3. 数据决策

### 3.1 D-1 细分类型枚举 `PetSpecies`

`B/profile/domain/PetSpecies.java`，**顺序固定、只往后加**（App 清单的显示顺序在 App 侧另定，不依赖枚举序）：

```
CAT, DOG, RABBIT, HAMSTER, BIRD, CHICKEN, FISH, LIZARD, TURTLE, SUGAR_GLIDER, SNAKE, DUCK, FROG, GUINEA_PIG,
CIVET, HEDGEHOG, HERMIT_CRAB, OTTER, SHEEP, COW, SPIDER, SQUIRREL, GOAT, CRAB, INSECT, MOUSE,
OTHER
```
共 27 值（14 个独立类型 + 12 个「其他」组物种 + `OTHER`）。最长 `SUGAR_GLIDER` 12 字符。

方法：
- `PetType group()`：`CAT → CAT`、`DOG → DOG`、其余 → `OTHER`。**大类的唯一推导处**，全仓库不许另写。
- `PetSpecies pickerType()`：独立类型返回自己；12 个「其他」组物种与 `OTHER` 返回 `OTHER`（= App 类型宫格上的「Lainnya」）。**「是否改了类型」只比较 `pickerType()`**（§4.2）。
- `String zhName()`：后台与 AI 用的中文名（猫 / 狗 / 兔 / 仓鼠 / 鸟 / 鸡 / 鱼 / 蜥蜴 / 龟 / 蜜袋鼯 / 蛇 / 鸭 / 青蛙 / 豚鼠 / 麝香猫 / 迷你刺猬 / 寄居蟹 / 水獭 / 绵羊 / 牛 / 蜘蛛 / 松鼠 / 山羊 / 螃蟹 / 昆虫 / 鼠类 / 其他）。后台显示「其他」组物种为「其他·麝香猫」由模板拼（`pickerType()==OTHER && this!=OTHER`）。
- `static Optional<PetSpecies> parse(String raw)`：trim + 大写；不认识 → empty（调用方 422）。

### 3.2 D-2 `pet_profiles` 三列（迁移 ① `V20261115_0400`）

```sql
ALTER TABLE pet_profiles ADD COLUMN pet_species VARCHAR(32);
ALTER TABLE pet_profiles ADD COLUMN milestone_track VARCHAR(16);
ALTER TABLE pet_profiles ADD COLUMN pet_type_changed_at TIMESTAMPTZ;
UPDATE pet_profiles SET pet_species = pet_type, milestone_track = pet_type;
ALTER TABLE pet_profiles ALTER COLUMN pet_species SET NOT NULL;
ALTER TABLE pet_profiles ALTER COLUMN milestone_track SET NOT NULL;
ALTER TABLE pet_profiles ADD CONSTRAINT ck_pet_profiles_pet_species CHECK (pet_species IN (<27 值，同 §3.1 顺序>));
ALTER TABLE pet_profiles ADD CONSTRAINT ck_pet_profiles_species_group CHECK (
    (pet_species = 'CAT' AND pet_type = 'CAT') OR (pet_species = 'DOG' AND pet_type = 'DOG')
    OR (pet_species NOT IN ('CAT', 'DOG') AND pet_type = 'OTHER'));
ALTER TABLE pet_profiles ADD CONSTRAINT ck_pet_profiles_milestone_track CHECK (milestone_track IN ('CAT', 'DOG', 'OTHER'));
```
- `pet_type` 列、`ck_pet_profiles_pet_type`、`PetType` 枚举**不动**。
- 回填一条 UPDATE 足够（宠物量级数千）。存量回填后三列关系：`pet_species = pet_type = milestone_track`。
- 以后加物种：DROP + ADD `ck_pet_profiles_pet_species` 重列全集，值取**当前树最后一条重建它的迁移**（同 `ck_notifications_type` 规则）；`ck_pet_profiles_species_group` 不用改。
- 实体 `PetProfile` 追加（**只追加，不改既有字段顺序与注释**，与 ① 的加列合并时冲突最小）：
  - `@Enumerated(STRING) @Column(name="pet_species", nullable=false, length=32) PetSpecies petSpecies`
  - `@Enumerated(STRING) @Column(name="milestone_track", nullable=false, length=16) PetType milestoneTrack`
  - `@Column(name="pet_type_changed_at") Instant petTypeChangedAt`
  - 单一写入口 `void applySpecies(PetSpecies s)`：同时写 `petSpecies` 与 `petType = s.group()`；**不写** `milestoneTrack`（由 §3.4 切换器单独写）。`PetProfile.create(...)` 保留原签名（内部 `species = PetSpecies.valueOf(petType.name())`），另加 `create(..., PetSpecies species, ...)` 重载；建档时 `milestoneTrack = species.group()`。
  - 守卫测试 `PetSpeciesWriteGuardTest`：主代码里除 `PetProfile` 自身外不得出现 `petType =` 赋值 / 反射写（照 `PetStatusSingleEntryGuardTest` 写法）。

### 3.3 D-3 里程碑清单归属 `milestone_track`

- 含义：这只宠物的里程碑 / 新手任务清单按哪一套（CAT / DOG / OTHER）。**与大类 `pet_type` 分离**，因为 FR-208 迁移的 51 只猫大类变 CAT、清单仍是 OTHER（PRD 3.3.3 / 3.4）。
- 读：§1.2 第 4 条的 22 处调用点**一律**从 `getPetType()` 改为 `getMilestoneTrack()`（`MilestoneCatalog.forType` / `MilestoneAutoCompleteMap.codeOf` / `lulusPemulaCode` / `newbiePrereqCodes` / `AccountMilestoneFlag.codeOf` 的入参）。加守卫测试 `MilestoneTrackUsageGuardTest`：`profile/service/Milestone*.java`、`NewbieTaskService`、`AccountMilestoneService`、`VisitorProjectionService`、`TimelineService` 里不得再出现 `getPetType()`。
- 写：建档 = 大类；用户改类型 / 运营代改且大类改变 → 切换器（§3.4）改为新大类；**迁移脚本不写**。

### 3.4 D-4 里程碑清单切换（迁移 ③ `V20261115_0420`）

```sql
ALTER TABLE pet_milestones ADD COLUMN retired_at TIMESTAMPTZ;
CREATE INDEX idx_pet_milestones_active ON pet_milestones (pet_profile_id) WHERE retired_at IS NULL;
```
- **活跃清单** = `retired_at IS NULL` 的行。§1.2 第 6 条的读路径改为只读活跃行（`findActiveByPetProfileIdOrderBySortOrderAsc`、`existsActiveByPetProfileId`、`findActiveByPetProfileIdAndCode`、`findIdsByOwnerIdAndCodeIn` 加 `m.retiredAt is null`）；`deleteByPetProfileId`（删档级联）照旧删全部。
- 切换器 `profile.service.MilestoneTrackSwitcher.switchTo(PetProfile pet, PetType newTrack)`，在改类型的同一事务里调用，`newTrack == pet.milestoneTrack` 时直接返回：
  1. 当前活跃行全部 `retired_at = now`（**不删**：完成记录、徽章、分享页、时间线条目、已发成长值全保留，PRD「保留不收回」）。
  2. 按 `MilestoneCatalog.forType(newTrack)`：已存在的同 code 行（来回改过）`retired_at = null` 复活；缺的新建（同 `assignRoster`）。
  3. **按事实补记**（不发事件、不发奖励、不进补庆祝）：对 `MilestoneAutoEvent` 每个事件，若旧 track 的 code 已完成、新 track 的 code 存在且未完成 → 插 `MilestoneCompletion.ofCelebrated(newRowId, SYSTEM_AUTO, 旧完成时间)`；Lulus Pemula 同理（旧 `lulusPemulaCode` 已完成 → 新的也记完成）。打卡类（`USER_CHECKIN`）无语义对应，不补。
  4. 账号级 flag：调 `AccountMilestoneService.applyExistingFlagsToPet(petId)`（已有方法，幂等）。
  5. `pet.milestoneTrack = newTrack`。
- 结果：里程碑列表 / 进度 / 新手任务按新清单；旧清单完成过的、新清单有对应的，显示为已完成；没有对应的不在列表里，但历史（时间线、分享页）仍在。是否在列表外另给「旧徽章」展示位见待拍板 Q-1。

### 3.5 D-5 类型修改记录（迁移 ② `V20261115_0410`）

```sql
CREATE TABLE pet_species_changes (
    id                 BIGSERIAL    PRIMARY KEY,
    pet_profile_id     BIGINT       NOT NULL REFERENCES pet_profiles (id) ON DELETE CASCADE,
    from_species       VARCHAR(32)  NOT NULL,
    to_species         VARCHAR(32)  NOT NULL,
    type_changed       BOOLEAN      NOT NULL,   -- pickerType 是否变（=是否占用户冷却）
    group_changed      BOOLEAN      NOT NULL,   -- 大类是否变
    source             VARCHAR(16)  NOT NULL,   -- USER / MIGRATION / ADMIN
    operator_admin_id  BIGINT,                  -- ADMIN 时必填；不建 FK（同审计口径）
    reason             VARCHAR(200),            -- ADMIN 时必填
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_pet_species_changes_source CHECK (source IN ('USER', 'MIGRATION', 'ADMIN'))
);
CREATE INDEX idx_pet_species_changes_pet_created ON pet_species_changes (pet_profile_id, created_at DESC);
CREATE UNIQUE INDEX uq_pet_species_changes_migration ON pet_species_changes (pet_profile_id) WHERE source = 'MIGRATION';
```
- FK `ON DELETE CASCADE`：删档 / 注销物理删宠时随之删除（`ProfileDeletionService` 不必改；测试 `deleteAll` 不被阻断）。
- `from_species / to_species` 不加 CHECK（历史记录，枚举以后改名不该让旧行失效）。
- 迁移唯一索引让 FR-208 脚本可重跑。
- 写入只经 `profile.service.PetSpeciesChangeLog.append(...)`（用户改、运营代改两处调用；迁移脚本是 SQL 直写，字段口径同）。

### 3.6 D-6 身份证卡快照细分类型（迁移 ④ `V20261115_0430`）

```sql
ALTER TABLE id_cards ADD COLUMN pet_species VARCHAR(32);
```
- 可空、**不回填**：旧卡 App 按 `petType` 显示（快照不变，PRD 3.3.3）。不加 CHECK（快照，取值由 DTO 白名单保证）。
- `id_cards.pet_type` 仍存**大类**（卡号物种码、`findNumberSourceCard`、`findReusableKtpPassportNo` 不变）。

### 3.7 迁移清单（预分配，时间戳 20261115_0400–0459 为本线专用段）

| # | 文件 | Story | 内容 |
|---|---|---|---|
| ① | `V20261115_0400__pet_profiles_add_species.sql` | 1.1 | §3.2 三列 + 回填 + 三个 CHECK |
| ② | `V20261115_0410__create_pet_species_changes.sql` | 1.2 | §3.5 |
| ③ | `V20261115_0420__pet_milestones_add_retired_at.sql` | 1.2 | §3.4 |
| ④ | `V20261115_0430__id_cards_add_pet_species.sql` | 1.3 | §3.6 |
| 预留 | `V20261115_0440` ~ `_0459` | 4.2 | 合并对齐若需迁移（如别的线也加了 `ck_pet_profiles_*`） |

整数列一律 INTEGER（本线无整数新列）；`length=1` 不出现。若实现时撞号：只在本段内顺延，回写 sprint-status 头注释。

## 4. 接口决策

### 4.1 D-7 建档 `POST /api/v1/pet-profiles`

- `PetProfileCreateRequest` 末尾追加 `String species`（`@Pattern` = 27 值白名单常量 `PetSpecies.WIRE_PATTERN`，可空）。
- `petType` 由 `@NotBlank` 改为可空（Bean 校验），服务层判：
  - 有 `species`：解析 → `petType` 若也传了且 `≠ species.group()` → 422「宠物类型与细分类型不一致」；以 `species` 为准。
  - 无 `species`（旧版 App）：`petType` 必填（缺 → 422「宠物类型必选」，与现状同文案），`species = PetSpecies.valueOf(petType)`。
- 建档不写 `pet_type_changed_at`、不写修改记录（创建不算修改）。`assignRoster(id, species.group())`；`milestoneTrack = species.group()`。

### 4.2 D-8 编辑 `PATCH /api/v1/pet-profiles/me[?petId=]`

`PetProfileUpdateRequest` 末尾追加 `String species`（可空，同白名单）。处理顺序（在 `requireWritable` 之后、其他字段之前）：

1. `species == null` → **保留原值**（旧版 App 编辑迁移过的兔子不会被改回 OTHER）。
2. 解析失败 → 422；与当前相同 → 不算修改，继续处理其他字段。
3. 对宠物行加悲观锁（`PetProfileRepository.findByIdForUpdate`，同一事务；防两台设备同时改绕过冷却）。
4. `typeChanged = old.pickerType() != new.pickerType()`；`groupChanged = old.group() != new.group()`。
5. `typeChanged` 时校验冷却：`pet_type_changed_at != null && todayWib < changeableFrom` → 抛 `PetSpeciesCooldownException`（§4.3），**整个请求不生效**（其他字段也不存——App 收到后恢复类型与品种、保留表单其余输入，用户可再点保存，UI C7）。
6. 应用：`applySpecies(new)`；`typeChanged` → `pet_type_changed_at = now`，且 `req.breed() == null` 时清空品种（App 正常流程会同时带新品种或空串）；`groupChanged` → `MilestoneTrackSwitcher.switchTo(pet, new.group())`；写修改记录（source = USER）。
7. 「其他」组内换物种（`typeChanged == false`，如 OTHER → CIVET、CIVET → OTTER）：不校验冷却、不写 `pet_type_changed_at`、不清品种，只记修改记录。
8. 提交后发埋点 `pet_type_changed`（§6）。
- 冷却口径：`changeableFrom = WIB日期(pet_type_changed_at) + 90 天`；`todayWib >= changeableFrom` 可改（「第 91 天可改」：11-24 改 → 2027-02-22 可改，与后台 G1 稿一致）。日界用 `ScheduleWindow.WIB`。

### 4.3 D-9 冷却错误

- `profile.domain`（或 `profile.service`）新建 `PetSpeciesCooldownException extends AppException implements ProblemExtensions`：409，`ErrorTypes.PET_SPECIES_COOLDOWN = BASE + "pet-species-cooldown"`，detail「宠物类型暂时不能修改」，扩展成员 `changeableFrom`（`yyyy-MM-dd`，WIB 日期字符串）。写法照 `PetSlotFullException`。
- 旧版 App 不会触发（不传 `species`）。

### 4.4 D-10 返回体新增字段

所有返回体**只追加**，`petType` 保留原语义（大类）：

| 返回体 | 新增 | 用途 |
|---|---|---|
| `PetProfileResponse` | `species`、`speciesChangeableFrom`（`LocalDate`，可空；冷却中才有值，NON_NULL 省略） | 编辑页类型框锁定 / 解锁 |
| `PetListItemResponse` | `species`、`speciesChangeableFrom` | 「我的」头像排、选宠物抽屉（E1 排序与副标题） |
| `VisitorProfileResponse`、`PublicProfilePetResponse`、`RecommendedPetResponse`、`FollowedPetItem`、`FollowListItem`、`ContentPetView`、`GiftCatalogResponse.Recipient`、`PetGiftShowcaseResponse.PetView`、`GiftPetDirectory.GiftPet`（内部，透传）、`BoardingPassDetailResponse`、`OrderDetailView`、`IdCardResponse` / `IdCardDataResponse`（卡：读 `id_cards.pet_species`，可空） | `species` | 各处显示 |
| `AdminUserDetailView.PetRow` | `species`、`speciesChangeableFrom`、`petTypeChangedAt` | 后台 G1 |

- 不改：`PetIdentityView`（兽医端，`species` 字段名已被大类占用，Q-5）、`OwnedPetRef`（性格测试按大类）、`PetPassportSubject`（护照号按大类）、`RecommendationView.ProfileSummary.species`（商城推荐 CAT / DOG / UNIVERSAL，大类逻辑）。
- 新增字段的取值统一 `p.getPetSpecies().name()`；投影 / JPQL 构造（如 `PetView`、`PetRecommendationService` 原生投影）要把列一起查出来，**禁止为取 species 另发 N+1 查询**。

### 4.5 D-11 旧版 App 兼容矩阵（PRD §6）

| 场景 | 旧版 App（≤1.4.2）行为 | 服务端保证 |
|---|---|---|
| 建档 | 只传 `petType` | `species = petType` |
| 编辑（含已迁移的兔子） | 不传 `species` | 保留原值，不触发冷却 |
| 读档案 / 列表 / 访客等 | 只认 `petType` | `petType` 仍是三值大类，兔子显示「Lainnya」 |
| 建身份证卡 | 只传 `petType` | `id_cards.pet_species = petType`（§4.7） |
| 点 `PET_SPECIES` 推送 | `OpsAppPage.fromWire` 返回 null | 不跳转、只打开 App（现有逻辑，不报错） |
| 迁移后的猫（OTHER → CAT） | 显示「Kucing」；里程碑仍是 G 清单（按 code 前缀显示） | 无额外处理 |

### 4.6 D-12 AI 分诊

- `PetProfileQueryService:213` 构造 `TriagePetContext` 时传 `p.getPetSpecies().name()`。
- `GeminiDeveloperApiClient.speciesLabel`：`CAT → 猫`、`DOG → 狗`、`OTHER → 其他伴侣动物`、其余 → `PetSpecies.zhName()`（兔 / 仓鼠 / 麝香猫……）；不认识的值 → 「其他伴侣动物」。systemInstruction 不改。

### 4.7 D-13 身份证建卡

- `CreateIdCardRequest` 末尾追加 `species`（可空，同 27 值白名单）；`PET_TYPES` 白名单不变。
- 服务层：有 `species` → `petType` 若传了且不一致 422，否则以 `species.group()` 作卡快照 `pet_type`；无 `species` → 原逻辑，`pet_species = petType`（可空时为 null）。V2 接口「物种必填」= `species` 或 `petType` 至少一个。
- 卡号物种码 / 编号沿用查询**继续用大类**（`req` 归一后的 `petType`）。

### 4.8 D-14 后台代改（AB-42）

- 权限码 `AdminPermissions.USER_PET_TYPE_CHANGE = "user.pet_type_change"`（UI 稿建议名 `pet.change_type`，按现有 `user.*` 命名改，Q-3）；登记到 `AdminPageCatalog` `users` 页写权限列表；默认不预授（只 SUPER_ADMIN 有，见 Q-3）。
- `POST /admin/users/{userId}/pets/{petId}/species`（HTMX，照 `AdminPetSlotController.grant` 结构：`@PreAuthorize`、HX 分支换片段 + toast、非 HX 分支 flash + redirect），参数 `species`、`reason`（trim 后 1–200 字，空 → 422 `admin.err.petSpecies.reasonRequired`，弹层内显示，G3）。
- 服务 `admin.usermgmt.service.AdminPetSpeciesService.change(...)`：校验宠物属于该用户（否则 404）；`species` 与当前相同 → 422 `admin.err.petSpecies.same`；`applySpecies`；**品种清空**；**不写 / 不改** `pet_type_changed_at`；大类改变 → `MilestoneTrackSwitcher.switchTo`；修改记录 source = ADMIN（操作人、原因）；审计 `AuditActions.PET_SPECIES_CHANGED`（targetType `PET`，targetId 档案 id，summary 「from→to」，**不把原因写进 summary**——原因可能含工单号，留在修改记录表）；埋点 `pet_type_changed`（source = admin）。不受 `PetWriteGuard` 只读限制？→ 受限（只读宠物同样拒，Q-6 默认）。
- 后台下拉：15 个类型 + 「其他」下 12 个物种（`<optgroup>`），共 27 项，文字用 `zhName()` 三语词条。

### 4.9 D-15 跳转页 `PET_SPECIES`（FR-224）

- App `OpsAppPage` 末尾加 `petSpecies('PET_SPECIES')`；`resolveOpsTarget` 返回新动作 `OpsPetSpeciesAction(requiresLogin: true)`（sealed 子类，L0 可测）。
- 执行层（`presentation/open_ops_target.dart`）：拉 `GET /pet-profiles/me/list`；0 只 → push `/profile/create`；1 只 → `currentPetIdProvider.notifier.select(id)` 后 push `/profile/edit`；多只 → `showPetPickerSheet(multiSelect: false)`，说明文案「Pilih hewan yang mau diperbarui jenisnya」，排序 `species == OTHER`（细分类型仍是「其他」，**不含** CIVET 等已细分的）在前、其余按创建顺序（列表接口已按创建序），选中后同 1 只。只读宠物在列表里照常显示（选了进编辑页由 `PetWriteGuard` 表现现有只读态）——见 Q-7。
- 后台跳转说明「5 个固定页」：UI 稿定稿写的是具体数字，本线分支内改为「6」（三语）。**合并后数字须 = `OpsAppPage.values().length`**（届时含 `STARTER_PACK` 为 7），由 Story 4.2 落实。

## 5. App 决策

### 5.1 D-16 清单与匹配（`A/features/profile/domain/pet_species.dart`，新文件；`pet_breeds.dart` 保留文件名、内容改为从新清单导出）

- `enum PetSpeciesCode`（27 值，wire 同后端）+ `PetSpeciesCode? fromWire(String?)`（不认识 → null）。
- `class SpeciesType`（宫格上的 15 项）：`code`、`emoji`、`nameId`、`nameEn`、`breeds`（`List<BreedOption>`）；顺序照 PRD 3.1 表；`OTHER` 项的 `groups` = 12 个 `SpeciesGroup(code, titleId, titleEn, breeds)`（附录 B 顺序），每组末尾自动追加「<titleId> lainnya」项（品种值存 `<titleId>`，如「Musang」）。
- `class BreedOption`：`label`（显示全文，如「Pudel / Poodle」）、`aliases`（按「/」切开的每段 trim）。存库值 = `label` 全文（与现状一致，旧值「Pudel / Poodle」天然命中）。
- 匹配函数 `bool speciesQueryMatches(String query, Iterable<String> candidates)`：`foldDiacritics` + 小写 + 包含；类型匹配 `nameId`、`nameEn`；品种匹配 `label` 与每个 alias；「其他」里命中分组标题 → 整组。**建档 / 编辑 / 身份证三处共用这一个函数**。
- 手填解析 `PetSpeciesCode resolveCustomInOther(String text)`：在「其他」下点「Pakai "xxx"」→ 与某组某项或组名规范化后**完全相等** → 该组物种；否则 `OTHER`（PRD「手填、对不上任何物种的，记 OTHER」）。
- 已选值是否手填：`breed` 不在当前类型（「其他」组为该组）清单里 → 手填（Thai Bangkaew、Weimaraner 等旧值同样走手填显示）。
- 同步守卫测试（Dart，读仓库文件，云端可跑）：
  - `pet_species_backend_sync_test.dart`：解析 `../petgo-backend/src/main/java/com/tailtopia/profile/domain/PetSpecies.java` 的常量名列表 = `PetSpeciesCode` wire 列表（顺序也一致）。
  - `pet_species_export_sql_sync_test.dart`：`scripts/ops/pet-species/export-push-groups.sql` 里的猫 / 狗品种 VALUES 列表 = App 猫 / 狗清单（§6.2）。
  - 清单条数断言：猫 39、狗 43、兔 19、仓鼠 6、鸟 17、鸡 15、鱼 11、蜥蜴 5、龟 5、蜜袋鼯 6、蛇 6、鸭 5、青蛙 7、豚鼠 5；「其他」12 组。

### 5.2 D-17 统一显示函数（`A/features/profile/domain/pet_species_display.dart`，新文件）

```dart
/// 唯一入口：所有显示宠物类型名 / emoji 的地方都只调这里。species 为空或不认识时按 petType（大类）兜底。
PetSpeciesView petSpeciesView(String? species, String? petType);
class PetSpeciesView {
  String emoji;          // 15 项各自 emoji；「其他」组物种与 OTHER 均 🐾
  String label(AppLocalizations l10n);   // 界面语言：Kelinci / Rabbit；「其他」组物种显示「Lainnya / Other」（PRD AC3）
  String cardNameId;     // 卡面固定印尼语（KTP 大写由调用方 toUpperCase）：Kucing / Anjing / Kelinci / … / 其他与「其他」组 → Hewan
  Color tint;            // 头像占位底色：仍按大类（猫浅紫 / 狗浅黄 / 其余薄荷灰，UI F1 「不新增颜色」）
  String pickerWire;     // 宫格上对应的那一项（CIVET → OTHER）
}
```
- 旧函数 `petTypeDisplay`、`petSpeciesLabel`、`petSpeciesEmoji`、`petSpeciesTint` **保留签名**、改为转调 `petSpeciesView`（加可选命名参数 `species`），这样别的线新增的调用点即使没传 `species` 也能编译、只是按大类显示（Story 4.2 再扫一遍补上）。
- `PetSpeciesAvatar` 加可选 `species` 参数。
- 所有调用点（§1.2 第 13 条）改为传 `species`；模型类（`PetProfile`、`PetListItem`、`VisitorProfile`、`FollowedPet`、`FollowListItem`、`ContentPetRef`、`GiftCatalog` 收礼宠物、`GiftShowcase`、`BoardingPass`、`IdCard`、`RecommendedPet`、`PublicProfilePet`、`OrderDetail`、`CelebrationPet` 等）加 `species` 可空字段。
- 新增 l10n 词条：12 个类型名 `petTypeRabbit` … `petTypeGuineaPig`（已有 `petTypeCat/Dog/Other`），12 个分组名 `petSpeciesGroupCivet` …，以及 UI 稿文案表其余各条（§8 文案键表在 Story 3.1 / 3.2 / 3.3 / 3.5 内列全）。

### 5.3 D-18 选择抽屉

- 一个外壳 `SpeciesPickerScaffold`（拖拽条、标题、常驻搜索框 44 高带清除钮、可滚动内容区、键盘弹起整体上移），两个用法：
  - `showPetSpeciesPicker(context, {current})` → 返回 `PetSpeciesCode`：无输入 3 列 × 5 行宫格（格高 80，emoji 30 + 名 13）；有输入切列表（命中段加粗）；无结果空态（浅紫圆底 `search_off` + 「Jenis "{q}" belum ada」+「Pilih Lainnya, lalu cari di daftar ras.」+ 一行「🐾 Lainnya」点了即选 OTHER）。**不提供手填**。
  - `showPetBreedPicker(context, {speciesType, current})` → 返回 `BreedPick(label, species)`：列表 + 底部固定一行（无输入「Tulis sendiri」点了聚焦搜索框；有输入「Pakai "{q}"」，最多 60 字，点了即用）；「其他」时分组列表（组标题大写小字）+ 每组末项「<组> lainnya」；当前已选打勾；已选是手填值 → 搜索框预填、键盘不自动弹、「Pakai」行打勾（B4）。
- `SpeciesField` / `BreedField` 改用它们（删掉 `ListTile` 版与 `AlertDialog` 手填）；身份证建卡删掉 `IdCardSpeciesDropdown / IdCardBreedDropdown`，在「Ubah info」抽屉上再弹同一组件（D1 ~ D4）。

## 6. 运维与数据交付

### 6.1 D-19 FR-208 存量迁移脚本（不进 Flyway）

位置 `scripts/ops/pet-species/`（新目录）：
- `README.md`：执行步骤（备份 → 在 stag 先跑 → 生产只读核对 → 执行 → 核对），**生产执行前须操作者确认**（`confirm-before-prod-infra-change`）。
- `migrate-pet-species.sql`（psql 脚本，一个事务）：
  1. `CREATE TEMP TABLE species_mapping(pet_id BIGINT, pet_name TEXT, current_type TEXT, target_species TEXT, target_breed TEXT, confidence TEXT, reason TEXT)`；`\copy species_mapping FROM :'csv' CSV HEADER`。
  2. 校验：`target_species` 不在 27 值 → 整批 `RAISE EXCEPTION`；`pet_id` 重复 → 报错；只取 `confidence = 'HIGH'`；`pet_name` 与库内名字不一致的行**跳过并列出**（防映射表行错位）。
  3. 只处理当前 `pet_species = 'OTHER'` 的宠物（存量「其他」，51 只误存的猫也在其中，目标为 `CAT`）；已被用户 / 运营改过（有 USER 或 ADMIN 修改记录）的跳过。
  4. `UPDATE pet_profiles SET pet_species = m.target_species, pet_type = <group(target)>, breed = COALESCE(NULLIF(trim(breed), ''), NULLIF(trim(m.target_breed), ''))`——**品种已有的不覆盖**；**不动** `milestone_track`、`pet_type_changed_at`。
  5. `INSERT INTO pet_species_changes (..., source) VALUES (..., 'MIGRATION') ON CONFLICT DO NOTHING`（唯一索引保证可重跑）；`operator_admin_id`、`reason` 为空。
  6. 末尾打印统计（按目标物种计数、跳过原因计数），默认 `ROLLBACK`，传 `-v apply=1` 才 `COMMIT`（干跑优先）。
- 不发通知、不发埋点（迁移量看 `pet_species_changes`，Q-4）。
- 映射表延期（OQ-1）不阻塞上线：脚本与 App 功能无依赖。

### 6.2 D-20 FR-224 推送分组导出

`scripts/ops/pet-species/export-push-groups.sql`（只读查询，迁移脚本 COMMIT 之后执行）：
- 基础人群 = `OpsAudienceSql.BASE_FILTER` 同口径（照抄并注明出处）；只看 `status = 'ACTIVE'` 的宠物（Q-8）。
- A：有宠物 `pet_species = 'OTHER'`；B：不在 A、有宠物存在 `source = 'MIGRATION'` 修改记录；C：不在 A / B、有 CAT / DOG 宠物且 `breed` 为空或 `lower(trim(breed))` 不在新清单（SQL 内 `VALUES` 列表，猫 39 + 狗 43，与 App 清单由 §5.1 守卫测试同步）；D 不导出（后台「按条件 → 没有宠物」）。
- 输出三份 `\copy (...) TO 'group_a.csv'`，每份一列 `user_id`，按 50,000 行切分说明写在 README；末尾自检查询：三组两两交集为 0、三组与「无 ACTIVE 宠物」用户交集为 0。

## 7. 合并最后的规则（G-1 ~ G-6）

- **G-1 只追加**：`PetProfile`、各 DTO、`PetProfileUpdateRequest` / `CreateRequest` 新增字段一律放末尾，便捷构造器保留；不重排、不改别人的注释。
- **G-2 一个函数**：App 类型名 / emoji 只走 `petSpeciesView`；后端大类只走 `PetSpecies.group()`；里程碑 code 只走 `milestoneTrack`。
- **G-3 不碰别人的逻辑**：健康提醒、送礼物种排序、首页推荐分池、运营人群、礼物适用物种、性格测试一律继续读 `pet_type`。
- **G-4 不碰地基**：不改 `ck_notifications_type`、`NotificationType`、`OpsAppPage` 后端枚举顺序。
- **G-5 合并后再扫一遍（Story 4.2）**：在从 `dev_1.5.0` 切出的对齐分支 `feat/1.5.0-pet-species-align` 上做（**不把 dev 反向合并进功能分支**）：① 扫 ①②③ 新增的显示调用点补 `species`；② FR-174 同物种匹配改读 `pet_species`；③ 后台跳转说明数字改为实际页数；④ ① 若新增了按 `getPetType()` 算里程碑 code 的代码，改读 `milestoneTrack`；⑤ 重跑守卫测试与全量 L0。
- **G-6 迁移号**：只用 `20261115_04xx`；对齐分支若需迁移用 `_0440` 起。

## 8. 埋点（PRD §4）

| 事件 | 端 | 触发 | 属性 |
|---|---|---|---|
| `pet_type_selected` | App | 类型抽屉选中 | `species`、`scene`（create / edit / id_card）、`via_search` |
| `pet_breed_selected` | App | 品种抽屉选中 | `species`、`scene`、`is_custom`、`via_search` |
| `pet_breed_search_no_result` | App | 品种搜索结果为空（同一次打开抽屉、同一 query 只报一次，输入停 800ms 后判定） | `species`、`query`（≤60 字） |
| `pet_type_changed` | 后端 | 用户改 / 运营代改提交后（AFTER_COMMIT） | `from_species`、`to_species`、`group_changed`、`source`（user / admin） |

App 事件名写字面量，进 `analytics.dart` 白名单；`pet_` 前缀已登记。后端 `distinctId` 用现有 `AnalyticsDistinctId`（主人）。`query` 是用户输入的品种词，不是 PII，但截断到 60 字。

## 9. 待拍板（汇总，见 epics 末尾，均已给默认做法、不阻塞）

Q-1 旧清单徽章展示位 · Q-2 冷却拒绝时其他字段是否一并保存 · Q-3 代改权限码名与预授 · Q-4 迁移是否发埋点 · Q-5 兽医端是否显示细分类型 · Q-6 只读宠物能否被代改 · Q-7 推送落地是否排除只读宠物 · Q-8 导出只算 ACTIVE 宠物 · Q-9「其他」里手填恰好等于某组选项时记什么 · Q-10 后台跳转说明页数（本线 6，合并后 7）。
