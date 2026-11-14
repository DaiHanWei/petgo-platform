# Story 3.1: app物种清单与统一显示函数

Status: ready-for-dev

> 版本 v1.5.0 · 主题 pet-species（会话 ④）· 分支 `feat/1.5.0-pet-species`
> 口径优先级：`decision-log.md`（11-13：emoji 照稿、品种显示全文）> PRD §3.1 / 附录 A / 附录 B / §6「App」> `architecture-v1.5.0-pet-species-delta.md`（D-16、D-17、G-1、G-2）> App UI 稿 F1 与页头文案表（`宠物物种扩展/拆页-App/`）。
> 前置：1.1、1.3（后端返回体的 `species` 字段名；云端开发时后端代码在同一分支，按其实际字段名写）。
> 范围层：**只 App**。不做选择抽屉（3.2）、不改编辑页交互（3.3）、不改身份证建卡表单（3.4）。
> 迁移：无。

## Story

As a 养兔子的用户,
I want App 里所有显示宠物类型的地方都显示「Kelinci」和 🐰,
so that 我的宠物不再是一只「其他」。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地或 stag 后端真接口（本地）· `[L2]` Android 模拟器视觉（本地）。

### AC1 — 清单 `lib/features/profile/domain/pet_species.dart`（新）`[L0]`（D-16）
1. `enum PetSpeciesCode`，27 值，`wire` 与后端 `PetSpecies` **同名同序**：`CAT, DOG, RABBIT, HAMSTER, BIRD, CHICKEN, FISH, LIZARD, TURTLE, SUGAR_GLIDER, SNAKE, DUCK, FROG, GUINEA_PIG, CIVET, HEDGEHOG, HERMIT_CRAB, OTTER, SHEEP, COW, SPIDER, SQUIRREL, GOAT, CRAB, INSECT, MOUSE, OTHER`；`static PetSpeciesCode? fromWire(String?)`（区分大小写，不认识 → null）；`PetSpeciesCode get pickerType`（CIVET … MOUSE、OTHER → OTHER，其余自己）；`String get groupWire`（CAT / DOG / OTHER 大类）。
2. `const List<SpeciesType> kSpeciesTypes`（宫格 15 项，**顺序固定**）：

   | # | code | emoji | nameId | nameEn |
   |---|---|---|---|---|
   | 1 | CAT | 🐱 | Kucing | Cat |
   | 2 | DOG | 🐶 | Anjing | Dog |
   | 3 | RABBIT | 🐰 | Kelinci | Rabbit |
   | 4 | HAMSTER | 🐹 | Hamster | Hamster |
   | 5 | BIRD | 🐦 | Burung | Bird |
   | 6 | CHICKEN | 🐔 | Ayam | Chicken |
   | 7 | FISH | 🐟 | Ikan | Fish |
   | 8 | LIZARD | 🦎 | Kadal | Lizard |
   | 9 | TURTLE | 🐢 | Kura-kura | Turtle |
   | 10 | SUGAR_GLIDER | 🐾 | Sugar Glider | Sugar Glider |
   | 11 | SNAKE | 🐍 | Ular | Snake |
   | 12 | DUCK | 🦆 | Bebek/Entok | Duck |
   | 13 | FROG | 🐸 | Katak | Frog |
   | 14 | GUINEA_PIG | 🐾 | Marmut | Guinea Pig |
   | 15 | OTHER | 🐾 | Lainnya | Other |

   界面名走 l10n（AC4），表里的 `nameId` / `nameEn` 只给搜索与卡面用。
