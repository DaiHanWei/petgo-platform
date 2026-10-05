# Story 5.1: 公共徽章组件与 App 六处替换

Status: review

## Story

As a 完成了里程碑的用户,
I want 看到属于这个里程碑的专属徽章,
so that 每一次解锁都有辨识度、值得收藏。

## Acceptance Criteria

**AC1 — code → 语义键映射表（按完整 code 寻址）** `[L0]`
1. NEW `petgo_app/lib/features/profile/domain/milestone_badge_assets.dart`：
   - `const Map<String, String> kMilestoneBadgeKeys`：**78 个完整 code 各一条**，值为语义键（下表 40 个），同语义跨物种共用一个键（如 Camilan：`C-S8` / `D-S8` / `G-S6` → `first_treat`）；
   - `String? milestoneBadgeKeyOf(String code)` = `kMilestoneBadgeKeys[code]`（**只做精确查表**）；
   - `String milestoneBadgeAssetPath(String key)` = `'assets/milestone/$key.webp'`（全仓库唯一出现 `assets/milestone/` 字面量的地方）；
   - 锁定态保留键 `locked`（全局一张，素材清单 §5「D. 锁定态」）。
2. **禁止按后缀 / 前缀寻址**（有测试）：
   - `milestoneBadgeKeyOf('G-S8')`（点赞）≠ `milestoneBadgeKeyOf('C-S8')`（零食）；`milestoneBadgeKeyOf('C-S8') == milestoneBadgeKeyOf('G-S6')`；
   - 不在表里的 code（`'X-S8'`、`'C-S99'`、`''`）→ `null`；
   - 源码扫描：`milestone_badge_assets.dart` 与 `milestone_badge.dart` 不出现 `substring(` / `split(` / `endsWith(` / `startsWith(` / `RegExp(`。
3. 跨库测试（后端，NEW `petgo-backend/src/test/java/com/tailtopia/profile/domain/MilestoneBadgeMappingTest.java`，读 App 源码，写法照 `MilestoneCatalogI18nTest`：路径 `../petgo_app/lib/features/profile/domain/milestone_badge_assets.dart`，**找不到文件即失败，不跳过**）：
   - 映射表的 code 集合 == `MilestoneCatalog` 三物种全部 code（78 个，互为子集）；
   - 每个值匹配 `^[a-z0-9_]+$`，不同值恰为 40 个；
   - 抽查三组共用：`C-S8/D-S8/G-S6`、`C-M5/D-M5/G-M1`、`C-S16/D-S16/G-S9` 各自同键。

**AC2 — `MilestoneBadge(code, size, locked)` 公共组件** `[L0]`
1. NEW `petgo_app/lib/features/profile/presentation/widgets/milestone_badge.dart`：
   `MilestoneBadge({required String code, required double size, bool locked = false, MilestoneLevel? level, WidgetBuilder? fallback})`。
2. 取图：
   - `locked == false` 且该 code 的语义键素材**存在** → `Image.asset(milestoneBadgeAssetPath(key), width: size, height: size, fit: BoxFit.contain, filterQuality: FilterQuality.medium)`，key `ValueKey('milestoneBadgeArt_$code')`；`errorBuilder` 回落（双保险）。
   - `locked == true` → 若 `locked` 素材存在用它（**不**用该枚真图做灰度——锁定态「不能暴露是哪一枚」，素材清单 §5）；否则回落现有锁定渲染（浅灰圆 `AppColors.line2` + `Icons.lock_outline_rounded` `AppColors.muted`）。
   - 素材不存在 → 调用方给的 `fallback`；没给则默认「奖杯 + 级别色圆底」：级别色 L `AppColors.gold` / M `AppColors.mint` / S `AppColors.triageGreen`（`level` 为 null 时用 `AppColors.mint`），图标 `Icons.emoji_events_rounded` 白色、约 `size * 0.4`。
