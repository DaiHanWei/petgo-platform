# Story 3.3: 佩戴角色小标与 Diary 测试条目

Status: review

## Story

As a 解锁过结果的用户,
I want 把性格标签挂在宠物档案上、在 Diary 里留下这次测试,
so that 来看主页的人都能看到，我也能回顾。

## Acceptance Criteria

**AC1 — 佩戴表与首次自动佩戴** `[L0]` `[L1]`
1. 新迁移（时间戳号；实际 `V20260930_2315__init_tailsonality_badges.sql`）：`tailsonality_badges(pet_profile_id BIGINT PRIMARY KEY REFERENCES pet_profiles(id) ON DELETE CASCADE, result_id BIGINT NOT NULL REFERENCES tailsonality_results(id) ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now())` + 表 / 列 COMMENT（「一宠一行；只能指向已解锁结果，由服务层保证」）。实体 `com.tailtopia.tailsonality.domain.TailsonalityBadge`（NEW）。
2. 在 3-2 的 `TailsonalityKeepsakeGranter.grant` 内、**仅当结果为 `GRANTED` 时**，同一事务执行 `INSERT INTO tailsonality_badges(pet_profile_id, result_id) SELECT pet_profile_id, id FROM tailsonality_results WHERE id = :refId AND (SELECT count(*) FROM tailsonality_results r WHERE r.pet_profile_id = tailsonality_results.pet_profile_id AND r.unlocked_at IS NOT NULL) = 1 ON CONFLICT (pet_profile_id) DO NOTHING` —— 仅该宠物**第一次**解锁时自动佩戴（D-16：卸下后再解锁不自动戴回），此后再解锁**不替换**；并发两笔同宠物解锁不撞主键。仍不得抛异常（包在 grant 既有 try 内）。
3. 集成测试：首次解锁 A → 佩戴 A；再解锁 B → 仍佩戴 A；两笔并发到账（两个不同结果）→ 恰一行、无异常；`ALREADY_UNLOCKED` / `REF_MISSING` 路径不写佩戴。

**AC2 — 切换佩戴接口** `[L1]`
`PUT /api/v1/pet-profiles/me/tailsonality/badge`，body `{ "resultToken": "…" }`，需 `USER`：
1. 结果非本人 / 不存在 → 404；结果未解锁 → 422，新 type `tailsonality-badge-locked`（`ErrorTypes` + `AppException` 工厂）；
2. 通过 → `INSERT … ON CONFLICT (pet_profile_id) DO UPDATE SET result_id = EXCLUDED.result_id, updated_at = now()`；返回 `204`。
3. `SecurityConfig` 精确 matcher `PUT /api/v1/pet-profiles/me/tailsonality/badge` → `hasRole("USER")`；限流 `rl:tailsonality:badge:{userId}` 20 次 / 分钟。
4. **可卸下**（2026-09-29 决策 D-16）：`DELETE /api/v1/pet-profiles/me/tailsonality/badge`（`SecurityConfig` 精确 matcher `hasRole("USER")`，幂等：无佩戴也 204）；结果列表中正在佩戴的行「Dipakai」可点 → 确认弹窗「Lepas badge? / Badge {pet} nggak akan tampil di profil.」+「Batal」「Lepas」→ 卸下后所有行显示「Pakai ini」，宠物档案与公开主页不再显示小标。「首次解锁自动佩戴」的判据改为：**本次解锁后该宠物的已解锁结果恰好 1 条**（即这是它第一次解锁）才自动佩戴；因此用户卸下后再解锁新结果，**不会**被自动戴回。
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
3. `TimelineService.fetchMerged` 增加源⑦（1-6 已占用源⑥ = 场所打卡）（照源④里程碑的「按 `createdAtUpperBound` 取 fetch 条 + 满批设地板锚点」写法）：该宠物 `unlocked_at IS NOT NULL` 的结果，按 `unlocked_at` 倒序；**`date` 与 `eventDate`（有效日期）都取 `unlocked_at`**（`eventDate` = `unlocked_at` 的 UTC 日期）——与其它源的「UTC 日期」口径一致。多次解锁多条并存。
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