3. 品种（`BreedOption(label)`，`aliases` = `label` 按 `/` 切开每段 trim；**顺序照附录 A，显示全文**）：
   - 猫（39）：Kucing Kampung, Kucing Anggora, Kucing Persia, British Shorthair, Kucing Siam, Ragdoll, Maine Coon, Himalaya, Scottish Fold, Kucing Bengal, Turkish Angora, American Shorthair, Munchkin, Exotic Shorthair, Russian Blue, Norwegian Forest Cat, British Longhair, Ocicat, Siberian, Birman, Khao Manee, Kucing Sphinx, Oriental Shorthair, Snowshoe, Turkish Van, Burmese, Japanese Bobtail / Bobtail, Li Hua / Dragon Li, Scottish Straight, Manx, Bombay, Chartreux, Devon Rex, Kinkalow, Kucing Abyssinian, Minuet / Napoleon, Raas / Kucing Raas, Tonkinese, Campuran / mix
   - 狗（43）：Anjing Kampung, Pudel / Poodle, Pomeranian, Shih Tzu, Golden Retriever, Chihuahua, Siberian Husky, Maltese, Corgi, Maltipoo, Anjing Gembala Jerman, Kintamani / Anjing Bali, Dachshund, Pitbull Terrier, Labrador Retriever, Beagle, Bulldog, Bichon Frise, Rottweiler, French Bulldog, Samoyed, Chow Chow, Pug, Alaskan Malamute, Jack Russell Terrier, Shih-poo, Yorkshire Terrier, Akita, Shiba Inu, Pekingese, Dobermann, Miniature Schnauzer, Spitz, American Bully, Basenji, Basset Hound, Jindo, Miniature Pinscher, Border Collie, Boxer, Thai Ridgeback, Great Dane, Campuran / mix
   - 兔（19）：Netherland Dwarf, Holland Lop, Lokal, Lionhead, New Zealand, Angora, Dutch, Rex, Fuzzy Lop, Flemish Giant, Mini Rex, Bligon, English Spot, Californian, Mini Lop, Florida White, Dwarf Hotot, Jersey Wooly, Campuran / mix
   - 仓鼠（6）：Syrian, Winter White, Campbell, Dwarf, Roborovski, Campuran / mix
   - 鸟（17）：Pigeon / merpati, Lovebird, Songbird, Canary / kenari, Kutilang, Cockatiel, Budgie / parkit, Jalak, Owl, Murai Batu, Shrike / cendet, Pleci, Conure, Parrot, Cucak Ranting, Perkutut, Kacer / Magpie Robin
   - 鸡（15）：Bangkok, Kampung, Broiler, Pakhoy, Mangon, Kate, Shamo, Layer / petelur, Pelung, Serama, Sebright, Ayam Hutan, Cemani, Ayam Jawa, Campuran / mix
   - 鱼（11）：Betta / cupang, Channa, Goldfish, Catfish / lele/patin, Tilapia / mujair, Louhan, Oscar, Koi, Molly, Arowana / arwana, Guppy
   - 蜥蜴（5）：Leopard Gecko, Iguana, Water Monitor / salvator, Calotes versicolor, House gecko / tokek
   - 龟（5）：Sulcata, Red-eared Slider / Brazil, Amboina box turtle, African tortoise, Hermann's tortoise
   - 蜜袋鼯（6）：Classic Grey, Mozaik, Platinum, Leucistic, White Face, Creamino
   - 蛇（6）：Ball Python, Reticulated Python, Corn Snake, Kingsnake, Wagler's Pit Viper, Cobra
   - 鸭（5）：Muscovy / entok, Peking, Lokal, Mojosari, Call Duck
   - 青蛙（7）：Pacman Frog, American Bullfrog, Budgett's Frog, Fire-bellied Toad, Poison Dart Frog, Tomato Frog, White's Tree Frog
   - 豚鼠（5）：American, Abyssinian, Peruvian, Teddy, Silkie / Sheltie
   - 注意「Catfish / lele/patin」按 `/` 切出 `Catfish`、`lele`、`patin` 三个别名。
