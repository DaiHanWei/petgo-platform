# Story 3.3: 佩戴角色小标与 Diary 测试条目

Status: ready-for-dev

## Story

As a 解锁过结果的用户,
I want 把性格标签挂在宠物档案上、在 Diary 里留下这次测试,
so that 来看主页的人都能看到，我也能回顾。

## Acceptance Criteria

**AC1 — 佩戴表与首次自动佩戴** `[L0]` `[L1]`
1. 新迁移（时间戳号，如 `V20260929_HHmm__init_tailsonality_badges.sql`）：`tailsonality_badges(pet_profile_id BIGINT PRIMARY KEY REFERENCES pet_profiles(id) ON DELETE CASCADE, result_id BIGINT NOT NULL REFERENCES tailsonality_results(id) ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now())` + 表 / 列 COMMENT（「一宠一行；只能指向已解锁结果，由服务层保证」）。实体 `com.tailtopia.tailsonality.domain.TailsonalityBadge`（NEW）。
2. 在 3-2 的 `TailsonalityKeepsakeGranter.grant` 内、**仅当结果为 `GRANTED` 时**，同一事务执行 `INSERT INTO tailsonality_badges(pet_profile_id, result_id) SELECT pet_profile_id, id FROM tailsonality_results WHERE id = :refId ON CONFLICT (pet_profile_id) DO NOTHING` —— 首次解锁自动佩戴，此后再解锁**不替换**；并发两笔同宠物解锁不撞主键。仍不得抛异常（包在 grant 既有 try 内）。
3. 集成测试：首次解锁 A → 佩戴 A；再解锁 B → 仍佩戴 A；两笔并发到账（两个不同结果）→ 恰一行、无异常；`ALREADY_UNLOCKED` / `REF_MISSING` 路径不写佩戴。

**AC2 — 切换佩戴接口** `[L1]`
`PUT /api/v1/pet-profiles/me/tailsonality/badge`，body `{ "resultToken": "…" }`，需 `USER`：
1. 结果非本人 / 不存在 → 404；结果未解锁 → 422，新 type `tailsonality-badge-locked`（`ErrorTypes` + `AppException` 工厂）；
2. 通过 → `INSERT … ON CONFLICT (pet_profile_id) DO UPDATE SET result_id = EXCLUDED.result_id, updated_at = now()`；返回 `204`。
3. `SecurityConfig` 精确 matcher `PUT /api/v1/pet-profiles/me/tailsonality/badge` → `hasRole("USER")`；限流 `rl:tailsonality:badge:{userId}` 20 次 / 分钟。
4. 本版本**不提供卸下**（UI 只有「Pakai ini」切换；见 Dev Notes「未决」）。
5. 2-1 的 `TailsonalityResultResponse`（列表与单条）追加 `equipped: boolean`（= 佩戴行 `result_id` 等于本结果 id）；四处同步：后端 record、`TailsonalityResultResponseContractTest` 的 `FULL_FIELDS`、App `TailsonalityResult.fromJson`、`test/tailsonality/tailsonality_result_wire_contract_test.dart` fixture。

**AC3 — 结果列表页行内切换** `[L2]` `[L0]`
在 2-6 交付的「Riwayat Tailsonality」页（`tailsonality_results_page.dart`，行组件 `widgets/ts_result_row.dart`）每行右侧加控件：
1. 已解锁 + 已佩戴 →「Dipakai」（选中态胶囊，不可点）；已解锁 + 未佩戴 →「Pakai ini」（描边按钮，点击调 AC2，成功后刷新列表：原佩戴行变「Pakai ini」、本行变「Dipakai」）；未解锁 →「Belum dibuka」置灰、**不可点**（2-6 已有文字，本 story 保证无点击响应、无水波纹）。
2. 请求中按钮 loading 防重复点；失败 toast「Gagal pasang badge, coba lagi」并回滚为原状态。
3. 控件热区 ≥44×44，与「点行进结果页」的整行热区**不重叠**（控件区吃掉点击，不冒泡到行）。
4. App 埋点 `tailsonality_badge_equipped`，属性 `result_index`，**仅在 PUT 成功后**报。
5. widget 测试：三态渲染；点「Pakai ini」→ 调接口一次、切换后仅一行「Dipakai」；未解锁行点击无回调。