3. 「素材是否存在」由 NEW `MilestoneBadgeAssets` 从 `AssetManifest.loadFromAssetBundle(rootBundle)` 读一次、缓存 `Set<String>`；未读完前按「不存在」渲染回落（不闪白、不占位图）。提供 `@visibleForTesting static Set<String>? debugOverride`。
4. **同一张图等比缩放**：六处只传 `size`，不存在 `_small` / `_large` 两套路径（源码扫描：`assets/milestone/` 只在 `milestone_badge_assets.dart` 出现）。
5. widget 测试：有素材（`debugOverride` 含该路径）→ 出 `Image`，其 `AssetImage.assetName` = 语义键路径；无素材 → 默认回落含 `Icons.emoji_events_rounded`；`locked` 无素材 → `Icons.lock_outline_rounded`；传 `fallback` → 渲染 fallback；共用语义的三个 code 解析出同一路径。

**AC3 — App 六处改用 `MilestoneBadge`** `[L0]`
「取哪张图、有没有图」的判断全部收进 `MilestoneBadge`，六处不再各自构造奖杯 / 锁图标（回落外观由组件默认或调用方 `fallback` 提供，见 Dev Notes「回落外观」）：
1. **庆祝页大徽章**：`_badge(120)` 调用处（`milestone_celebration.dart` L194）→ `MilestoneBadge(code: item.code, size: 120, level: item.level, fallback: (_) => <现紫渐变圆 + 白奖杯>)`；级别小标签仍叠在下沿。
2. **KOLEKSI 圆点**：`_collectionCircle(color:)` 的有色分支（L386、L390）→ `MilestoneBadge(code: m.code, size: 44, level: m.level)`；「+N」格（`text` 分支）不变；「圆点数 + N = 已解锁总数」逻辑不变。
3. **列表页徽章墙** `_Badge`（`milestone_list_page.dart` L532-602）：64 圆（L571-588）→ `MilestoneBadge(code: item.code, size: 64, level: item.level, locked: !completed)`；外层 `GestureDetector` key `milestoneBadge_${item.code}`、点击分流、标题文字不变。
4. **列表底抽屉** `_showBadgeSheet`（L634+）76 圆（L701-722）→ `MilestoneBadge(code: item.code, size: 76, level: item.level, locked: !completed)`。
5. **Diary 时间线**（`timeline_item_tile.dart`）：
   - 系统里程碑 banner `_milestoneBanner`（L246+）左侧 40×40 白底图标块里的 emoji（L296）→ 有 code 时 `MilestoneBadge(code: code, size: 34, fallback: (_) => Text(emoji, fontSize 21))`，无 code 保持 emoji；
   - 照片卡里程碑角标 `_milestoneStamp`（L212-235）的「🏆」→ 行内 `MilestoneBadge(code, size: 14, fallback: (_) => Text('🏆', …))` + 名称；key `timelineMilestoneStamp` / `timelineMilestoneStampTap` 不变。
6. **通知中心**：`MILESTONE_NODE` / `MILESTONE_SM_NODE` 两型（`notification_center_page.dart` L666-677）的 40×40 图标块（L881-891）内容 → `MilestoneBadge(code: item.targetRef ?? '', size: 32, fallback: (_) => Icon(icon, size: 20, color: fg))`，底色块保留。`targetRef` 不是合法 code（如定时任务的生日节点）时自然回落原图标。

**AC4 — 保留清单一律不变** `[L0]`
既有测试不改断言仍全绿：`milestone_celebration_test.dart`（统一全屏、无 `milestoneChestTap`，D-11 不做分级仪式）、`milestone_list_test.dart`（L109-112 `milestoneBadge_C-S1`、`emoji_events_rounded`、`lock_outline_rounded`）、`milestone_checkin_test.dart`、`milestone_catchup_test.dart`、`milestone_local_celebrated_test.dart`、`milestone_uncelebrated_badge_test.dart`、`milestone_gray_badge_destination_test.dart`、`timeline_five_class_render_test.dart`、`diary_guest_state_test.dart`、`notification_center_test.dart`（L107 `emoji_events_rounded` findsOneWidget）。三种关闭方式、解锁振动、彩纸、补庆祝、只弹最高级、无新解锁不弹、已完成徽章重温、`{name}` 与双语机制**不碰**。