4. 「其他」12 组（`SpeciesGroup(code, titleId, titleEn, breeds)`，顺序照附录 B；每组末尾**自动追加**一项 `"<titleId> lainnya"`，选它时存库品种 = `titleId`）：

   | code | titleId | titleEn | 品种 |
   |---|---|---|---|
   | CIVET | Musang | Civet | Musang Pandan, Musang Bulan, Musang Akar |
   | HEDGEHOG | Landak Mini | Mini hedgehog | African Pygmy / landak mini |
   | HERMIT_CRAB | Kelomang | Hermit crab | Coenobita |
   | OTTER | Otter | Otter | Small-clawed Otter |
   | SHEEP | Domba | Sheep | Merino, Garut, Gibas |
   | COW | Sapi | Cow | Simmental, Bali |
   | SPIDER | Laba-laba/Tarantula | Spider/Tarantula | Tarantula, Jumping Spider |
   | SQUIRREL | Tupai | Squirrel | Plantain squirrel / kelapa |
   | GOAT | Kambing | Goat | Etawa, Boer, Kacang |
   | CRAB | Kepiting/Lobster | Crab/Crayfish | Land crab, Crayfish |
   | INSECT | Serangga | Insect | Ant, Jaliteng cricket / jangkrik |
   | MOUSE | Tikus & Dormouse | Mouse & dormouse | African Pygmy Dormouse, Fancy Mouse, Fancy Rat |

   「Musang lainnya」的界面文案用 l10n `petSpeciesGroupOther`（「{group} lainnya」/「Other {group}」），存库值固定 `titleId`。
5. 查询辅助：`SpeciesType typeOf(PetSpeciesCode code)`（CIVET → OTHER 那一项）、`SpeciesGroup? groupOf(PetSpeciesCode)`、`List<BreedOption> breedsFor(PetSpeciesCode code)`（「其他」组物种返回该组；OTHER 返回空，分组由抽屉处理）、`bool isPresetBreed(PetSpeciesCode code, String breed)`（逐字相等；「其他」组还认 `titleId` 本身）。
6. `pet_breeds.dart` 保留文件，改为 `export 'pet_species.dart';` + 保留 `breedsForPetType` 并转调（旧调用点编译不断）；`kCatBreeds` / `kDogBreeds` 改为新清单（Thai Bangkaew、Weimaraner 自然消失，存量值按手填显示，PRD 3.1）。

### AC2 — 匹配与手填解析 `[L0]`（D-16）
1. `String foldDiacritics(String s)`：自写映射表（à á â ã ä å ā ă ą → a；ç ć č → c；è é ê ë ē ė ę ě → e；ì í î ï ī į → i；ñ ń ň → n；ò ó ô õ ö ø ō ő → o；ù ú û ü ū ů ű ų → u；ý ÿ → y；ś š ş → s；ź ż ž → z；ł → l；ğ → g；大写同理），**不引第三方包**。
2. `String normalizeQuery(String s)` = `foldDiacritics(s.trim()).toLowerCase()`。
3. `bool speciesQueryMatches(String query, Iterable<String> candidates)`：任一候选 `normalizeQuery(c).contains(normalizeQuery(query))`；空 query → true。
4. 类型候选 = `nameId` + `nameEn` + l10n 当前语言名；品种候选 = `label` + 全部 `aliases`；「其他」组标题候选 = `titleId` + `titleEn`（命中标题 → 整组）。
5. `List<MatchRange> highlightRanges(String text, String query)`（命中段加粗用，A3）。
6. `PetSpeciesCode resolveCustomBreed(PetSpeciesCode currentType, String text)`：当前类型为 OTHER 组（`pickerType == OTHER`）时，`text` 规范化后与某组任一品种 label / 别名 / `titleId` / `"<titleId> lainnya"` **完全相等** → 该组 code；否则 `OTHER`（待拍板 Q-9 默认）。非「其他」类型 → 原 code。
7. 单测（PRD 3.2 AC1）：狗清单搜「poo」结果含「Pudel / Poodle」「Maltipoo」「Shih-poo」；搜「pudel」「POODLE」都命中「Pudel / Poodle」；鱼搜「cupang」命中「Betta / cupang」；「其他」搜「musang」命中整组；搜「é」与「e」等价（造一个含变音的测试候选）；「Musang Pandan」手填 → CIVET；「kelinci lokal」在 OTHER 下手填 → OTHER。