**AC4 — 档案与公开主页下发小标** `[L1]` `[L0]`
统一规则（一个方法、两处复用）：tailsonality 包新建 `TailsonalityBadgeQuery.badgeOf(long petProfileId) → Optional<String>`：佩戴行存在**且**所指结果 `unlocked_at IS NOT NULL` → 返回该结果 `type_code` 列（2-1 定义为 `CHAR(4)`，即 **4 字母**，不含能量后缀；对应 DTO 的 `letters`），否则 empty。
1. 本人宠物档案 `GET /api/v1/pet-profiles/me`：`PetProfileResponse` 末尾追加 `String tailsonalityBadge`（null 时经 NON_NULL 省略）；`PetProfileResponse.from(PetProfile)` 保留（创建路径 `ProfileService` L101 仍用它、恒 null），新增 `from(PetProfile, String tailsonalityBadge)`，`ProfileService.getMyProfile`（L189 附近）改调新重载。
2. 公开主页宠物卡 `GET /api/v1/users/{userId}/pet`：`PublicProfilePetResponse` 末尾追加 `String tailsonalityBadge`，`of(p, diaryCount)` 改为 `of(p, diaryCount, badge)`；`PublicProfilePetController` 注入 `TailsonalityBadgeQuery`。拉黑 / 注销 / 无宠物的 204 分支**不变**。
3. 字段名与实现标识符**不得含** milestone / passport / checkin / share / follow / visitor / bio（FR-118.7 反向清单）；`InAppVisitorEntryTest` 的禁词组件扫描（`token` / `serial` / `share*` / `health` …）继续通过——**只下发 4 字母，不下发结果 token**。
4. 访客视图（`VisitorProfileResponse` / `VisitorProjectionService`）**不加**该字段（AD-3 只点名两处）。
5. 测试：未解锁 → null；解锁并佩戴 → `"ENTJ"`；佩戴行指向的结果被删（删档）→ 无佩戴行 → null；公开端点游客可见同一值。

**AC5 — App 显示小标** `[L2]` `[L0]`
1. 公开主页 `public_profile_page.dart` `_PetSection`（L661-759）：宠物名那一行右侧（名字 `Text` 之后，`Flexible` 包名字防挤）加小标胶囊，`ValueKey('profilePetTailsonality')`，内容仅 4 字母、等宽数字字体特性（`FontFeature.tabularFigures()` 不影响字母，但沿用 NFR-8 统一写法）；`badge == null` 不渲染任何占位。更新类注释 L659-660「Tailsonality 角色小标位是天然空状态」为新规则。
2. `public_profile_pet_repository.dart` 的 `PublicProfilePet` 加 `String? tailsonalityBadge`（`fromJson` 容忍缺键）。
3. 本人档案：`PetProfile`（`features/profile/domain/pet_profile.dart`）加 `String? tailsonalityBadge`；`PetHeaderInfo`（`domain/pet_header_info.dart`）加同名可空字段，作者态由 `PetProfile` 产出时带上、访客态恒 null；`PetInfoCard`（`widgets/pet_info_card.dart`）在名字行显示同款胶囊（抽一个共享 widget `TailsonalityBadgeChip`，放 `lib/features/tailsonality/presentation/widgets/`，两处共用）。
4. 测试更新：`test/user_profile/public_profile_pet_test.dart` L151-157「卡上没有 Tailsonality 占位」改为两例——无小标 → `profilePetTailsonality` findsNothing；有小标 `ENTJ` → findsOneWidget 且显示 `ENTJ`；`find.textContaining('Tailsonality')` 仍 findsNothing（界面只显 4 字母）。`test/user_profile/public_profile_posts_test.dart` L277-313 反向清单**原样通过**（不许删词）。

