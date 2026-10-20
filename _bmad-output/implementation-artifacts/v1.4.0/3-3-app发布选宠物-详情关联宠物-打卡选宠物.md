# Story 3.3: App——发布选宠物、详情关联宠物、打卡选宠物

Status: review

> 版本 v1.4.0 · 主题 multi-pet · 分支 `feat/1.4.0-multi-pet`（从地基 Story 0.1 合入之后的 `dev_1.4.0` 切出）
> 口径优先级：`decision-log.md` ② 节 > `多宠物/1-4-0多宠物prd.md` > `architecture-v1.4.0-multi-pet-delta.md`（AD-1 ~ AD-17、P-1 ~ P-4、V-1 / V-2）> UI 稿 `多宠物/ui-1-4-0-multi-pet-integrated-v1.html`（F 泳道 F1 ~ F6c、G 泳道 G1 ~ G4，2026-10-15 定稿）（均在 `_bmad-output/planning-artifacts/v1.4.0/`）；跨分支契约以 `CROSS-STORY-DECISIONS.md`「2026-10-18 V1.4.0 四条线共用地基」节为准。
> 前置（**计划名**，开工前对照实际提交核对，有出入先改本文件）：
> - **1.3**（后端）：打卡「今日已打卡」逐宠物 + 提交时每只宠物各自盖章 / 新章 / 计数——本 story 依赖它在场所详情与打卡响应里**下发逐宠物结果**（计划字段见 Dev Notes「依赖的后端契约」）；
> - **1.5**（App）：`petListProvider`（列表项含 `id / name / avatarUrl / petType / status / createdAt`，按创建顺序）、`currentPetIdProvider`；
> - **2.1 / 2.4**：宠物状态 `ACTIVE / READ_ONLY`、App 侧只读判定；
> - **3.1**（后端）：发布接口 `petIds`、详情 `pets`。
> 范围层：**只 App**（`petgo_app/`）。后端契约全部由 1.3 / 3.1 提供；若发现缺字段，**不在本 story 改后端**，记 Completion Notes「待确认」并按保守做法降级（见各 AC）。

## Story

As a 多宠物用户,
I want 发 Diary 和打卡时选择宠物、在详情页看到这条关联了哪些宠物,
so that 记录准确归到每只宠物。

## Acceptance Criteria

**AC1 — Diary 发布页宠物 chips（F1 / F2 / F4）** `[L0]` `[L2]`（PRD AC14、决策日志 F4）
1. `publish_compose_page.dart` 的「Untuk:」只读行 `_petTargetRow`（:966-1004）改为**多选 chips 行**，位置不变（类型 chips 之后、同步开关之前，:675-679）。可选列表 = `petListProvider` 中 **`status == ACTIVE`** 的宠物（只读不出现），按创建顺序。
2. 视觉（UI 稿 `.forrow` / `.pchip`）：行容器 `AppColors.mintTint2` 底、圆角 11、内边距 8 × 10、`Wrap` 间距 8、「Untuk:」12 / w600 / `textSecondary`（沿用 `publishForLabel`）；chip = 胶囊（圆角 999）、白底、1.5 描边 `#DCD2F7`、内边距 3 / 10 / 3 / 3、头像 22 圆形 + 名字 12 / w600 `ink2`；**选中**：描边与文字 `#845EC9`（`AppColors.mint`）+ 名字后「✓」（w800）。每个 chip 热区 ≥ 44 高（外包 `ConstrainedBox`，视觉尺寸不变）。
3. **F1 未选**：默认一只都不选；「Bagikan」置灰（`publishSubmit`，:621-623 的 `onPressed` 为 null）；正文占位用新键 `diaryPetsComposeHint`（「Tulis catatan Diary...」）。
4. **F2 已选**：占位沿用 `publishHintGrowth`（:814），`{name}` = 选中宠物名按「宠物名拼接规则」（AC4.4）拼：1 只「Orenji」、2 只「Orenji & Kopi」、≥ 3 只「Orenji dkk.」。
5. **F4 只 1 只正常宠物**：自动选中、「Bagikan」直接可点（仍是可点的 chip，可取消；取消后回到 F1 置灰）。
6. **禁用条件**：`PublishController.canPublish`（`publish_controller.dart`:91-95）在 `type == growthMoment` 时追加「已选宠物 ≥ 1」；Moment / Tips 不受影响。
7. **默认预选**（其余入口均默认不选）：
   - 打卡成功页「Rekam Momen Ini」进来 → 默认选中**本次打卡的宠物**（G3，决策日志 G3）；
   - 里程碑「去发布」（`widget.milestoneCode` 非空）→ 默认选中 Diary Tab 当前宠物（`currentPetIdProvider`；里程碑属于该宠物，1.3 / 3.1 后端会校验帖子关联了它）；它若只读则不预选。
   - `PublishComposePage.open`（:105-138）与构造器新增可选参数 `initialPetIds`（`List<int>?`），预选时过滤掉已不在可选列表里的 id。
8. 宠物列表加载中：chips 行显示与现状同高的占位；加载失败：行内「重试」小字链接（复用现有通用重试文案键），不阻塞 Moment / Tips。
9. 无宠物账号：维持现状（Diary 段灰置 + `publishGrowthNeedsProfile` 提示，:883-898），不出 chips 行。

**AC2 — Moment / Tips 无宠物选择（F5）** `[L0]`（PRD AC15）
- 切到 Moment / Tips：不出 chips 行（现状即无，:680-682 走 `_publicHint`），请求体不带 `petId` / `petIds`。从 Diary 切走再切回，已选状态保留（同一页面实例内）。

**AC3 — 发布请求与成功页（F3）** `[L0]` `[L2]`（AD-10、决策日志 F3）
1. `ContentRepository.publish`（`content_repository.dart`:45-66）新增 `List<int> petIds`；Diary 时请求体写 `petIds`（按创建顺序）**并同时写 `petId` = 第一只**（新旧后端都能收；前后端同批部署，但不赌部署顺序）；Moment / Tips 两者都不写。`PublishController.publish`（:215-240）的 `int? petId` 参数换为 `List<int> petIds`（调用点 `_publish` :346-396 同步改）。
2. 失败分流：`pet-read-only`（409，选中宠物在提交瞬间变只读）→ Toast 复用 2.4 已有的只读提示文案并刷新 `petListProvider`、chips 去掉它，不进成功页；其他错误沿用现有分支。
3. **F3 成功页**「Bagikan buku tumbuh kembang {petName}」取**第一只关联宠物**（创建顺序第一只，与后端 `content_posts.pet_id` 口径一致）：`_buildArgs`（:516-532）的 `cardToken` / `petName` 改取该宠物——`cardToken` 经 1.1 的 `GET /pet-profiles/me?petId=<第一只>` best-effort 取（现 `_ensurePetLoaded` :285-289 的按宠物版本），失败只隐藏分享按钮、不挡发布（现有注释口径）。
4. 发布成功后失效：现有 `feedProvider` / `myPostsProvider` / `meCountsProvider`（:415-417）照旧；Diary 时把 **每只关联宠物** 的 1.5 petId family（时间线首页 / 统计 / 日历）都失效（现 :419-420 只失效当前一组）。