- [x] **T0 核对前置**：2-1（结果表 / 实体 / 仓库 / 删档方法）、2-6（列表页 widget）、3-2（granter）、1-6（`supports` 解析、`TimelineItemResponse` 扩字段方式、三处接口签名）实际代码
- [x] **T1 后端：表 + 自动佩戴**（AC1）
- [x] **T2 后端：切换接口 + DTO equipped**（AC2）
- [x] **T3 后端：小标下发**（AC4）
  - [x] `TailsonalityBadgeQuery`；`PetProfileResponse` 新重载；`ProfileService.getMyProfile`
  - [x] `PublicProfilePetResponse` / `PublicProfilePetController`；更新 `InAppVisitorEntryTest` 的构造（多一个依赖）
- [x] **T4 后端：时间线源⑥**（AC6）
  - [x] `TimelineItemType` 追加；`TimelineItemResponse` 字段 + 工厂；`TimelineService` 源⑥ + 三处能力闸
  - [x] 访客态不下发测试
- [x] **T5 后端：删档**（AC8）
- [x] **T6 App：列表页切换**（AC3）
- [x] **T7 App：小标显示**（AC5）+ 两个测试文件更新
- [x] **T8 App：时间线条目**（AC7）
- [x] **T9 l10n**：en + id（见下表）；`flutter gen-l10n`
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
- **卸下（D-16 已定）**：见 AC 第 4 条。l10n 追加 `tailsonalityBadgeRemoveTitle`「Lepas badge? / Remove badge?」、`tailsonalityBadgeRemoveBody`「Badge {pet} nggak akan tampil di profil. / {pet}'s badge won't show on the profile.」、`tailsonalityBadgeRemoveConfirm`「Lepas / Remove」。

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

Claude Code 云端 session（headless，环境 tailtopia-L0）

### Debug Log References

- 后端 L0：**2836 例，0 失败**；新增 L1 类 `tailsonality/TailsonalityBadgeIntegrationTest` 进排除清单。
- App L0：`flutter analyze` 无问题；`flutter test` **2553 例全绿**。
- `check-flyway-versions.sh origin/main` → OK（新迁移 `V20260930_2315__init_tailsonality_badges.sql`）。

### Completion Notes List

- **T0 核对（与本文件出入，已改本文件 / 按实际代码）**：
  - 1-6 已把时间线「源⑥」用给场所打卡，本 story 的 Tailsonality 为**源⑦**（AC6.3 已改）；能力闸沿用 1-6 的 `TimelineCapabilities`（枚举追加 `TAILSONALITY("tailsonality")` + `tailsonality()`），`ProfileApiController` 三端点已是通用解析，无需改。
  - `SecurityConfig` 已由 2-1 覆盖 `/api/v1/pet-profiles/me/tailsonality/**` → USER，PUT / DELETE `…/badge` 不再单加 matcher（AC2.3 / AC2.4 的 matcher 要求已满足）。
  - 日历「按 1-6 的口径决定是否因本类条目产生格子」：照打卡维加 `DayCell.hasTailsonality`（第八维，未声明能力时 null 省略；只有解锁的日子新建格子），App 日历在「只有打卡」之后加性格图标标记 `kTailsonalityCalendarIcon`。
  - 时间线源经 tailsonality 包只读口 `TailsonalityTimelineQuery`（照 `PlaceCheckinTimelineQuery` 的 JDBC 写法），profile 不注入 tailsonality 仓库；`TailsonalityBadgeQuery` 只依赖自己的仓库，无构造器循环。
  - 编辑档案（PATCH）响应也带小标（`ProfileService.update` 同改）：App 用编辑响应覆盖本地档案，不带会闪掉。
- **L1/L2 待本地验收**：
  - `TailsonalityBadgeIntegrationTest`（真库 + Redis）：首次解锁自动佩戴 / 再解锁不替换 / 切换；未解锁 422 `tailsonality-badge-locked`；卸下幂等且再解锁不自动戴回（D-16）；两笔并发发放（不同结果）→ 至多一行、无异常；本人档案 / 公开卡小标（游客可见）；Diary 条目只在 `supports=tailsonality` 时下发；删档后结果与佩戴无残留、`keepsake_purchases.pet_profile_id` 置空、重建宠物小标为 null。
  - 🔴 同 3-1 / 3-2：真实 Spring 上下文要等 3.4 / 3.5 的发放口落地才能起（IT 用 `@MockitoBean` 顶掉注册表）。
  - 迁移真跑 + `ddl-auto=validate`（`tailsonality_badges` 实体）。
  - L2：列表页三态控件 / 卸下弹窗、档案页与公开主页小标胶囊、Diary 条目与日历标记视觉。