**AC6 — Diary Tailsonality 条目** `[L1]` `[L0]`
1. 前后端 `TimelineItemType` **末尾同名追加** `TAILSONALITY_BANNER`（后端 `profile/dto/TimelineItemType.java`；App `features/profile/domain/timeline_item.dart` L17-34，wire `'TAILSONALITY_BANNER'`）；查询时拼装、**不落库**。
2. `TimelineItemResponse` 追加字段（照 1-6 追加 `checkinPlace` 的方式：record 末尾追加、`withDecorationTags` 透传；并更新 `TimelineItemResponseContractTest` 的 `itemTypeVocabulary` / `ALL_FIELDS` / 工厂样本）：`tailsonalityResultToken`、`tailsonalityCode`（完整代号 `ENTJ-H` = `type_code` + `-` + `energy`）、`tailsonalityTestedOn`（`created_at` 的 UTC 日期，仅显示用）；新工厂 `tailsonalityBanner(...)`，`kind` 新常量 `TAILSONALITY`。`withDecorationTags` 等复制构造同步。
3. `TimelineService.fetchMerged` 增加源⑥（照源④里程碑的「按 `createdAtUpperBound` 取 fetch 条 + 满批设地板锚点」写法）：该宠物 `unlocked_at IS NOT NULL` 的结果，按 `unlocked_at` 倒序；**`date` 与 `eventDate`（有效日期）都取 `unlocked_at`**（`eventDate` = `unlocked_at` 的 UTC 日期）——与其它源的「UTC 日期」口径一致。多次解锁多条并存。
4. **能力闸**：仅当请求 `supports` 含 `tailsonality` 时下发（`supports` 由 1-6 交付于 `ProfileApiController` 的 `/me/timeline`、`/me/day`、`/me/calendar` 三端点，取值小写 wire；本 story 在三处都接上 `tailsonality`，日历按 1-6 的口径决定是否因本类条目产生格子）；未声明 → 三处都不下发（有测试，照 1-6「带与不带 supports 对比基线」的写法）。
5. 访客态（`VisitorProjectionService` 的访客时间线）**不下发**，有测试钉住；1-6 的源码守卫测试（`VisitorProjectionService.java` 不出现 `supports` 等）继续通过，并追加禁词 `TAILSONALITY_BANNER`。
6. 集成测试：未解锁结果不出条目；解锁后出一条且落在解锁日；解锁两次两条；老 App（不带 `supports`）响应与改动前逐字一致；跨页游标不漏不重（同日多条 + 翻页边界）。

**AC7 — App 条目样式与跳转** `[L2]` `[L0]`
1. `timeline_item_tile.dart` 的 `switch (item.resolvedType)`（L58-66）加 `TimelineItemType.tailsonalityBanner` 分支：复用类③ banner 结构 + 左侧 34px 日期槽（`_dateGutter`）+ **专属底色**（新增 `AppColors` token，如 `tailsonalityBannerBg`，浅紫系，与里程碑 banner 区分）；内容 = 角色插画缩略（按 4 字母取 2-4 已接入的包内角色图，缺失回落占位）+「{code} · {角色名}」+ 测试日期（`formatDayMonth` 类既有日期格式化）。
2. 组件**不持有跳转**（沿用本组件既有约定）：`growth_archive_page.dart` 的 `_realTapFor` 穷举 switch（1-6 已为打卡加过分支）加本类分支 → `context.push(TailsonalityRoutes.result(token))`，并先 `_reportItemTap(item)`。游客示例时间线与真实时间线复用同一组件（本 story 不新增示例数据）。
3. `timeline_repository.dart` 的 `kTimelineSupports`（1-6 建立，仅作者态请求携带）加 `'tailsonality'`；访客态请求仍不带。
4. `TimelineItem` 解析新字段；未知类型兜底逻辑（L139-143 回落照片卡）保持不变。
5. widget 测试：新类型渲染代号与角色名、带日期槽；点击回调带 token。

**AC8 — 删档 / 注销** `[L1]`
`tailsonality_badges` 在 `ProfileDeletionService.deleteByUserId` 内（`petProfiles.delete(pet)` 之前）经 2-1 的 `TailsonalityDeletionService.deleteForPet(petId)` **先于** `tailsonality_results` 物理删除（在该方法开头加一行删佩戴）；配级联测试：删档后两表无该宠物残留、`keepsake_purchases` 行仍在且 `pet_profile_id` 为空（3-1 口径）、同一账号重建宠物后公开卡小标为 null。

## Tasks / Subtasks

- [ ] **T0 核对前置**：2-1（结果表 / 实体 / 仓库 / 删档方法）、2-6（列表页 widget）、3-2（granter）、1-6（`supports` 解析、`TimelineItemResponse` 扩字段方式、三处接口签名）实际代码
- [ ] **T1 后端：表 + 自动佩戴**（AC1）
- [ ] **T2 后端：切换接口 + DTO equipped**（AC2）
- [ ] **T3 后端：小标下发**（AC4）
  - [ ] `TailsonalityBadgeQuery`；`PetProfileResponse` 新重载；`ProfileService.getMyProfile`
  - [ ] `PublicProfilePetResponse` / `PublicProfilePetController`；更新 `InAppVisitorEntryTest` 的构造（多一个依赖）