### AC3 — 统一显示函数 `lib/features/profile/domain/pet_species_display.dart`（新）`[L0]`（D-17）
1. `PetSpeciesView petSpeciesView({String? species, String? petType})`：`species` 能解析用它；否则按 `petType`（CAT / DOG / 其余 → OTHER）；都没有 → OTHER。
2. `PetSpeciesView`：
   - `PetSpeciesCode code`、`PetSpeciesCode pickerType`；
   - `String emoji`：按 AC1.2 表（「其他」组物种与 OTHER → 🐾）；
   - `String label(AppLocalizations l10n)`：15 项界面名；**「其他」组物种显示 `petTypeOther`（Lainnya / Other）**（PRD 3.1 AC3：宠物页显示类型「其他」）；
   - `String cardNameId`：卡面固定印尼语名（Kucing / Anjing / Kelinci / Hamster / Burung / Ayam / Ikan / Kadal / Kura-kura / Sugar Glider / Ular / Bebek/Entok / Katak / Marmut；「其他」组与 OTHER → `Hewan`，同现状兜底）；KTP / 学生卡大写由调用方 `toUpperCase()`；
   - `Color tint`：**仍按大类**（CAT `AppColors.violet100`、DOG `AppColors.goldTint`、其余 `AppColors.mintTint`），UI F1「不新增颜色」。
3. 旧函数**保留签名并转调**（别的线新增的调用点不传 species 也能编译，按大类显示，Story 4.2 再补）：
   - `petTypeDisplay(l10n, type, {String? species})`（`pet_form_fields.dart`）= `'${v.emoji} ${v.label(l10n)}'`；
   - `petSpeciesLabel(l10n, petType, {String? species})`（`pet_age.dart`）；
   - `petSpeciesEmoji(petType, {String? species})`、`petSpeciesTint(petType, {String? species})`、`PetSpeciesAvatar(..., this.species)`（`pet_species_avatar.dart`）。
4. 单测：RABBIT → 🐰 / Kelinci / Rabbit / KELINCI 卡面；CIVET → 🐾 / Lainnya / Hewan；`species=null, petType=DOG` → 🐶 Anjing；`species="UNKNOWN_NEW", petType=OTHER` → 🐾 Lainnya（新版后端加了 App 不认识的物种也不崩）。

### AC4 — l10n `[L0]`
1. `app_id.arb` / `app_en.arb` 新增 12 个类型名：`petTypeRabbit`（Kelinci / Rabbit）、`petTypeHamster`、`petTypeBird`（Burung / Bird）、`petTypeChicken`（Ayam / Chicken）、`petTypeFish`（Ikan / Fish）、`petTypeLizard`（Kadal / Lizard）、`petTypeTurtle`（Kura-kura / Turtle）、`petTypeSugarGlider`（Sugar Glider）、`petTypeSnake`（Ular / Snake）、`petTypeDuck`（Bebek/Entok / Duck）、`petTypeFrog`（Katak / Frog）、`petTypeGuineaPig`（Marmut / Guinea Pig）；12 个分组名 `petSpeciesGroupCivet` … `petSpeciesGroupMouse`（ID / EN 同 AC1.4 表）；`petSpeciesGroupOther`（`{group} lainnya` / `Other {group}`，带 placeholder）。
2. 已有 `petTypeCat/Dog/Other` 不动。改 ARB 后 `flutter gen-l10n`，**不手改生成产物**。