**AC4 — 详情页关联宠物胶囊（F6 / F6b / F6c）** `[L0]` `[L2]`（决策日志 2026-10-15 F6 定稿 + F6c）
1. 数据：`ContentDetail`（`content/domain/content_detail.dart`:8，`fromJson` :134）新增 `List<ContentPetRef> pets`（`petId / name / avatarUrl / petType`，解析 3.1 的 `pets` 键；缺省 = 空表）。
2. **显示条件**：只 Diary 且 `pets` 非空（1 只也显示：「Orenji」/「bersama Orenji」）；Moment / Tips / 关联宠物已全删 → 不显示。**Feed 卡片不加**。
3. **有图（F6）**：在 `_ImageCarousel`（`content_detail_page.dart`:579 起，`Stack` :657）里加 `Positioned(left: 12, bottom: 12)` 深色胶囊：底 `rgba(20,16,30,0.55)` + `BackdropFilter` 模糊 8、圆角 999、内边距 4 / 10 / 4 / 4、间距 8；头像环扣 + 名字（白字 12.5 / w600）+ `chevron_right` 白色 16。**滑动轮播时胶囊固定不动**（放在 `PageView` 外层 Stack）。
   - 🔴 **与装饰标签撞位**：现装饰标签也在首图左下（:697-707，`left/bottom: AppSpacing.sm`）。有关联宠物时：胶囊占左下 12 / 12，装饰标签上移到胶囊正上方（同左边距，间距 8）；无关联宠物时装饰标签位置不变（AC5 回归保护）。见「待确认」2。
4. **无图（F6b）**：正文（:259-288）与装饰标签行（:290-303）之后、打卡场所条（:314-317）之前，左对齐浅色胶囊：底 `AppColors.mintTint2`、圆角 999、内边距 4 / 10 / 4 / 4、上 12 下 2；文字 12.5 / w500 `ink2`，宠物名部分 w600 `ink`（`bersama **Orenji & Kopi**`）；`chevron_right` `ink2`。
5. **头像环扣**：每个 24 圆形、`0 0 0 2px #fff` 白描边（`Container` 外圈 2 白边）、后一个左移 6（重叠 6）、**第一个在最上层**（CSS z-index 3/2/1；稿件文字「后一只压前一只一半」与 CSS 不符，按 CSS）；最多 3 个，**> 3 只时第 3 个位置换「+N」**（N = 总数 − 2，同尺寸圆、`mintTint2` 底、11 / w700 `ink2`）。
6. **名字规则**（与 AC1.4 共用一个函数 `formatPetNames`，放 `features/profile/presentation/widgets/` 或同层 util）：1 只 `{name}`；2 只 `diaryPetsPair`；≥ 3 只 `diaryPetsMany`（第一只 + dkk.）。无图版本用 `diaryPetsWithOne` / `diaryPetsWithPair` / `diaryPetsWithMany`。
7. **点击**：点胶囊弹底部抽屉（统一卡片行，同 AC5.2 的行样式、无勾选框、尾部 `chevron_right`），列出**全部**关联宠物（头像 40、名字、物种 · 年龄副标题）；点某行 → `requireLogin`（详情页游客可看，访客视图必须登录）→ `context.push('${VisitorArchiveView.inAppRouteBase}/$petId?from=content_detail')`（J3；`from` 新取值登记到埋点文档 E-17）。只有 1 只时点胶囊**直接**进访客视图，不弹抽屉。
8. 胶囊可读：`Semantics(button: true, label: <名字串>)`；超长名字单行省略（`maxLines: 1, overflow: ellipsis`，胶囊最大宽 = 图宽 − 24）。

**AC5 — 打卡选宠物（G1 / G2 / G4）** `[L0]` `[L2]`（PRD AC3a、决策日志「今日已打卡按宠物算」）
1. 入口不变：场所详情整宽「Check-in」（`place_checkin_button.dart`:30 起）。`_start`（:87-169）在**定位成功之后、提交之前**（:118-124 取 `petProfileProvider` 那一段）改为：取 `petListProvider` 的 ACTIVE 宠物——
   - 0 只 → 现状 Toast `placeCheckinNoPet`（:121-124）；
   - **1 只（G4）** → 不弹选择器，直接提交 `[该宠物]`（现状流程）；
   - **≥ 2 只（G1）** → 弹选宠物抽屉，用户确认后提交所选；关闭抽屉 = 取消本次打卡（按钮复位，不 Toast）。
2. 抽屉组件 `PetPickerSheet`（新建，`features/profile/presentation/widgets/pet_picker_sheet.dart`，**单选 / 多选两种模式**，4.1 问诊 H1 / 4.2 KTP I1 复用单选模式；多选骨架参照 `place_list_page.dart` 的 `_FilterSheet`）：
   - 把手 + 标题 `petPickerCheckinTitle`（左对齐）+ 说明 `petPickerCheckinBody`；
   - 行（UI 稿 `.sheet .crow`）：内边距 11 × 12、1.5 描边 `AppColors.divider` 系、圆角 14、行距 8、行高 ≥ 60；头像 40、名字 14.5 / w600、副标题 12 `ink2`（物种 · 年龄，复用现有 `petTypeCat/Dog/Other` 与年龄格式化）、尾部 `check_box_outline_blank` / `check_box`；**选中**：底 `mintTint2`、描边 `#845EC9`；
   - **今天已在此打过卡的宠物**：底 `#FAFAFB`、名字与头像 0.55 透明、副标题换 `petPickerCheckedInHere`、尾部 `block` 图标、不可点；
   - 默认**全不选**，主按钮置灰；选 n 只后按钮文案 `petPickerCheckinSubmit`（「Check-in ({n})」）；
   - 只列 ACTIVE（只读不出现）。
3. 场所详情按钮「已打卡」态：沿用后端 `checkedInToday`（1.3 改为「全部正常宠物都打过才 true」）；按钮仍可点时，抽屉里按逐宠物结果置灰。
4. 提交：`PlaceRepository.checkIn`（`place_repository.dart`:139-150，现「petIds 本版本 = 当前唯一宠物」）传所选全部；`checkin-already-today`（409，并发 / 状态过期）→ 刷新场所详情（`invalidatePlaceDetailIn`）并 Toast 现有失败文案，不进成功页；现有 `checkin-too-far` / `checkin-no-pet` 分流（:171-189）不变。
5. 打卡成功后失效：现有 `invalidatePlaceDetailIn` + `petPassportProvider`（:138-141）；多宠时对**每只所选宠物**的护照 / 里程碑 provider（1.5 的 petId family）都失效。