**AC5 — 素材目录与「放素材不改代码」** `[L0]` `[L2]`
1. NEW `petgo_app/assets/milestone/`（先只放 `.gitkeep`，**不造占位图**，AD-15）；`pubspec.yaml` `assets:` 加 `- assets/milestone/`（照 `assets/age_card/` 那行的位置与注释风格）。确认 `flutter build apk --debug` 在目录只有 `.gitkeep` 时不报 asset 缺失；若报，改放一个 `README.txt`（说明命名规则 `<语义键>.webp`，透明底，建议 512×512）并在同步测试里忽略非 `.webp` 文件。
2. App 测试：`assets/milestone/` 下每个 `.webp` 文件名（去扩展名）必须是 `kMilestoneBadgeKeys` 的某个值或 `locked`（防拼错的孤儿文件）。
3. `[L2]` 本地放入一枚测试素材（如 `first_treat.webp`）→ 六处该 code 同时显示新图；拿掉后回落。结果写 Completion Notes，测试素材不入库。

## Tasks / Subtasks

- [x] **T1 映射表**（AC1）+ App 单测 + 后端跨库测试 `MilestoneBadgeMappingTest`
- [x] **T2 组件**（AC2）：`MilestoneBadge` + `MilestoneBadgeAssets`（manifest 缓存）+ widget 测试
- [x] **T3 六处替换**（AC3）——一次改一处、每处改完跑该处既有测试
  - [x] 庆祝页大徽章 + KOLEKSI
  - [x] 列表墙 + 底抽屉
  - [x] Diary banner + 角标
  - [x] 通知中心两型
- [x] **T4 素材目录**（AC5）：pubspec、`.gitkeep`、孤儿文件测试
- [x] **T5 回归**（AC4）：`flutter analyze` / `flutter test` 全绿；`mvn -B clean package`（跑跨库测试）
- [ ] **T6 L2（本地，待本地验收）**：放一枚测试素材看六处；看 14px 角标与 120px 大徽章两端可读性

## Dev Notes

⚠️ 前置 story 尚未实现：开工前先对照其实际代码核对本文件引用的类名/接口/字段，有出入先改本文件。（本 story 与 Epic 1~4 无依赖，但排在最后；开工前确认六处文件在前序 story 中没有被改动行号。）

> 核对结论（5.1 开工时）：六处文件在 Epic 1~4 中有改动，行号已漂移，但结构与本文件描述一致（`_badge(120)`、`_collectionCircle`、`_Badge` 64 圆、底抽屉 76 圆、`_milestoneStamp` / `_milestoneBanner`、通知中心 40×40 图标块）。
> 映射表 78 条 / 40 键与 App `kMilestoneTitles` 逐条核过：共用同一语义键的 code，印尼语标题完全相同。

### 必读：会被本 story 改到的现有代码

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `petgo_app/lib/features/profile/presentation/widgets/milestone_celebration.dart` | `showMilestoneCelebration` 报 `milestone_celebration_shown`（L57-80）；统一全屏深色页（L142+）；大徽章 `_badge(120)`（调用 L194，定义 L308-326）= **紫渐变 `mint→mint500` 圆 + 辉光 + 白奖杯**（不是级别色）；`_levelColor`（L329-333，L 金 / M 紫 / S 绿）；级别小标签（L336-354）；KOLEKSI `_collection`（L357-405）按宽度算两排容量、末格「+N」，`_collectionCircle`（L407-429）级别色渐变圆 + 奖杯 20；彩纸 `_Confetti`（L437+） | 大徽章与 KOLEKSI 有色圆换成 `MilestoneBadge` | 顶部注释里「三级动效」是**过期注释**（从未实现），可顺手改为「统一全屏页」但不改行为；振动、三种关闭、彩纸、分享 / 查看全部按钮、`+N` 逻辑 |
| `petgo_app/lib/features/profile/presentation/milestone_list_page.dart` | `_levelColor`（L482-486）；`_Badge`（L532-602）：64 圆，已完成级别色实心 + 辉光 + `emoji_events_rounded` 26 / 未完成 `line2` + `lock_outline_rounded`；点击：已完成 → 重温庆祝，未完成健康类 → 去处，其余 → `_showBadgeSheet`；`_showBadgeSheet`（L634+）76 渐变圆（L701-722） | 两处圆换组件 | key `milestoneBadge_${code}`、点击分流、标题 / 级别 chip、底抽屉按钮 |
| `petgo_app/lib/features/profile/presentation/widgets/timeline_item_tile.dart` | `_milestoneStamp`（L212-235）金色胶囊「🏆 {名称}」，key `timelineMilestoneStamp`、可点 key `timelineMilestoneStampTap`；`_milestoneBanner`（L246+）按 S/M/L 配色渐变条，左侧 40×40 白底块放**从庆祝文案尾部剥出的 emoji**（L264-272、L296），无 emoji 按级别默认 🎉/🗓️/🐾 | 两处换组件（回落保持现状） | 配色、标题剥 emoji 逻辑、右侧级别方块、游客示例时间线复用同组件 |
| `petgo_app/lib/features/notify/presentation/notification_center_page.dart` | `_iconStyle`（L639+）：`MILESTONE_NODE` → `emoji_events_rounded` / triageGreen（L666-670），`MILESTONE_SM_NODE` → `military_tech_rounded` / gold（L673-677）；图标块 40×40 r11 + `Icon(icon, size: 20)`（L881-891）；`targetRef` 即里程碑 code（后端 `MilestoneNotifyListener` L50-53、L64-66 传 `event.code()`），文案经 `localizedMilestoneTitle`（L611-622） | 两型的图标块内容换组件 | 其余类型图标映射；未读底色；深链 |
| `petgo_app/pubspec.yaml` | `assets:`（L156-175），含 `assets/age_card/`（L169） | 加 `assets/milestone/` | 其余条目与字体段 |
| `petgo_app/lib/features/profile/domain/milestone_titles.dart` | `kMilestoneTitles` 78 条（code → en/id） | **不改**（映射表另建文件；标题与徽章是两件事） | 三处文案同步机制（`MilestoneCatalog` Javadoc L20-33） |