- **偏差 / 取舍**：
  - AC3.1 写「Dipakai 不可点」，与同 AC 组的 AC2.4（D-16，2026-09-29 定）「Dipakai 可点 → 确认卸下」冲突：按决策日志优先，**Dipakai 可点并弹确认**。
  - 未解锁行不放控件：行内已有 2-6 的「Belum dibuka」灰字（本身不可点、无水波纹）；整行点击进结果页是 2-6 的既有行为，保留。
  - `TsResultRow` 重排：行尾控件移到点击区之外（按下缩放只作用于左侧点击区），保证控件吃掉点击、不冒泡到行（AC3.3）。
  - 并发自动佩戴：两笔并发时各自看不到对方未提交的解锁，可能都判「恰好 1 条」→ `ON CONFLICT DO NOTHING` 保证只落一行（先到者）；也可能都判不到 1 条（两者都提交后已解锁 2 条）→ 0 行。IT 断言「至多一行、无异常」，0 行是极端并发下的可接受结果（用户可手动佩戴）。**待确认**。
  - 自动佩戴 SQL 与解锁在同一保存点：自动佩戴失败会连带解锁一起回滚 → 发放记 REF_MISSING（QRIS 到账交人工 / PawCoin 整笔回滚重试）。`ON CONFLICT` 已排除主键冲突，实际只剩 DB 故障。
  - l10n 另加卸下三 key（本文件关键设计点列出）：`tailsonalityBadgeRemoveTitle` / `Body` / `Confirm`；「Batal」复用 `commonCancel`。
  - 埋点后缀表加 `_equipped`（`v112_events_test`，带语义注释）。
- **复审（code-review）**：1 条，已修：首次解锁（服务端自动佩戴）后 App 未刷新 `petProfileProvider`（全局 keep-alive），档案卡一直不显示小标 → 解锁成功路径加 invalidate，补测试断言。
- 被按设计打破的既有测试（已更新断言、未删）：`public_profile_pet_test`「卡上没有 Tailsonality 占位」→ 两例（无小标 findsNothing / 有小标 ENTJ findsOneWidget 且仍无「Tailsonality」字样）；`timeline_five_class_render_test`（游客示例不含 Tailsonality 条目）；`diary_guest_state_test` 与后端 `TimelineItemResponseContractTest` 的词表末尾追加；`timeline_place_checkin_test` 的 `kTimelineSupports`；`TailsonalityResultResponseContractTest` / App wire 契约加 `equipped`；`VisitorNoPlaceCheckinGuardTest` 追加禁词（`TAILSONALITY_BANNER` / `TailsonalityTimelineQuery` / `tailsonalityResultToken`）；`public_profile_posts_test` 反向清单**原样通过**。构造器变更同步：`ProfileServiceTest`、`InAppVisitorEntryTest`、`BlockedViewerProfileTest`、四个 `TimelineService` 单测、`ProfileApiControllerTest`、`TailsonalityOwnerTypeServiceTest`、`TailsonalityResultServiceTest`。
- **待确认**：① Dipakai 可点卸下（按 D-16）；② 极端并发下首次自动佩戴可能 0 行；③ 日历新增 `hasTailsonality` 维与性格图标标记。

### File List

后端（新增）
- `petgo-backend/src/main/resources/db/migration/V20260930_2315__init_tailsonality_badges.sql`
- `tailsonality/domain/TailsonalityBadge.java`、`repository/TailsonalityBadgeRepository.java`、`dto/BadgeEquipRequest.java`、`dto/TailsonalityTimelineView.java`
- `tailsonality/service/TailsonalityBadgeQuery.java`、`TailsonalityBadgeService.java`、`TailsonalityTimelineQuery.java`
- 测试：`tailsonality/service/TailsonalityBadgeServiceTest`、`profile/service/TimelineTailsonalityTest`、`profile/visitor/PublicProfilePetBadgeTest`、`tailsonality/TailsonalityBadgeIntegrationTest`（L1）