**AC6 — 打卡成功页多只盖章（G3）** `[L0]` `[L2]`
1. `PlaceCheckinSuccessArgs`（`place_checkin_success_page.dart`:24-30，现只有 `result` + `petName`）新增 `pets`（每项：`petId / name / avatarUrl / isNewStamp / visitCount`，来自 1.3 的逐宠物结果）；保留 `petName`（= 第一只）供单宠物路径。
2. **1 只**：页面与现状**逐像素同路径**（护照单章页 / 简版章面、副标题 `placeCheckinNewStamp` / `placeCheckinRepeatStamp` :249-251）。
3. **≥ 2 只**：不出护照单章详情（那是单只宠物的护照），改为一排章面（UI 稿 `.stamps`：横排居中、间距 14；每枚 = 110 圆形虚线章面（复用 `_NewStamp` / `_RepeatStamp` 的章面绘制，尺寸按 110）+ 下方宠物头像 26 + 名字 12 `ink2`，间距 6）；再访的章在名字后标「×{visitCount}」（复用 `placeCheckinRepeatStamp` 的计数写法不新增键）。副标题：有新章的宠物名按 AC4.6 拼接后套 `placeCheckinNewStamp`（「Orenji & Kopi dapat cap baru」）；全部再访 → `placeCheckinRepeatStamp(place, 第一只的 visitCount)`。超过 2 枚时横向可滚（`SingleChildScrollView` 横向），不换行。
4. 盖章动画页（`PlaceStampCeremonyPage`）：只要**任一**所选宠物是新章就播一次（章面是场所章，各宠物相同）；`args` 原样传递。
5. 「Rekam Momen Ini」（:206-226）→ `PublishComposePage.open(..., initialPetIds: 本次打卡全部宠物)`（AC1.7）。
6. 「第一次打卡」里程碑庆祝（:126-130 `celebrateAutoMilestoneIfAny`）：多宠时按所选宠物**逐只**检查并依次弹（若 1.5 的里程碑 provider 已是 petId family，则逐只调用；否则保持只查当前宠物并记「待确认」）。

**AC7 — 埋点 `diary_published.pet_count`** `[L0]`（PRD §4、云端规则「埋点」）
1. 全仓**不存在** `diary_published` 事件（App 与后端均检索不到；PRD 写「既有事件」有误）。本 story **新增**：Diary 发布成功（`id != null && type == growthMoment`，`_publish` 成功分支 :402 之后）报 `Analytics.capture('diary_published', {'pet_count': n})`；Moment / Tips 不报。不带宠物名 / petId / 正文。
2. 登记到 `docs/reference/analytics-posthog-tracking.md`（事件表加一行，落点 `publish_compose_page.dart`）；`content_publish_submitted`（:351）不动。
3. 访客视图 `from=content_detail`（AC4.7）同时登记为 `diary_visitor_viewed.from` 的新取值。

**AC8 — 文案（EN / ID 两份 arb 同时加）** `[L0]`（文案对照表 F / G 组，NFR7）
1. 新键（已 grep `app_id.arb`，无重名；`petPicker*` / `diaryPets*` 两个前缀现均未被占用）：

   | 键 | 对照表 | `app_id.arb` | `app_en.arb` |
   |---|---|---|---|
   | `diaryPetsComposeHint` | F-1 | Tulis catatan Diary... | Write a Diary entry... |
   | `diaryPetsPair`（`{first}`、`{second}`） | F-2 有图 | {first} & {second} | {first} & {second} |
   | `diaryPetsWithPair` | F-2 无图 | bersama {first} & {second} | with {first} & {second} |
   | `diaryPetsMany`（`{name}`） | F-3 有图 | {name} dkk. | {name} and others |
   | `diaryPetsWithMany` | F-3 无图 | bersama {name} dkk. | with {name} and others |
   | `diaryPetsWithOne`（`{name}`） | **对照表未列**，按 F-2 / F-3 同句式推导（待本地运营审核，见「待确认」3） | bersama {name} | with {name} |
   | `diaryPetsMore`（`{n}`） | F6c「+N」 | +{n} | +{n} |
   | `petPickerCheckinTitle` | G-1 | Siapa yang ikut hari ini? | Who's with you today? |
   | `petPickerCheckinBody` | G-2 | Pilih minimal 1 hewan. Setiap hewan dapat cap sendiri. | Choose at least 1 pet. Each pet gets its own stamp. |
   | `petPickerCheckedInHere` | G-3 | Sudah check-in di sini hari ini | Already checked in here today |
   | `petPickerCheckinSubmit`（`{n}`） | G2 按钮「Check-in ({n})」 | Check-in ({n}) | Check in ({n}) |

2. 沿用不新增：`publishForLabel`（Untuk:）、`publishHintGrowth`（F2 / F4）、`placeCheckinNewStamp`（G-4 =「{pet} dapat cap baru」已存在，`{pet}` 填拼接名）、`placeCheckinRepeatStamp`、`placeCheckinButton`、`placeCheckinNoPet`、`petTypeCat / Dog / Other`。
3. `test/l10n/microcopy_rules_test.dart`（两份 key 对齐、≤ 1 emoji）绿；生成 `app_localizations*.dart` 随 `flutter gen-l10n` 更新。App 不渲染后端显示串（宠物名是用户数据，不是显示串）。

**AC9 — 回归与验收** `[L0]` `[L2]`
1. **单宠物账号**：发布页（F4 自动选中，外观同现状「Untuk: 🐱 Orenji」只是可点）、打卡（G4 不弹）、成功页、详情页（单宠 Diary 出 1 只胶囊——这是新增可见变化，按 UI 稿规则）其余行为不变；既有测试全绿（不删、不 skip），按设计打破的只更新断言。
2. `flutter analyze` 零新增告警；相关测试绿；**不对整个文件跑 `dart format`**（存量未格式化，手工对齐缩进）；**不写 golden**。
3. **L2（留本地）**：Android 模拟器演示 **F2**（3 只选 2 只发布）、**F6**（有图胶囊 + 抽屉 → 访客视图；同时看 F6b 无图、F6c 4 只「+2」）、**G2**（3 只中 1 只已打过置灰、选 2 只 → G3 两枚章 → Rekam Momen Ini 预选这 2 只）；按 `ui-fidelity-check` 对 F1 / F2 / F4 / F6 / F6b / F6c / G1 / G2 / G3 逐屏打还原度（≥ 95%）。

## Tasks / Subtasks