### 可直接复用

| 要做的事 | 用这个 | 位置 |
|---|---|---|
| 清单事实源 | `MilestoneCatalog`（猫 31 / 狗 31 / 通用 16，`Seq` 按级别自增生成 code） | `petgo-backend/.../profile/domain/MilestoneCatalog.java` L112-219 |
| 跨库读 Dart 源码的测试写法 | `MilestoneCatalogI18nTest`（相对路径 `..`/`petgo_app`、正则抽条目、找不到即失败） | `petgo-backend/src/test/java/com/tailtopia/profile/domain/MilestoneCatalogI18nTest.java` |
| 素材目录 + 映射先例 | `assets/age_card/{species}_{slug}.webp` + `age_card_template.dart` L122 拼路径 | `petgo_app/assets/age_card/` |
| 级别色 | L `AppColors.gold` / M `AppColors.mint` / S `AppColors.triageGreen` | `milestone_celebration.dart` L329-333、`milestone_list_page.dart` L482-486 |

### 关键设计点

- **为什么必须按完整 code**：通用套的编号与猫狗不对齐——Camilan 是 `C-S8` / `D-S8` / **`G-S6`**，而 `G-S8` 是「Suka pertama」（点赞）。按后缀寻址会让其他宠物的零食徽章显示成点赞图（代码核对报告 §3）。`MilestoneCatalog.NEWBIE_PREREQ_SUFFIXES`（L60）按后缀是因为 S1~S5 恰好三套对齐，**不能当成普遍规律**。
- **「素材未到映射返回空」的落法**：映射表 78 条一次写全（它是语义事实，不随素材到货变）；「有没有图」看 asset manifest。这样放一枚 `.webp` 进目录即生效，**不改代码**（NFR-9、epic 5 另注）。
- **回落外观（与 epics 字面的差异，已按「无素材时零视觉变化」定）**：epics 写「回落现有奖杯 + 级别色圆底」，但六处现状并不一致——庆祝页大徽章是**紫渐变**（不是级别色，代码核对报告 §3 ①）、Diary 两处是 **emoji**、通知中心是**圆角方块里的 Material 图标**。组件默认回落 = 「奖杯 + 级别色圆底」（覆盖列表墙、底抽屉、KOLEKSI，这三处本就是这个样子）；另外三处通过 `fallback` 保留现状，保证素材一枚未到时上线零视觉变化、既有测试零改动。
- **锁定态不做灰度真图**：素材清单 §5 要求锁定态「看得出是还没拿到的东西，但不能暴露是哪一枚」，并单列一张全局锁定图。所以 `locked` 永远不读该枚真图。
- **尺寸**：素材清单要求同一枚撑住 26px 角标到 140px 大图；本 story 实际用到 14（角标）/ 32（通知）/ 34（banner）/ 44（KOLEKSI）/ 64（列表墙）/ 76（底抽屉）/ 120（庆祝页）。14px 角标可读性在 L2 验收，若看不清把角标改为 18~20 并在 Completion Notes 记录。
- **不做 L 级强化仪式**（D-11）；**访客视图没有徽章墙**（代码核对报告 §3 最后一条），不适配。