### AC5 — 模型加 `species` `[L0]`
以下模型 `fromJson` 读 `json['species'] as String?`（可空，旧后端 / 旧卡没有）、`copyWith` 透传：`PetProfile`（另加 `speciesChangeableFrom`，`DateTime?`，解析 `yyyy-MM-dd`）、`PetListItem`（同加 `speciesChangeableFrom`，文件 `features/profile/data/pet_list_repository.dart` 内的模型）、`VisitorProfile`、`PublicProfilePet`（`user_profile/data/public_profile_pet_repository.dart`）、`RecommendedPet`（`profile/data/pet_recommendation_repository.dart`）、`FollowedPet`、`FollowListItem`、`ContentPetRef`、`GiftCatalog` 收礼宠物、`GiftShowcase` 宠物、`BoardingPass`、`IdCard`（卡快照 `species`）、`OrderDetail`、`CelebrationPet` / `account_celebration_pets.dart`、`PetHeaderInfo`。`PetProfile.copyWith` 里「`petType` 创建后不可改，恒保留」的注释改为「`petType` / `species` 由服务端返回覆盖」。

### AC6 — 显示调用点全部改走统一函数 `[L0][L2]`
逐个改，并在 Completion Notes 列「文件:行 → 改法」清单（行号为 9680309cf）：

| 调用点 | 现状 | 改法 |
|---|---|---|
| `profile/presentation/widgets/pet_form_fields.dart:8` `petTypeDisplay` | 三值 | 转调（AC3.3）；`SpeciesField` 显示传 `species`（3.2 换抽屉） |
| `profile/domain/pet_age.dart:56` `petSpeciesLabel` | 三值 | 转调；调用方 `content_linked_pets.dart:175`、`suggested_follow_list.dart:97`、`pet_avatar_item.dart:211`、`pet_picker_sheet.dart:228`、`pet_slot_pet_row.dart:38` 传 `species` |
| `profile/presentation/widgets/pet_species_avatar.dart` | emoji / tint 三值 | 转调；调用方 `pet_avatar_item.dart:83`、`pet_picker_sheet.dart:305`、`milestone_share_card.dart:389`、`milestone_celebration.dart:620`、`pet_slot_page.dart:421`、`pet_slot_pet_row.dart:35` 传 `species` |
| `profile/presentation/widgets/pet_info_card.dart:65-101` | 自带 switch | 改调 `petSpeciesView`（UI F1：第二行「Kelinci · Holland Lop · 1 thn 2 bln」，无头像占位 🐰） |
| `profile/presentation/pet_profile_edit_page.dart:337`、`pet_profile_create_page.dart:473` | 头像占位 switch | 改调（3.2 / 3.3 会再动这两个文件的类型框，本 story 只改占位） |
| `gift/presentation/gift_panel_sheet.dart:318`、`gift/presentation/gift_page.dart:410` | switch | **只把 switch 换成函数调用**，不碰周围布局（送礼线 ② 也在改这两个文件） |
| `boarding_pass/presentation/widgets/boarding_pass_card.dart:225` | switch | 改调 |
| `social/presentation/widgets/followed_pet_row.dart:30-41` | 两个 switch | 改调 |
| `profile/presentation/widgets/recommended_pet_card.dart:214` | switch | 改调（首日线 ③ 也改推荐卡第二行，只换函数） |
| `profile/presentation/pet_card_page.dart:96,327` | `_speciesLabel` 未知值兜底成「猫」、占位写死 🐱（存量 bug） | 改调统一函数，兜底 OTHER |
| `profile/presentation/id_card/ktp_fields.dart:75` `spesies` | KUCING / ANJING / HEWAN | 改为接 `(species, petType)` 返回 `cardNameId.toUpperCase()` |
| `profile/presentation/id_card/new_card_text.dart:16` `NewCardDefaults.spesies` | Kucing / Anjing / Hewan | 同上（首字母大写原样）；调用方 `kitas_card.dart:44` 等传卡快照 `species` |
| `profile/presentation/id_card/student_card.dart:59` `_species` | 同 KTP | 同上 |
| `gift/presentation/widgets/gift_entry.dart:194` | 透传 petType | 同时透传 species |
| `me/presentation/me_page.dart:341`、`user_profile/presentation/public_profile_page.dart:774` | 透传 petType | 同时透传 species |