后端（修改）
- `tailsonality/service/TailsonalityKeepsakeGranter.java`（GRANTED 分支自动佩戴）、`TailsonalityDeletionService.java`、`TailsonalityResultService.java`、`dto/TailsonalityResultResponse.java`（`equipped`）、`web/TailsonalityController.java`（PUT / DELETE badge）
- `shared/error/ErrorTypes.java`、`AppException.java`（`tailsonality-badge-locked`）
- `profile/dto/PetProfileResponse.java`、`profile/service/ProfileService.java`、`profile/visitor/PublicProfilePetResponse.java`、`PublicProfilePetController.java`
- `profile/dto/TimelineItemType.java`、`TimelineItemResponse.java`、`CalendarMonthResponse.java`、`profile/service/TimelineCapabilities.java`、`TimelineService.java`
- 测试：见上「构造器变更同步」与契约 / 守卫测试

App（新增）
- `lib/features/tailsonality/presentation/widgets/tailsonality_badge_chip.dart`
- 测试：`test/tailsonality/tailsonality_badge_test.dart`

App（修改）
- `lib/features/tailsonality/domain/tailsonality_result.dart`、`data/tailsonality_repository.dart`、`presentation/tailsonality_results_page.dart`、`widgets/ts_result_row.dart`、`presentation/tailsonality_result_page.dart`
- `lib/features/profile/domain/pet_profile.dart`、`pet_header_info.dart`、`timeline_item.dart`、`calendar_month.dart`、`health_record_icons.dart`；`data/timeline_repository.dart`；`presentation/widgets/pet_info_card.dart`、`timeline_item_tile.dart`、`archive_calendar.dart`；`presentation/growth_archive_page.dart`、`day_detail_page.dart`
- `lib/features/user_profile/data/public_profile_pet_repository.dart`、`presentation/public_profile_page.dart`
- `lib/core/theme/colors.dart`、`lib/core/network/api_paths.dart`、`lib/l10n/app_en.arb`、`app_id.arb`
- 测试：`test/user_profile/public_profile_pet_test.dart`、`test/profile/timeline_five_class_render_test.dart`、`diary_guest_state_test.dart`、`timeline_place_checkin_test.dart`、`test/tailsonality/tailsonality_result_wire_contract_test.dart`、`tailsonality_unlock_test.dart`、`test/analytics/v112_events_test.dart`

### Change Log

- 2026-09-30：Story 3.3 实现（佩戴表 + 首次解锁自动佩戴 + 切换 / 卸下接口 + 结果 DTO `equipped`；档案与公开卡小标；Diary 源⑦ + 日历维 + 日详情；App 列表行内切换、小标胶囊、Diary 条目）；复审 1 条已修；L0 绿，置 review。
- 2026-10-05：**L1 / L2 本地验收**（库 `petgo_v132a`，模拟器 `petgo_verify`）。L1：`TailsonalityBadge*` / `TimelineTailsonalityTest` / `PublicProfilePetBadgeTest` / 访客投影与禁词扫描 / DTO 契约共 61 例绿（含首次解锁自动佩戴、再解锁不替换、切换、未解锁 422、卸下幂等且不自动戴回、并发至多一行、删档级联）。真接口：未解锁结果佩戴 → 422 `tailsonality-badge-locked`；`/pet-profiles/me` 与游客 `/users/1/pet` 同为 `ENFJ`，访客视图不带该字段（AC4.4）；时间线 / 日历不带 `supports=tailsonality` 无条目与 `hasTailsonality`，带上才有；DELETE 连调两次均 204、小标消失；PawCoin 再解锁第二条（ENFP-L）后小标仍为 ENFJ（不自动替换）。L2：档案卡与公开主页名字旁小标胶囊；Diary 条目（卡图缩略 + 代号角色名 + Tested 日期）点开进结果页；同日条目按时间正序排在当日末尾（既定规则）；日历在有打卡的日子显示打卡图标（优先级），临时把解锁时间挪到无打卡的 10/2 后显示性格图标、日详情含该条目（已改回）；列表「Wearing」→ 卸下弹窗「Remove badge?」→ 全部变「Wear this」、档案卡与公开卡小标消失；再点「Wear this」切到 ENFP，档案卡与公开卡同步变 ENFP。未解锁行为「Not unlocked yet」文字、无按钮。证据截图见本机 `~/Downloads/设计图/L1L2验收/3-3-佩戴小标与Diary条目/`。