### 映射表（78 code → 40 语义键）

分组按代码核对报告 §3：三物种共有 15 + 猫狗共有 8 + 通用独有 1（G-M2）+ 猫独有 8 + 狗独有 8 = 40。

**A. 三物种共有（15 键 · 45 code）**

| 语义键 | 猫 | 狗 | 通用 | 语义（titleId） |
|---|---|---|---|---|
| `profile_created` | C-S1 | D-S1 | G-S1 | Profil dibuat |
| `first_calendar_photo` | C-S2 | D-S2 | G-S2 | Foto pertama di kalender |
| `first_card_shared` | C-S3 | D-S3 | G-S3 | Kartu pertama dibagikan |
| `first_vet_note` | C-S4 | D-S4 | G-S4 | Catatan dokter pertama |
| `first_daily_post` | C-S5 | D-S5 | G-S5 | Postingan harian pertama |
| `first_treat` | C-S8 | D-S8 | **G-S6** | Camilan pertama |
| `first_comment` | C-S14 | D-S14 | **G-S7** | Komentar pertama |
| `first_like` | C-S15 | D-S15 | **G-S8** | Suka pertama |
| `lulus_pemula` | C-S16 | D-S16 | **G-S9** | Lulus Pemula 🎓 |
| `first_vet_visit` | C-M5 | D-M5 | **G-M1** | Ke dokter hewan pertama |
| `together_30_days` | C-M8 | D-M8 | **G-M3** | 30 hari bersama |
| `growth_records_10` | C-M10 | D-M10 | **G-M4** | 10 catatan tumbuh kembang |
| `first_birthday` | C-L1 | D-L1 | G-L1 | Ulang tahun pertama 🎂 |
| `together_100_days` | C-L2 | D-L2 | G-L2 | 100 hari bersama |
| `together_365_days` | C-L3 | D-L3 | G-L3 | 365 hari bersama |

**B. 猫狗共有（8 键 · 16 code）**

| 语义键 | 猫 | 狗 | 语义 |
|---|---|---|---|
| `first_bath` | C-S6 | D-S6 | Mandi pertama |
| `first_sleep_beside` | C-S9 | D-S9 | Tidur di sisimu pertama |
| `first_car_ride` | C-M2 | D-M2 | Naik mobil pertama |
| `first_vaccination` | C-M3 | D-M3 | Vaksinasi pertama |
| `first_deworming` | C-M4 | D-M4 | Obat cacing pertama |
| `spay_neuter` | C-M9 | D-M9 | Steril selesai |
| `all_health_milestones` | C-L4 | D-L4 | Semua tonggak kesehatan |
| `growth_records_30` | C-L5 | D-L5 | 30 catatan tumbuh kembang |

**C. 通用独有（1 键 · 1 code）**

| 语义键 | code | 语义 |
|---|---|---|
| `first_health_check` | G-M2 | Cek kesehatan pertama |

**D. 猫独有（8 键 · 8 code）**

| 语义键 | code | 语义 |
|---|---|---|
| `cat_first_nail_trim` | C-S7 | Potong kuku pertama |
| `cat_first_purr` | C-S10 | Dengkuran pertama |
| `cat_window_sunbath` | C-S11 | Berjemur di jendela pertama |
| `cat_wand_play` | C-S12 | Main tongkat pertama |
| `cat_box_dive` | C-S13 | Masuk kardus pertama |
| `cat_first_adventure` | C-M1 | Petualangan pertama |
| `cat_met_another_cat` | C-M6 | Bertemu kucing lain |
| `cat_knows_name` | C-M7 | Kenal namanya |

**E. 狗独有（8 键 · 8 code）**