**明确不改（大类逻辑，Completion Notes 逐个确认）**：`ktp_fields.dart:112` `speciesCodeFor`、`tailsonality_*`、`shop_reco_grid.dart:44`、`age_card_page.dart`、`pet_insights_page.dart:203`、`milestone_celebration.dart:236,636`、`profile_created_flow.dart:38`、`vet_*_page.dart`（待拍板 Q-5）。

### AC7 — 同步守卫测试 `[L0]`
1. `test/features/profile/pet_species_backend_sync_test.dart`：读 `../petgo-backend/src/main/java/com/tailtopia/profile/domain/PetSpecies.java`，正则取枚举常量名列表，断言 = `PetSpeciesCode.values.map((e) => e.wire)`（含顺序）。
2. `test/features/profile/pet_species_export_sql_sync_test.dart`：读 `../scripts/ops/pet-species/export-push-groups.sql`，取 `cat_breeds` / `dog_breeds` 的 `VALUES ('…')` 列表，断言与 App 猫 / 狗清单 label 集合相等。本线 1.4 排在 3.1 之前，该文件必然已存在；文件不存在直接判失败（不许 `skip`）。
3. 清单条数断言：猫 39、狗 43、兔 19、仓鼠 6、鸟 17、鸡 15、鱼 11、蜥蜴 5、龟 5、蜜袋鼯 6、蛇 6、鸭 5、青蛙 7、豚鼠 5；「其他」12 组，每组品种数照附录 B；类型 15 项且 OTHER 最后。

### AC8 — 回归 `[L0][L2]`
1. `flutter analyze` 零新增告警；`flutter test` 全绿（被按设计打破的断言更新为新规则，不删测试、不 skip）。
2. L2（本地 Android 模拟器）：一只兔子（stag 造数）在 Diary 档案卡、「我的」头像排、选宠物抽屉、访客页、送礼面板、登机牌、身份证 KTP 卡面各截一张，与 UI F1 对照。

## Tasks / Subtasks

- [ ] **T0 开工核对**：对照 1.1 / 1.3 实际提交的 JSON 字段名（`species`、`speciesChangeableFrom`）；AC6 表行号未漂移
- [ ] **T1** `pet_species.dart` 清单 + `pet_breeds.dart` 兼容（AC1）
- [ ] **T2** 匹配 / 高亮 / 手填解析（AC2）
- [ ] **T3** `pet_species_display.dart` + 旧函数转调（AC3）
- [ ] **T4** l10n（AC4）
- [ ] **T5** 模型字段（AC5）
- [ ] **T6** 调用点（AC6）
- [ ] **T7** 守卫测试（AC7）
- [ ] **T8** `flutter analyze` + `flutter test`；Completion Notes 标「L1 / L2 待本地验收」

## Dev Notes

- **不要对整个文件跑 `dart format`**（存量代码未统一格式化，会带出几百行无关 diff）；手工对齐缩进，只跑 `flutter analyze`。
- App 不渲染后端显示串：类型名一律按 code 在 App 本地化（项目约定），后端只给 `species` code。
- 卡面物种是印尼语设计常量，不随界面语言变（现有 `spesies` 注释写明）。
- 合并最后：送礼线 ② 改 `gift_panel_sheet.dart` 布局、首日线 ③ 改推荐卡第二行、健康线 ① 改编辑页。本 story 在这些文件里**只换函数调用一行**，Story 4.2 合并时只需保留两边改动。
- 头像底色按大类是 UI 稿明确要求，别给 15 种各配色。

### 测试
- L0：`pet_species_test.dart`（清单、辅助函数）、`pet_species_match_test.dart`（AC2.7）、`pet_species_display_test.dart`（AC3.4）、两个同步守卫、受影响 widget 测试更新（`pet_info_card_test`、`followed_pet_row_test` 等现有测试补一条兔子用例）。
- L2：AC8.2。

## Dev Agent Record

### Agent Model Used

### Completion Notes List

### File List