- [ ] **T4 后端：时间线源⑥**（AC6）
  - [ ] `TimelineItemType` 追加；`TimelineItemResponse` 字段 + 工厂；`TimelineService` 源⑥ + 三处能力闸
  - [ ] 访客态不下发测试
- [ ] **T5 后端：删档**（AC8）
- [ ] **T6 App：列表页切换**（AC3）
- [ ] **T7 App：小标显示**（AC5）+ 两个测试文件更新
- [ ] **T8 App：时间线条目**（AC7）
- [ ] **T9 l10n**：en + id（见下表）；`flutter gen-l10n`
- [ ] **T10 联调**（L1 本地 / L2 模拟器看档案页、公开主页、Diary）

## Dev Notes

⚠️ 前置 story 尚未实现：开工前先对照其实际代码核对本文件引用的类名/接口/字段，有出入先改本文件。（依赖 1-6、2-1、2-2、2-4、2-6、3-2。）

### 必读：会被本 story 改到的现有代码

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `petgo-backend/.../profile/dto/PetProfileResponse.java` | 13 组件 record + `from(PetProfile)`；两处调用 `ProfileService` L101（创建）/ L189（getMyProfile） | 追加 `tailsonalityBadge` + 新重载 | 旧 `from` 签名、既有字段顺序（App 按名解析，无影响） |
| `.../profile/visitor/PublicProfilePetResponse.java` | 6 组件，`of(p, diaryCount)` 包内静态；类注释 L16-17「没有 Tailsonality 角色小标……不做占位」 | 追加字段、改 `of`、改注释 | **无 cardToken / intro / sex**（白名单注释 L6-14） |
| `.../profile/visitor/PublicProfilePetController.java` L62-97 | 拉黑 403 / 反向拉黑 204 / 未激活 204 / 无宠物 204 / 200 | 注入 badge 查询，200 分支带上 | 三个 204 分支与顺序、游客可读 |
| `.../profile/dto/TimelineItemType.java` | 5 值，「枚举名即线格式」 | 末尾追加 | 既有 5 值 |
| `.../profile/dto/TimelineItemResponse.java` | 17 组件 record + 各工厂 + `withDecorationTags` | 追加字段 + 工厂 | 既有工厂签名（1-6 同样在改，合并时注意） |
| `.../profile/service/TimelineService.java` `fetchMerged` L198-306 | 五源 → `TimelineClassifier.classify` → 排序；`getCalendarMonth` L395、`getDayDetail` L467 | 源⑥（能力闸内）；两处同接 | 各源「同一锚点取数 → 归并 → 统一截断」、同日不跨页 |
| `.../profile/service/ProfileDeletionService.java` L56-111 | 删健康 / 里程碑 / 分享 → 卡打标 → `detachPet` → `petProfiles.delete` | 经 tailsonality 删档方法先删 badges 再删 results（2-1 已接入点） | 既有顺序与返回值 |
| `petgo_app/lib/features/user_profile/presentation/public_profile_page.dart` `_PetSection` L650-759 | 头像 + 名字 + meta +「Lihat →」；注释「小标位是天然空状态」 | 名字行加小标 | 登录门控 `requireLogin`、`pet_card_tapped` 埋点、`profilePetCard` / `profilePetMeta` key |
| `petgo_app/lib/features/user_profile/data/public_profile_pet_repository.dart` | `PublicProfilePet(petId, name, avatarUrl, petType, birthday, diaryCount)` | 加可空字段 | 204 → null 语义 |
| `petgo_app/lib/features/profile/domain/pet_profile.dart` / `pet_header_info.dart` / `presentation/widgets/pet_info_card.dart` | 作者态 / 访客态都产出 `PetHeaderInfo` 给 `PetInfoCard` | 加字段、名字行小标 | 访客态字段少的设计（不编假值） |
| `petgo_app/lib/features/profile/domain/timeline_item.dart` L17-49 | 5 值 + `parse` 未知返回 null | 追加 `tailsonalityBanner` | 未知类型兜底 |
| `petgo_app/lib/features/profile/presentation/widgets/timeline_item_tile.dart` L58-66 | `switch` 五类 | 加一类 | 组件不持有跳转 |
| `petgo_app/test/user_profile/public_profile_pet_test.dart` L151-157 | findsNothing | 按新规则改（不删） | — |
| `petgo_app/test/user_profile/public_profile_posts_test.dart` L277-313 | 源码禁词 | 不改，必须继续通过 | 禁词表一字不动 |