| 语义键 | code | 语义 |
|---|---|---|
| `dog_first_grooming` | D-S7 | Perawatan bulu pertama |
| `dog_first_tail_wag` | D-S10 | Kibas ekor pertama |
| `dog_collar_leash` | D-S11 | Kalung & tali pertama |
| `dog_ball_play` | D-S12 | Main bola pertama |
| `dog_first_swim` | D-S13 | Berenang pertama |
| `dog_first_walk` | D-M1 | Jalan-jalan pertama |
| `dog_met_another_dog` | D-M6 | Bertemu anjing lain |
| `dog_first_command` | D-M7 | Perintah pertama dikuasai |

另：`locked`（锁定态，全局一张，不属于任何 code）。

> ⚠️ 与《设计资产清单》§5 的分组不同：清单把 **C-M1 / D-M1（第一次出门）并为一枚共用**、把 **G-M2 并进疫苗那枚**（得 38~39 枚）。本表按代码核对报告 §3 与本 story 的交付要求取 40 键；若产品最终按清单合并，只需把对应几行的值改成同一个键（一行改动），组件与测试不动（跨库测试的「恰 40 个不同值」断言同步改）。

### 验证层级

L0：AC1（映射单测 + 后端跨库测试）、AC2（组件 widget 测试）、AC3（六处替换后既有测试全绿）、AC4、AC5.1-5.2 · L2：AC5.3 放测试素材看六处、两端尺寸可读性。

### Project Structure Notes