- [x] **T0 开工核对**：确认 1.3 / 1.5 / 2.4 / 3.1 实际提交的 provider 名、模型字段、后端响应键（Dev Notes「依赖的后端契约」）；不一致先改本文件；核对 Dev Notes「现状」file:line
- [x] **T1 文案**（AC8）：两份 arb + gen-l10n
- [x] **T2 公共件**：`formatPetNames`、`PetAvatarStack`（环扣 + +N）、`PetPickerSheet`（单选 / 多选）
- [x] **T3 发布页**（AC1 / AC2 / AC3）：chips、禁用条件、预选、请求体、F3 取第一只、失效
- [x] **T4 详情页**（AC4）：模型 `pets`、F6 / F6b / F6c 胶囊、装饰标签让位、抽屉与跳转
- [x] **T5 打卡**（AC5 / AC6）：选择器接入、逐宠物置灰、提交、成功页多章、Rekam Momen Ini 预选、里程碑逐只
- [x] **T6 埋点**（AC7）：`diary_published` + 文档登记
- [x] **T7 测试**（AC9）：widget / unit；`flutter analyze`
- [x] **T8** L2 标「待本地验收」（模拟器演示 + 还原度）

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ 8ca69f9f；1.5 / 2.4 合入后行号会漂，以代码为准）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo_app/lib/features/content/presentation/publish_compose_page.dart` | `open`（:105-138）无宠物参数；`_linkedPet`（:203）+ `_ensurePetLoaded`（:285-289）取唯一档案；`_publish`（:346-…）：`content_publish_submitted`（:351）、Diary 取 `_linkedPet.id`（:363-366）、成功失效 :415-420；`_buildArgs`（:516-532）分享取 `_linkedPet`；`publishSubmit` 按钮（:621-623）；Diary 段 `_petTargetRow` + 同步开关（:675-679）；`_hint`（:811-821）；`_petTargetRow`（:966-1004）只读「Untuk: 🐾 {pet}」 | chips 多选、预选、请求体、F3、失效、埋点 | 95% 抽屉、审核中覆盖层 ≥ 900ms、@ / 露出 / 打卡条、里程碑「去发布」回填庆祝（:421-470） |
| `…/content/domain/publish_controller.dart` | `canPublish`（:91-95）；`isSharing`（:113）；`publish({idempotencyKey, int? petId, placeCheckinToken})`（:215），Diary 才带 petId（:235） | 加已选宠物、`petIds` | 上传 / 顺序锁状态机 |
| `…/content/data/content_repository.dart` | `publish`（:45-66）`if (petId != null) data['petId']`（:62） | 加 `petIds` | 空字段整键省略的写法 |
| `…/content/presentation/publish_result_page.dart` | `PublishResultArgs`（`cardToken` / `petName` / `petEmoji`）；分享 `publish_done_share_tapped`（:253） | 不改结构，只改传入值 | — |
| `…/content/domain/content_detail.dart` | `ContentDetail`（:8）、`fromJson`（:134）无宠物 | 加 `pets` | 既有字段解析 |
| `…/content/presentation/content_detail_page.dart` | 作者行 → 图片（`_ImageCarousel` :249-257）→ 正文（:259-288）→ 无图装饰标签（:290-303）→ 打卡场所条（:314-317）→ 分隔线 + KOMENTAR（:319-325）；`_ImageCarousel`（:579 起）`Stack`（:657）内装饰标签左下（:697-707）、页码右上（:708-722） | F6 / F6b / F6c 胶囊 | 首图锁高、通栏出血、Hero tag、作者行**不再加东西**（决策日志 F6） |
| `…/place/presentation/place_checkin_button.dart` | `PlaceCheckinButton(token, checkedInToday)`（:30-37）；`_start`（:87-169）权限 → 定位 → `petProfileProvider` 唯一宠物（:118-124）→ `checkIn(petIds: [pet.id])`（:131-136）→ 动画页 / 成功页（:142-155）；失败分流（:171-189） | 选择器接入 | 权限与定位引导、精度原样提交、`checkin-already-today` 置已打卡 |
| `…/place/presentation/place_detail_page.dart` | `PlaceCheckinButton(token: token, checkedInToday: p.checkedInToday)`（:279） | 传逐宠物今日状态 | — |
| `…/place/data/place_repository.dart` | `checkIn(token, latitude, longitude, petIds)`（:139-150） | 注释改为多宠 | 请求体形态 |
| `…/place/domain/place_checkin_result.dart` / `place_detail.dart` | 单宠物结果（`isNewStamp` / `visitCount` / `passportNo` / `stampCount`）；`checkedInToday`（:70 / :103） | 解析 1.3 新增的逐宠物字段 | 既有字段解析（`place_checkin_result.dart`:5 注释称契约由 `test/place/place_checkin_result_test.dart` 钉住，**该文件不存在，需新建**） |
| `…/place/presentation/place_checkin_success_page.dart` | `PlaceCheckinSuccessArgs(result, petName, ceremonyPlayed)`（:24-30）；里程碑庆祝（:126-130）；副标题（:249-251）；护照单章 / 简版章面（:256-292）；「Rekam Momen Ini」（:206-226） | 多章、预选 | 单宠物路径不变 |
| `…/place/presentation/place_stamp_ceremony_page.dart` | 新章全屏动画（场所章面，与宠物无关） | 任一新章即播 | 看门狗 / 只导航一次 |
| `…/profile/data/profile_repository.dart` | `petProfileProvider`（:143，`FutureProvider<PetProfile?>`，1.5 改派生兼容层） | 本 story 新代码**不再引用**它（§5「新代码不引用」） | — |
| `…/profile/presentation/visitor_archive_view.dart` | 站内访客路由 `/pets/:petId`（:61-64，必须登录）、`diary_visitor_viewed.from`（:74-80） | 新 `from` 取值 | — |
| `petgo_app/lib/core/analytics/analytics.dart` | `capture`（:163）、PII 键过滤（:42 / :356）、`debugCaptureSink`（:159） | 无改动 | 不放宽 scrub |
| `petgo_app/lib/l10n/app_id.arb` / `app_en.arb` | `publishHintGrowth`（id :988 / en :1198）、`publishForLabel`（:1165 / :1862）、`placeCheckinNewStamp`（:2396 / :3710）、`placeCheckinRepeatStamp`（:2398 / :3712） | 加 11 个键 | — |

### 依赖的后端契约（计划名，以 1.3 / 3.1 实际提交为准）

- **发布**（3.1）：请求 `petIds: number[]`（Diary ≥ 1、≤ 5）+ 兼容 `petId`；错误 `pet-read-only`（409，带 `petId`）、非本人 404、缺宠物 422。
- **详情**（3.1）：`pets: [{petId, name, avatarUrl, petType}]`，仅 Diary 有关联时出现，按宠物创建顺序。
- **场所详情**（1.3）：`checkedInToday`（全部正常宠物都打过才 true）+ 逐宠物今日状态，计划为 `checkedInTodayPetIds: number[]`。〔T0：实际键名 **`checkedInPetIds`**（`PlaceDetailResponse`，游客 NON_NULL 省略），App 按实际键解析。〕**缺失时降级**：抽屉不置灰任何行，靠提交时 409 兜底（AC5.4），Completion Notes 记「待确认」。
- **打卡响应**（1.3）：顶层字段保持（= 第一只，旧版兼容）+ 逐宠物结果，计划为 `pets: [{petId, isNewStamp, visitCount, stampCount}]`。〔T0：实际 `PlaceCheckinResponse.PetStamp{petId, isNewStamp, visitCount, passportNo, stampCount}`，**不含名字 / 头像** → App 从 `petListProvider` 取名字 / 头像拼 `PlaceCheckinPetArgs`。〕
- 〔T0 其余核对〕发布 `ContentPostCreateRequest.petIds`（`@Size(max=5)`）+ `petId` 兼容、409 `pet-read-only` 扩展成员 `petId`（3.1 / 2.1）一致；详情 `ContentDetailResponse.pets` = `ContentPetView(petId, name, avatarUrl, petType)` 一致；`petListProvider`（`PetListItem.id/name/avatarUrl/petType/birthday/status/isReadOnly`，最早建档在前）一致；当前宠物页面一律读 **`effectivePetIdProvider`**（1.5：`currentPetIdProvider` 只记偏好、可能失效）；`petPassportProvider` **不是** petId family（跟随当前宠物）→ AC5.5 护照整体失效一次、里程碑 `milestoneListProvider(petId)` 逐只失效；`celebrateAutoMilestoneIfAny` 原只查当前宠物 → 加可选 `forPetId`（AC6.6 逐只）；`timelineFirstPageProvider` / `archiveStatsProvider` / `calendarMonthProvider` / `dayDetailProvider` 为 petId family，AC3.4 按 AD-14「整组失效」整族 invalidate（覆盖每只关联宠物）。**缺失时降级**：≥ 2 只的成功页每枚章都按顶层 `isNewStamp` 显示，记「待确认」。

### 关键设计说明

- **宠物顺序唯一口径 = 创建顺序**：chips、F2 占位拼名、F3 分享、F6 名字 / 头像、G3 章序全部按 `petListProvider` 的创建顺序（与后端 `content_posts.pet_id`「第一只」一致），不按用户点选先后。
- **为什么打卡 1 只时不弹**：PRD / G4「只有一只正常宠物时不显示选择器、自动选中」；与发布页 F4（显示但已选中）不同，是两处有意的差异。
- **只读宠物**：发布页、打卡抽屉都不出现（不是置灰）；只读宠物的「今天已打过」不需要显示。
- 当前宠物只从 `currentPetIdProvider` 读，页面不自存 petId（§5「App 状态单一来源」）；本 story 唯一用到当前宠物的地方是里程碑「去发布」预选。
- 公共件（`PetPickerSheet`、`PetAvatarStack`、`formatPetNames`）是 4.1 / 4.2 的复用基础，接口按「单选 / 多选 + 可禁用行 + 副标题覆盖」设计，不为它们预做业务逻辑。

### 与前后 story 的边界（本 story 不做）

- **不改后端**（发布 `petIds`、详情 `pets` = 3.1；逐宠物打卡 = 1.3；推荐物种 = 3.2）。
- 不做 Diary Tab 切换器 / 宠物列表（1.5）、只读横幅与拦截抽屉 E1（2.4；本 story 只读宠物直接不出现在选择里，无需拦截）。
- 不做问诊 / 兽医咨询 / KTP 选宠物（4.1 / 4.2，复用本 story 的 `PetPickerSheet`）。
- Feed 卡片不显示关联宠物；作者行不加宠物行（决策日志 F6）。
- 不改 `PayChannelPicker`、通用支付面板。

### 架构约束

- AD-14：新代码只读 `petListProvider` / `currentPetIdProvider`，不新引用 `petProfileProvider`；petId family 失效按「整组失效重取」。
- §5 埋点：事件名照 PRD §4，经 `Analytics.capture` + scrub，不带 PII；按 App 埋点守卫规则登记。
- 文案：arb 两份同加；不硬编码可见文字（「+{n}」也走 arb）。
- 视觉：不写 golden（云端 Linux 字体渲染不同），用结构断言（`find.byKey` / 组件树 / 文本）证明行为；视觉与还原度标 L2 留本地。

### 待确认（实现按默认做法，不阻塞）

1. **`diary_published` 不存在**：默认新增该事件（Diary 发布成功报，属性只有 `pet_count`）；若产品原意是给 `content_publish_submitted` 加属性，改一行即可。
2. **F6 胶囊与装饰标签同在首图左下**：UI 稿未画两者共存；默认胶囊占左下、装饰标签上移到胶囊之上。
3. **`diaryPetsWithOne`（无图 1 只「bersama {name}」）对照表未列**：按 F-2 / F-3 句式推导，提交本地运营审核。
4. **F2 占位 3 只以上**：稿件 F2 注「建议只拼前两只 + dkk，第三步定」，F6c 后来定为「第一只 + dkk.」；默认与 F6c 统一为「第一只 + dkk.」。
5. **F6 抽屉无标题**：稿件未画抽屉、对照表无标题文案；默认只放把手 + 宠物行，不加标题。
6. 自己的 Diary 点胶囊也进访客视图（J3，按稿件）；若产品希望本人进自己的 Diary Tab 并切到该宠物，后续再改。

### 测试

**验证层级**
- **L0（`flutter analyze` + `flutter test`，云端可跑）**：
  - `test/content/publish_compose_test.dart`（补）：3 只 ACTIVE + 1 只只读 → 出 3 个 chip（只读不出现）；未选「Bagikan」禁用；选 2 只占位含「Orenji & Kopi」；只 1 只自动选中可发布；Moment / Tips 无 chips 行；`initialPetIds` 预选（过滤非 ACTIVE）；里程碑入口预选当前宠物。
  - `test/content/publish_controller_test.dart`（补）：`canPublish` 在 Diary 无宠物时 false；`publish` 透传 `petIds`。
  - `test/content/content_repository_test.dart`（补）：Diary 请求体同时有 `petIds` 与 `petId`（第一只）；Moment 两者都无。
  - `test/content/publish_done_share_test.dart`（补）：F3 分享取第一只宠物的 `cardToken` / 名字。
  - `test/content/publish_diary_published_analytics_test.dart`（新增，`Analytics.debugCaptureSink`）：Diary 成功报 `diary_published{pet_count:2}`、Moment 不报、属性无名字 / id。
  - `test/content/content_detail_pets_test.dart`（新增）：`fromJson` 解析 `pets` / 缺省空表；有图 1 / 2 / 3 / 4 只的胶囊文本与头像数（4 只出「+2」）；无图走浅色胶囊且位于正文之后、分隔线之前；Moment 不显示；有装饰标签时两者都在且不重叠（结构断言：装饰标签在胶囊上方）；点 1 只直接跳 `/pets/{id}?from=content_detail`、点多只先弹抽屉；游客触发登录门控。
  - `test/content/content_detail_test.dart`、`content_detail_checkin_strip_test.dart`、`content_tag_test.dart`（既有，回归：无关联宠物时布局不变）。
  - `test/place/place_checkin_pet_picker_test.dart`（新增）：ACTIVE 1 只不弹直接提交；2 只以上弹抽屉、默认全不选按钮禁用、已打卡宠物置灰不可点、选 2 只按钮「Check-in (2)」并提交 `petIds` 两项；关闭抽屉不提交；`checkin-already-today` 刷新详情不进成功页；后端无逐宠物字段时降级不置灰。
  - `test/place/place_checkin_success_multi_test.dart`（新增）：2 只 → 两枚章 + 名字 + 副标题拼名；混合新章 / 再访各自标；1 只时与现状同路径（护照单章 key 仍在）；Rekam Momen Ini 带 `initialPetIds`。
  - `test/place/place_checkin_flow_test.dart`、`place_checkin_test.dart`、`place_stamp_ceremony_page_test.dart`、`place_wire_contract_test.dart`、`place_detail_wire_contract_test.dart`（既有，回归 + 契约补新字段）；`test/place/place_checkin_result_test.dart`（**不存在，需新建**：顶层字段 + 逐宠物 `pets` 解析 + 缺省降级）。
  - `test/l10n/microcopy_rules_test.dart`（既有，键对齐）。
- **L2（留本地，需 Android 模拟器 + 连 `api.tailtopia.id` 或 stag 真后端 + 真登录）**：AC9.3 的三段演示与逐屏还原度打分；云端 Completion Notes 标「L2 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-multi-pet.md#Story 3.3、UX-DR6 / UX-DR7]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-multi-pet-delta.md#AD-13、AD-14、§5]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物prd.md#3.1 场所打卡、3.3 内容关联宠物、AC3a / AC14 / AC15、§4 埋点]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物-新增文案对照表.md#F、G]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/ui-1-4-0-multi-pet-integrated-v1.html#lane-F（F1 ~ F6c）、lane-G（G1 ~ G4）、CSS .forrow / .pchip / .pkchip / .pk-a / .sheet .crow / .stamps]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#② F3 / F4 / F6 / F6b / F6c / G1 / G3]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/cloud-rules-multi-pet.md]

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，bmad-dev-story 流程）

### Debug Log References

- 基线（改动前，`feat/1.4.0-multi-pet` @ acc497c2）：`flutter analyze` → No issues found；`flutter test` 基线 3557 通过 / 1 跳过（2.4 记录）。
- 首轮全量失败点（均已处理）：① `test/analytics/v112_events_test.dart` 动作后缀白名单无 `_published` → 按该测试既定扩展方式登记 `'_published'`（带注释：PRD §4 定死事件名，扩表不改名），未放宽正则；② `test/pet_passport/pet_passport_stamp_flow_test.dart` 源码守卫要求「Lihat Paspor」在 `if (r.isNewStamp) ...[` 分支 → 恢复按顶层 `isNewStamp`（见偏差 6）；③ 打卡 / 发布页既有测试原先桩 `petProfileProvider`，现改读 `petListProvider` → 只补 `petListProvider` 桩（见下）。
- 改动后：`flutter analyze` → **No issues found**；`flutter test` 全量 → **3620 通过 / 1 跳过 / 0 失败**（+63）。
- code review 修复后：`flutter analyze` → **No issues found**；`flutter test` 全量 → **3629 通过 / 1 跳过 / 0 失败**（再 +9）。

### Completion Notes List

**L1/L2 待本地验收**：AC9.3 三段模拟器演示（F2 3 只选 2 只发布；F6 有图胶囊 + 抽屉 → 访客视图、F6b 无图、F6c 4 只「+2」；G2 3 只中 1 只已打过置灰、选 2 只 → G3 两枚章 → Rekam Momen Ini 预选这 2 只）与 F1 / F2 / F4 / F6 / F6b / F6c / G1 / G2 / G3 逐屏还原度打分（≥ 95%）云端 headless 跑不了，需本地连 stag / 真后端验收。本 story **只 App，后端零改动**，无 L1 测试；**无 Flyway 迁移**（`check-flyway-versions.sh origin/main` OK，242 个迁移）。

**T0 核对**：见 Dev Notes「依赖的后端契约」的〔T0〕标注（`checkedInPetIds` 实际键名、打卡 `pets[]` 不含名字头像、`effectivePetIdProvider`、护照非 family、`celebrateAutoMilestoneIfAny` 加 `forPetId`）。其余 provider / 字段 / 错误码与本文件一致；行号有漂移，以代码为准。

**实现要点**
- 公共件：`profile/presentation/widgets/pet_names.dart`（`formatPetNames(l10n, names, {withPrefix})`，F2 占位 / F6 胶囊 / G3 副标题共用）、`pet_avatar_stack.dart`（`PetAvatarStack`：24 圆 + 2 白边、重叠 6、第一个最上层、> 3 只第 3 位「+N」走 `diaryPetsMore`）、`pet_picker_sheet.dart`（`showPetPickerSheet` / `PetPickerSheet` 单选（点行即返回）/ 多选（默认全不选、主按钮 `submitLabel(n)`）、`disabledPetIds` + `disabledSubtitle`、返回按列表顺序；统一卡片行 `PetCardRow` 供详情抽屉复用）。
- 发布页：`PublishComposePage.open` / 构造器加 `initialPetIds`；`PublishController.petIds` + `setPetIds`，`canPublish` 在 Diary 追加「≥ 1 只」，`publish({idempotencyKey, List<int>? petIds, placeCheckinToken})`（`int? petId` 参数换掉）；`ContentRepository.publish` 加 `List<int> petIds`，非空时写 `petIds` + `petId = 第一只`，空则退回旧 `petId`（Moment / Tips 两者都不写）。chips 行替换「Untuk:」只读行（同位置）：只 ACTIVE、按创建顺序、选中 ✓、热区 ≥ 44；加载中同高占位、失败行内 `commonRetry`、无宠物不出；默认预选只做一次（initialPetIds 过滤 / 里程碑 = `effectivePetIdProvider` 当前宠物且非只读 / F4 只 1 只自动选中）；列表刷新后已选里变只读的自动剔除。占位 F1 `diaryPetsComposeHint` / F2 `publishHintGrowth(拼接名)`。409 `pet-read-only` → Toast `petReadonlyBlockTitle(名字)`（2.4 已有文案）+ 失效 `petListProvider` + 去掉该 id，不进成功页。F3 分享档案按第一只关联宠物 `getMyProfile(petId: 第一只)` best-effort。成功后整族失效时间线 / 统计 / 月历 / 当天详情 + 宠物列表；里程碑回填 / 庆祝按进页时的当前宠物（`_milestonePetId`）。
- 详情页：`ContentDetail.pets`（`ContentPetRef.listFromJson`，缺省空表，`withFollowEpoch` 保留）；`ContentLinkedPetsCapsule`（`content/presentation/widgets/content_linked_pets.dart`）：有图深色毛玻璃（`0x8C14101E` + blur 8）叠在 `_ImageCarousel` 外层 Stack 左下 12 / 12（不随 PageView 滑动），装饰标签有宠物时上移到胶囊正上方（同左边距 12、间距 8），无宠物时位置不变；无图浅色胶囊在正文 / 装饰标签之后、打卡场所条之前（上 12 下 2），宠物名部分 w600 ink；仅 Diary 且非空显示；Semantics button + 名字串；1 只直接 `requireLogin` → `/pets/{id}?from=content_detail`，多只先弹抽屉（把手 + `PetCardRow`，副标题物种）。Feed / 作者行未加（源码测试钉住）。
- 打卡：`PlaceDetail.checkedInPetIds`（null = 未下发）、`PlaceCheckinResult.pets`（`PlaceCheckinPetStamp`）；`PlaceCheckinButton(checkedInPetIds)`：定位成功后读 `petListProvider` 的 ACTIVE —— 0 只 Toast `placeCheckinNoPet`、1 只直接提交（G4）、≥ 2 只弹 `PetPickerSheet`（已打卡行置灰 G-3、关闭 = 取消、开抽屉期间按钮不转圈但不可重复点）；提交所选；全部正常宠物都打过才本地切「已打卡」；护照失效一次 + 每只所选宠物 `milestoneListProvider(id)` 失效；盖章动画「任一新章」即播（`args.anyNewStamp`）。
- 成功页：`PlaceCheckinSuccessArgs.pets`（`PlaceCheckinPetArgs{petId, name, avatarUrl, isNewStamp, visitCount}`）+ `isMultiPet` / `anyNewStamp`；≤ 1 只走改造前同一路径（唯一差别：单只且确定不是当前宠物时章详情读新增的 `petPassportOfPetProvider(petId)`）；≥ 2 只一排 110 章面（横向可滚、居中、间距 14）+ 头像 26 + 名字（再访「名字 ×n」），副标题新章宠物拼名套 `placeCheckinNewStamp` / 全再访 `placeCheckinRepeatStamp(场所, 第一只次数)`；里程碑庆祝逐只 `celebrateAutoMilestoneIfAny(..., forPetId:)`；「Rekam Momen Ini」带 `initialPetIds = 本次打卡全部宠物`；盖章动画页原样透传 `pets`。
- 埋点：新增 `diary_published {pet_count}`（Diary 成功分支，Moment / Tips / 失败不报，无名字 / id / 正文）；`diary_visitor_viewed.from` 新取值 `content_detail`；两者登记到 `docs/reference/analytics-posthog-tracking.md` §4；`content_publish_submitted` 未动。
- 文案：AC8 表 11 键，EN / ID 两份（仓库只有这两个 arb），已 `flutter gen-l10n`。

**L0 测试（均已跑、全绿）**
- 新增：`test/content/content_detail_pets_test.dart`（20：模型解析 / 缺省 / 拼名规则 / F6 1–4 只头像数与「+2」/ 胶囊在 PageView 外且距左下 12 / 装饰标签让位与无宠物回归 / F6b 位置与文本 / Moment 不显示 / 1 只直达、多只抽屉、游客门控 / Semantics / Feed 与作者行源码守卫）、`test/content/publish_diary_published_analytics_test.dart`（3）、`test/place/place_checkin_pet_picker_test.dart`（8）、`test/place/place_checkin_success_multi_test.dart`（7）、`test/place/place_checkin_result_test.dart`（3，story 注明需新建）、`test/profile/pet_picker_sheet_test.dart`（2：单选 / 多选）。
- 补：`test/content/publish_compose_test.dart`（+10：F1 只读不出现 + 置灰 + 占位 / F2 创建顺序 + 拼名 + ≥3 dkk / F4 自动选中可取消 / F5 Moment·Tips 无 chips + 切回保留 / initialPetIds 过滤 / 里程碑预选当前宠物 / 当前宠物只读不预选 / 列表失败重试 / 请求体 petIds + petId 第一只 + F3 分享第一只 / 409 只读）、`test/content/publish_controller_test.dart`（+4）、`test/content/content_repository_test.dart`（+3）、`test/place/place_detail_wire_contract_test.dart`（+3：`checkedInPetIds` 解析 / 空表 / 缺键 null）。
- 只改调用形状 / 桩（断言语义未改、未删、未 skip）：`ContentRepository` 假实现补 `List<int> petIds = const []`（`publish_controller / publish_sync_switch / publish_preview_lightbox / publish_compose / publish_checkin_link / publish_reorder / mention_entry` 测试）；`c.publish(petId: N)` → `petIds: [N]`（`publish_controller_test`、`publish_sync_switch_test`）；补 `petListProvider` 桩（`publish_compose_test` 有档案 Diary 用例、`publish_checkin_link_test`、`place_checkin_test`、`place_checkin_flow_test`）。
- `test/analytics/v112_events_test.dart`：后缀白名单登记 `'_published'`（见 Debug Log）。
- `test/l10n/*`、`content_detail_test` / `content_detail_checkin_strip_test` / `content_tag_test`、`place_stamp_ceremony_page_test`、`place_wire_contract_test` 等既有测试全绿。
- L1：无（本 story 不碰后端）。

#### 与本文件文字的偏差

1. **当前宠物读 `effectivePetIdProvider`**（AC1.7 写 `currentPetIdProvider`）：1.5 规定页面只读 effective（`currentPetIdProvider` 只记偏好、可能已失效）。
2. **`PublishController.publish` 保留可选 `petIds` 入参**：选择状态住在控制器（`petIds` + `setPetIds`，`canPublish` 需要它）；`publish(petIds:)` 给了就先写入，不给用已选的。`ContentRepository.publish` 保留 `int? petId`（老调用方 / 测试），新增 `petIds` 优先。
3. **AC3.4 失效方式**：按 AD-14「整组失效重取」对 `timelineFirstPageProvider` / `archiveStatsProvider` / `calendarMonthProvider` / `dayDetailProvider` 整族 invalidate（⊇ 每只关联宠物）；月历 / 当天详情此前未失效，顺带补上。
4. **AC3.2 Toast 文案**：用 2.4 的 `petReadonlyBlockTitle(name)`（「Slot {name} sudah berakhir」）；2.4 无单独的只读 Toast 键。
5. **AC5.4 `checkin-already-today`**：只 1 只正常宠物时保持改造前「直接切已打卡禁用态、不 Toast」（「必须保持」列）；≥ 2 只时按本 AC 刷新详情 + `placeCheckinFailed` Toast、按钮保持可点。
6. **「Lihat Paspor」按钮条件**：仍按顶层 `isNewStamp`（= 第一只；既有源码守卫 `pet_passport_stamp_flow_test` 钉住该分支）；盖章动画按「任一新章」（AC6.4）。
7. **单只宠物成功页章详情**：单只打卡且**确定不是**当前宠物时读新增的 `petPassportOfPetProvider(petId)`（`pet_passport_repository.dart`，只加不改），否则走原 `petPassportProvider`（单宠账号零变化）。
8. **`celebrateAutoMilestoneIfAny` 加可选 `forPetId`**（1.5 函数，非破坏性扩展）：多宠逐只依次检查；单宠 / 老调用方不传 = 原口径。
9. 打卡选宠物抽屉开着时按钮不转圈（仍不可重复点）：避免抽屉关闭 = 取消时多一个中间态，也让 widget 测试能 settle。
10. F3 分享取第一只的用例写在 `publish_compose_test.dart`（需走发布流程到成功页），未放 `publish_done_share_test.dart`（那里只测成功页本身，结构不变）。
11. 详情抽屉行副标题只有物种（3.1 `ContentPetView` 无生日，无法算年龄）。
12. `PetPickerSheet` 多选未给 `submitLabel` 时用系统本地化「OK」（仓库无通用确认键，打卡一定传 G2 文案）。
13. 全部宠物都只读（有宠物但 ACTIVE 为 0）时发布页不出 chips 行，Diary 无法提交（见待确认 7）。

### code review 结果（2026-10-18，均已修复）

1. **CONFIRMED · 逐只里程碑庆祝互相抑制 / 回报落错宠物**（AC6.6）：本机已庆祝集合只按 code 记、回报用当前宠物。→ `LocallyCelebratedMilestones` 另按 (petId, code) 记显式带 petId 的庆祝（`add / report` 加可选 `petId`、新增 `codesFor(petId)`；当前宠物的庆祝仍同时记进 `state`，不带 petId 的调用方与单宠物行为不变）；`celebrateAutoMilestoneIfAny(forPetId:)` 把 petId 一路传到 `_celebrate` → `markMilestonesCelebrating` / `report`，回报落到被庆祝的那只。修复中另发现两处会让第二只的庆祝丢失：① 回报落库后**整族**失效 `milestoneListProvider` 会作废下一只正在进行的预读（`.future` 挂到超时）→ 带 petId 时只失效那一只；② 上一只弹层退场转场期间宿主页 `isCurrent == false`，下一只被当成「被盖住」丢弃 → 新增 `celebrateAutoMilestonesForPets`（逐只依次、两只之间等宿主重新成为当前路由，最多 ~1.5s，期间进了别的页就停），成功页改调它。测试：`test/profile/milestone_per_pet_celebration_test.dart`（4：非当前宠物不进 state / 当前宠物同时进 state / report 落到给定 petId、不给 = 当前宠物 / 两只同一 code 各弹一次且各自回报 `C-S17@2`、`C-S17@3`）；既有 `milestone_local_celebrated_test`、`milestone_instant_celebration_test` 全绿。
2. **PLAUSIBLE · 里程碑预选覆盖用户点选 / 里程碑回填落到未关联的宠物**：新增 `_userTouchedPets`，异步取到当前宠物时用户已点过 chip 就不再套默认预选；里程碑回填只在里程碑宠物仍在所选里时做（保守口径，不改用第一只——那只宠物不是里程碑的主人）；当前宠物没取到（null）时按改造前口径不带 petId。测试：`publish_compose_test` +3（等待期间点 chip 不被覆盖 / 仍选中 → 回填 `C-S1@2` / 取消勾选 → 不回填）。
3. **补埋点负向用例**：`publish_diary_published_analytics_test` +2（`content-text-blocked` 进被拒页不报、409 `pet-read-only` 留编辑页不报）。

### 待确认

1. **`diary_published` 不存在**（本文件待确认 1）：按默认新增该事件；`v112_events_test` 后缀白名单新增 `_published`。
2. **F6 胶囊与装饰标签共存**（本文件待确认 2）：按默认胶囊占左下、装饰标签上移到其正上方。
3. **`diaryPetsWithOne`**「bersama {name}」/「with {name}」对照表未列，按句式推导，交本地运营审核。
4. **F2 ≥ 3 只占位**：与 F6c 统一「第一只 + dkk.」。
5. **F6 抽屉无标题**：只放把手 + 宠物行。
6. **自己的 Diary 点胶囊也进访客视图**（J3，按稿件）。
7. **全部宠物只读**时发布页不出 chips（只读不出现），Diary 置灰无提示；是否需要引导到续租（E1）请产品确认。
8. **「Lihat Paspor」/ 护照页**：多宠打卡后「Lihat Paspor」→ B4 → 护照页仍是**当前宠物**的护照（护照页不在本 story 范围）；若第一只不是当前宠物，护照页可能看不到这枚新章。
9. **打卡里程碑逐只庆祝**：每只调用一次轮询（各约 2.5s 上限），依次弹；L2 时留意体验。
10. **后端 `checkedInToday` 口径**：1.3 / 2.1 实际为「全部 ACTIVE 宠物都打过」→ 按钮禁用；按钮可点时抽屉按 `checkedInPetIds` 置灰。缺键降级不置灰、靠 409 兜底。

### File List

新增：
- `petgo_app/lib/features/profile/presentation/widgets/pet_names.dart`
- `petgo_app/lib/features/profile/presentation/widgets/pet_avatar_stack.dart`
- `petgo_app/lib/features/profile/presentation/widgets/pet_picker_sheet.dart`
- `petgo_app/lib/features/content/domain/content_pet_ref.dart`
- `petgo_app/lib/features/content/presentation/widgets/content_linked_pets.dart`
- `petgo_app/test/content/content_detail_pets_test.dart`
- `petgo_app/test/content/publish_diary_published_analytics_test.dart`
- `petgo_app/test/place/place_checkin_pet_picker_test.dart`
- `petgo_app/test/place/place_checkin_success_multi_test.dart`
- `petgo_app/test/place/place_checkin_result_test.dart`
- `petgo_app/test/profile/pet_picker_sheet_test.dart`
- `petgo_app/test/profile/milestone_per_pet_celebration_test.dart`（code review #1）

修改（lib）：
- `petgo_app/lib/features/content/data/content_repository.dart`
- `petgo_app/lib/features/content/domain/{publish_controller,content_detail}.dart`
- `petgo_app/lib/features/content/presentation/{publish_compose_page,content_detail_page}.dart`
- `petgo_app/lib/features/place/data/place_repository.dart`（注释）
- `petgo_app/lib/features/place/domain/{place_checkin_result,place_detail}.dart`
- `petgo_app/lib/features/place/presentation/{place_checkin_button,place_checkin_success_page,place_detail_page,place_stamp_ceremony_page}.dart`
- `petgo_app/lib/features/pet_passport/data/pet_passport_repository.dart`（新增 `petPassportOfPetProvider`）
- `petgo_app/lib/features/profile/presentation/milestone_instant_celebration.dart`（可选 `forPetId`；code review：`celebrateAutoMilestonesForPets`、本地集合 / 回报按 petId）
- `petgo_app/lib/features/profile/data/milestone_celebration_reporter.dart`（code review #1：按 (petId, code) 记、回报 / 失效按 petId）
- `petgo_app/lib/l10n/app_en.arb`、`petgo_app/lib/l10n/app_id.arb`

修改（test）：
- 新增用例：`petgo_app/test/content/{publish_compose,publish_controller,content_repository}_test.dart`、`petgo_app/test/place/place_detail_wire_contract_test.dart`
- 调用形状 / 桩：`petgo_app/test/content/{publish_sync_switch,publish_preview_lightbox,publish_checkin_link,publish_reorder}_test.dart`、`petgo_app/test/mention/mention_entry_test.dart`、`petgo_app/test/place/{place_checkin,place_checkin_flow}_test.dart`
- `petgo_app/test/analytics/v112_events_test.dart`（后缀 `'_published'` 入表）

文档：
- `docs/reference/analytics-posthog-tracking.md`（`diary_published`、`diary_visitor_viewed.from=content_detail`）
- `_bmad-output/implementation-artifacts/v1.4.0/3-3-app发布选宠物-详情关联宠物-打卡选宠物.md`（T0 标注、状态、任务、Dev Agent Record）

### Change Log

- 2026-10-18：实现 Story 3.3（Diary 发布页宠物 chips F1 / F2 / F4 + 预选 + `petIds` 请求体 + 409 只读分流 + F3 第一只分享；详情页关联宠物胶囊 F6 / F6b / F6c + 抽屉 → 访客视图；打卡选宠物 G1 / G2 / G4 + 逐宠物置灰 + G3 多章成功页 + Rekam Momen Ini 预选 + 里程碑逐只；`diary_published` 埋点；11 个文案键；公共件 `formatPetNames` / `PetAvatarStack` / `PetPickerSheet`）。L0 绿（analyze 0 issue；test 3620 / 1 skip / 0 fail），Status → review；L1/L2 待本地验收。
- 2026-10-18：code review 修复 3 项（逐只庆祝按 (petId, code) 记 + 回报落到该宠物 + 逐只依次不丢；里程碑预选不覆盖用户点选 + 回填只对仍选中的里程碑宠物；埋点负向用例）。analyze 0 issue；test 3629 / 1 skip / 0 fail。