### 可直接复用

| 要做的事 | 用这个 | 位置 |
|---|---|---|
| 源的锚点取数与地板 | 源④ 里程碑写法 | `TimelineService.java` L253-262 |
| banner 条目样式 | `_milestoneBanner` + `_dateGutter` | `timeline_item_tile.dart` L90 / L246 |
| 角色插画 / 名称寻址 | 2-2 内容表、2-4 角色图映射 | `lib/features/tailsonality/` |
| 能力参数 | 1-6 的 `supports` 解析与 App 常量 | 以 1-6 实现为准 |
| NON_NULL 省略 | 全局 Jackson 配置（`PetProfileResponse` 注释） | — |

### 关键设计点

- **小标只在「佩戴 + 已解锁」时出现**：修订 FR-118 原「测过即出现」；未解锁用户看不到任何小标或占位。
- **自动佩戴只发生在 grant 的 GRANTED 分支**：放在同一事务里，避免「解锁了但没佩戴」的中间态；`ON CONFLICT DO NOTHING` 同时解决并发与「之后不替换」。
- **有效日期 = `unlocked_at`**（AD-9）：测试日期只是显示字段——付费那天才进 Diary，不会把条目插回过去某天打乱已读。
- **标识符避词**：`tailsonalityBadge`、`TailsonalityBadgeChip` 均不含禁词；**不要**起 `shareBadge` / `bioTag` 之类的名字。
- **未决（不阻塞）**：PRD / 内容设计说「可选择是否佩戴」，UI 稿与 epics 只有切换、没有卸下。本 story 按 UI 与 epics 只做切换；若产品要「卸下」，另开小改（接口加 DELETE、列表「Dipakai」可点）。

### l10n 新 key（en + id）

| key | id | en |
|---|---|---|
| `tailsonalityBadgeEquipped` | Dipakai | Wearing |
| `tailsonalityBadgeEquip` | Pakai ini | Wear this |
| `tailsonalityBadgeEquipFailed` | Gagal pasang badge, coba lagi | Couldn't set the badge, try again |
| `timelineTailsonalityTestedOn` | Dites {date} | Tested {date} |

「Belum dibuka」由 2-6 提供，复用。

### 验证层级

L0：AC1 实体、AC3 / AC5 / AC7 widget 测试、两个公开主页测试 · L1：AC1 自动佩戴与并发、AC2、AC4、AC6、AC8 · L2：档案页 / 公开主页 / 列表页 / Diary 视觉

### Project Structure Notes

- 后端：佩戴与 badge 查询在 `com.tailtopia.tailsonality`；profile 包只调用 `TailsonalityBadgeQuery`（只读），时间线源⑥经 tailsonality 包提供的只读视图取数（照里程碑的 `MilestoneTimelineView` 做法），**profile 不直接注入 tailsonality 的 Repository**。注意避免构造器循环：`TailsonalityBadgeQuery` 只依赖自己的仓库。
- App：`TailsonalityBadgeChip` 放 `lib/features/tailsonality/presentation/widgets/`，`user_profile` 与 `profile` 两处引用。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.2/epics-v1.3.2-batch-a.md#Story 3.3]
- [Source: _bmad-output/planning-artifacts/v1.3.2/architecture-v1.3.2-batch-a-delta.md#AD-3, AD-9, AD-17]
- [Source: _bmad-output/planning-artifacts/v1.3.2/tailsonality-内容设计.md#2.4, 2.7]
- [Source: _bmad-output/planning-artifacts/v1.3.2/PRD-v1.3.2-batch-a.md#3.2 角色小标佩戴 / Diary 联动]
- [Source: _bmad-output/planning-artifacts/v1.3.2/ui-v1.3.2-batch-a.html#A17, A18, A21, A22]
- [Source: _bmad-output/planning-artifacts/v1.3.2/代码核对报告-batch-a.md#0, #1 公开主页小标 / Diary 时间线]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created

### File List