- 组件放 `features/profile/presentation/widgets/`（与庆祝页同目录）；通知中心已 import `profile/domain/milestone_titles.dart`（L14），再 import 本组件不引入新的依赖方向。
- 映射表放 `features/profile/domain/`，与 `milestone_titles.dart` 并列；5.2 的后端 Java 映射以它为源、跨库测试钉一致。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.2/epics-v1.3.2-batch-a.md#Story 5.1]
- [Source: _bmad-output/planning-artifacts/v1.3.2/architecture-v1.3.2-batch-a-delta.md#AD-15]
- [Source: _bmad-output/planning-artifacts/v1.3.2/决策日志-batch-a.md#D-9, D-11, D-12, D-13]
- [Source: _bmad-output/planning-artifacts/v1.3.2/PRD-v1.3.2-batch-a.md#3.1 FR-111 已确认项 / 保留清单]
- [Source: _bmad-output/planning-artifacts/v1.3.2/设计资产清单.md#5 里程碑徽章]
- [Source: _bmad-output/planning-artifacts/v1.3.2/ui-v1.3.2-batch-a.html#D1, D2, D3, D4]
- [Source: _bmad-output/planning-artifacts/v1.3.2/代码核对报告-batch-a.md#0, 3]

## Dev Agent Record

### Agent Model Used

Claude（云端 dev agent）

### Debug Log References

- 前端 L0：`flutter analyze`（No issues）/ `flutter test` 全量 2665 例全绿（review 修复后 profile 目录复跑 481 例全绿）。
- 后端 L0：`./mvnw -B clean package` + 单测 2889 例 0 失败（含新跨库测试 `MilestoneBadgeMappingTest` 3 例）。
- `flutter build apk --debug`：云端无 Android SDK，未能执行（`No Android SDK found`）→ 列入本地验收。

### Completion Notes List

- **L1/L2 待本地验收**：① `flutter build apk --debug` 确认 `assets/milestone/` 只有 README 时不报 asset 缺失（AC5.1，云端无 Android SDK）；② AC5.3 本地放一枚测试素材（如 `first_treat.webp`）看六处同时换新图、拿掉后回落；③ 14px 角标与 120px 大徽章两端可读性（看不清则把角标改 18~20）。
- **映射表**：`milestone_badge_assets.dart` 78 code → 40 语义键（按本文件表一次写全）+ `kMilestoneBadgeLockedKey = 'locked'`；`milestoneBadgeKeyOf` 只做精确查表；`milestoneBadgeAssetPath` 是全仓库唯一出现 `assets/milestone/` 的地方（源码扫描测试钉住）。
- **组件**：`MilestoneBadge(code, size, locked, level, fallback, lockedFallback)` + `MilestoneBadgeAssets`（`AssetManifest` 读一次缓存，未读完按无素材回落、读完后 `ValueListenable` 自动刷新；`debugOverride` 测试缝；`hasArtFor(code)`）。锁定态永不读该枚真图。
- **与本文件的差异**：组件多一个可选参数 `lockedFallback`（列表墙 / 底抽屉的锁定外观尺寸各不相同：锁图标 26 / 36，默认 `size * 0.4` 会有 1~6px 偏差），用于保证素材未到时**零视觉变化**；列表墙、底抽屉、KOLEKSI 也传了 `fallback` 复刻原外观（原外观带辉光 / 渐变，组件默认回落是纯色圆）。
- **六处**：庆祝页大徽章（fallback = 原紫渐变奖杯）、KOLEKSI（fallback = 原级别色圆，`+N` 逻辑不变）、列表墙 64 / 底抽屉 76（`locked: !completed`，key 与点击分流不变）、Diary banner（有 code 时 34，fallback = 原 emoji）与角标（**有素材才换成行内 14px 徽章 + 名称，无素材保持原单个「🏆 名称」文本** —— code-review 低优先项修复）、通知中心两型（32，fallback = 原图标；`targetRef` 非合法 code 自然回落）。庆祝页顶部「三级动效」过期注释改为「统一全屏页（D-11）」，行为不变。
- **素材目录**：`assets/milestone/` 放 README.md（命名规则 / 规格 / 回落说明），不放 `.gitkeep`、不造占位图 —— 与 `assets/place_stamp/`、`assets/tailsonality/` 同一先例；`pubspec.yaml` 已声明。孤儿文件测试只检查 `.webp`。
- **AC4**：保留清单内既有测试全部未改断言、全绿。
- **code-review**：1 条低优先（角标 Row 化后无素材时宽度差约 1px）已修。
- **待确认**：见汇总文件 Epic 5 小节（`lockedFallback` 新增参数、README 代替 `.gitkeep`、映射 40 键 vs 设计资产清单 38~39 枚的合并口径）。

### File List

App（新增）
- `lib/features/profile/domain/milestone_badge_assets.dart`
- `lib/features/profile/presentation/widgets/milestone_badge.dart`
- `assets/milestone/README.md`
- 测试：`test/profile/milestone_badge_test.dart`

App（修改）
- `lib/features/profile/presentation/widgets/milestone_celebration.dart`
- `lib/features/profile/presentation/milestone_list_page.dart`
- `lib/features/profile/presentation/widgets/timeline_item_tile.dart`
- `lib/features/notify/presentation/notification_center_page.dart`
- `pubspec.yaml`

后端（新增）
- 测试：`src/test/java/com/tailtopia/profile/domain/MilestoneBadgeMappingTest.java`

### Change Log

- 2026-10-01：Story 5.1 实现（徽章映射表 + 公共组件 + 六处替换 + 素材目录 + 跨库测试）；复审 1 条低优先已修；L0 绿，置 review。
- 2026-10-06：**L1 / L2 本地验收**（模拟器 `petgo_verify`）。L0 复跑：里程碑 / 徽章 / 时间线五类 / 游客态 / 通知中心相关 App 测试 124 例绿；后端 `MilestoneBadgeMappingTest`（读 App 源码，78 code ↔ 40 语义键）+ `MilestoneCatalogI18nTest` 绿。素材已正式入库（`assets/milestone/` 40 枚 + `locked` + `first_health_check`，2026-10-05 上传），故 AC5.3「放一张 / 拿掉」改为直接看真图：① 徽章墙 64 圆——已完成「Profile created」「First daily post」显示真图，其余 29 枚统一显示 `locked` 图（不暴露是哪一枚）；② 点已完成徽章 → 庆祝页（重温）120 大徽章 + S·SMALL 小标 + KOLEKSI 两枚真图；③ 点锁定徽章 → 底抽屉 76 圆为 `locked` 图；④ Diary 时间线系统里程碑横幅左侧为真徽章；⑤ 通知中心两条里程碑通知的图标块为真徽章。⑥ 照片卡里程碑角标：测试账号无「打卡里程碑」的 Diary 帖，未在真机看到，由 `timeline_five_class_render_test` 覆盖。证据截图见本机 `~/Downloads/设计图/L1L2验收/5-1-徽章组件六处/`。
