# Story 2.2: 测试内容表（EN / ID）与合规扫描

Status: review

## Story

As a 印尼或英语用户,
I want 题目和结果都用我的语言、读起来自然,
so that 测试不像机翻、愿意做完并分享。

## Acceptance Criteria

**AC1 — 内容表落地（Dart 常量，随 App 打包）** `[L0]`
1. 新建 `petgo_app/lib/features/tailsonality/domain/content/` 下一组 Dart 常量文件（结构与键方案见 Dev Notes「内容表结构」），**不进 ARB**（与 `profile/domain/milestone_titles.dart` 同一路线：大块内容走 Dart 常量表，ARB 只放界面 chrome）。
2. 必须包含以下全部块，条数一条不能少（清单见 Dev Notes「翻译清单」）：三套题干与选项（含 3 道图片题的**文字标签**，与图成对）、16 角色名与 slogan（**Jaksel 原文，三语共用、不翻译**）、16 段免费摘要、8 段维度解读、16 段角色专属深读、2 段能量段（+ 能量标签）、5 档名 + 5 档总评 + 5 档总结句、8 条差异句、16 段逐轴详解、挽留弹窗文案、重测确认文案。
3. 能量后缀展示词固定为 EN「High energy / Low energy」、ID「Energi tinggi / Energi rendah」（内容设计 §4.2，逐字）。
4. 内容设计里**已给出 EN / ID 的条目一律逐字照搬**（能量标签、5 档名 / 总评 / 总结句、8 条差异句、挽留弹窗、重测确认），不得「顺手润色」。

**AC2 — 翻译质量** `[L0]`（L0 只钉可机检部分；语感复核列入发版检查单 RC-5）
1. 其余中文内容由 dev agent 依内容设计中文版翻成 EN / ID（D-8），口吻对齐内容设计已给出的 EN / ID 样例与本文件「口吻样例」（年轻、口语；ID 用 `kamu` / `dia`，可混 Jaksel 常见词，不用 `Anda`）。
2. 中文原文的「你家猫 / 你家狗 / 你家宝贝 / 它」在题干与解读里按语义落成 `{pet}` 占位或代词；`{pet}` 是**唯一**允许的占位符。
3. 中文原文的 `**加粗**` 保留为 `**…**` 标记（展示层解析，见 Story 2.4 / 2.5）。
4. 机检（`test/tailsonality/content_tables_test.dart`）：
   - 每条 EN / ID 非空、**不含任何中日韩字符**（抓「复制了中文忘了翻」）；
   - 可翻译字段 `en != id`（抓「两栏贴了同一句」；角色名 / slogan 不是 `TsText`，不受此条约束）；
   - 花括号占位只出现 `{pet}`；
   - 题目：键集合恰为 `{CAT,DOG,GENERAL} × {Q1..Q15,P1..P3}` 共 54 个，每题恰 4 个选项；`P1..P3` 带 `imageGroup`，`Q*` 不带；
   - 角色 16 个、键为 16 个四字母代号；维度键 `E I N S T F J P`；能量键 `H L`；档位键 `0..4`；差异句 8 键、逐轴详解 16 键（键方案见 Dev Notes）；
   - AC1.3 / AC1.4 的逐字条目用断言钉几条代表值（如 `kTsMatchTiers[4]!.name.id == 'Literally Twins'`、`kTsEnergy['L']!.label.en == 'Low energy'`）。

**AC3 — 跨库一致性测试** `[L0]`
后端新测试 `petgo-backend/src/test/java/com/tailtopia/tailsonality/TailsonalityContentParityTest.java`（照 `profile/domain/MilestoneCatalogI18nTest.java`：后端读 `../petgo_app/...` 的 Dart 源码，**找不到文件明确失败，绝不静默跳过**）：
1. 从 `ts_questions.dart` 用正则抽出全部 `'<SET>.<QID>'` 键 = `TailsonalityCatalog`（Story 2.1）的「3 套 × `QUESTION_IDS`」集合，一个不多一个不少。
2. 从 `ts_roles.dart` 抽出全部角色键 = `TailsonalityCatalog.TYPE_CODES`（16 个）。
3. 后端侧再断言 Catalog 每题权重恰 4 个（选项数两端一致：App 侧由 AC2.4 钉 4 选项，后端由本条钉 4 权重）。

**AC4 — 商标词静态扫描** `[L0]`
1. App 测试 `test/tailsonality/trademark_scan_test.dart`：以下范围**不出现 `mbti`（不区分大小写）**——`lib/` 下全部 `.dart`（含内容表、`api_paths.dart`、全部 `Analytics.capture` 事件名与属性键）、`lib/l10n/*.arb`、`assets/` 下全部文件与目录**名**（递归）。
2. 后端测试 `TrademarkScanTest`：`src/main/java/**` 与 `src/main/resources/**` 下文本文件（`.java .sql .yml .yaml .properties .html .json .xml .js .css .txt`）内容，及两处全部文件名，不出现 `mbti`（不区分大小写）——覆盖 API 路径、DTO 字段、迁移、`AnalyticsEventGuard` 白名单。
3. 两条测试**扫全文含注释**（比只扫代码更简单也更严）；现状两端零命中（2026-09-29 已 grep 核实），测试上线即绿。注释里需要提及时写「四字母商标词」。
4. **16Personalities 角色别名扫描**（App，同一测试文件）：`lib/features/tailsonality/**` 全部文件 + ARB 中 `tailsonality*` 键的值，按**整词、不区分大小写**匹配下列别名，零命中：
   - EN（必扫）：Architect、Logician、Commander、Debater、Advocate、Mediator、Protagonist、Campaigner、Logistician、Defender、Executive、Consul、Virtuoso、Adventurer、Entrepreneur、Entertainer
   - ID（同扫，整词匹配避免误伤 `petualangan` 这类派生词）：Arsitek、Ahli Logika、Komandan、Pendebat、Advokat、Protagonis、Juru Kampanye、Ahli Logistik、Pembela、Eksekutif、Konsul、Petualang、Pengusaha、Penghibur（开工时对照 16personalities.com/id 的实际译名核一遍，有出入以站点为准并在 Completion Notes 记录）
   - 翻译时撞到这些词（如 ENTJ 的深读里想写 "commander"）→ 换说法，不许加白名单。

## Tasks / Subtasks

- [x] **T1 内容表骨架**（AC1）
  - [x] `content/ts_text.dart`：`typedef TsText = ({String en, String id});` + `extension on TsText { String of(Locale l) }`（`languageCode == 'id'` 取 id，其余取 en）+ `String tsFillPet(String s, String petName)`
  - [x] `content/ts_questions.dart`：`TsQuestion` 类 + `kTsQuestions`（54 键）；三道图片题定义为 `_kP1/_kP2/_kP3` 三个常量，三套各自以 `'CAT.P1': _kP1,` 引用（题干与选项三套共用，内容设计 §6.5）
  - [x] `content/ts_roles.dart`：`TsRole` + `kTsRoles`（16 键）
  - [x] `content/ts_readings.dart`：`kTsDimensionReadings`（8 键）、`kTsEnergy`（H / L，各含 `label` 与 `line`）
  - [x] `content/ts_match_copy.dart`：`TsMatchTier` + `kTsMatchTiers`（键 = 相同字母数 4..0）、`kTsAxisDiffLines`（8 键）、`kTsAxisDetails`（16 键）
  - [x] `content/ts_dialog_copy.dart`：`kTsRetentionDialog`、`kTsRetakeDialog`（各含 title / body / confirm / cancel 四个 `TsText`）
- [x] **T2 翻译落地**（AC1、AC2）：按「翻译清单」逐块填；先填逐字照搬块，再翻其余块；每块翻完跑一次 T4 的机检
- [x] **T3 跨库测试**（AC3）：`TailsonalityContentParityTest`（依赖 Story 2.1 的 `TailsonalityCatalog`）
- [x] **T4 App 机检测试**（AC2.4）：`test/tailsonality/content_tables_test.dart`
- [x] **T5 商标词 / 别名扫描**（AC4）：App `test/tailsonality/trademark_scan_test.dart`、后端 `src/test/java/com/tailtopia/tailsonality/TrademarkScanTest.java`
- [x] **T6** `flutter analyze` + `flutter test test/tailsonality/` + `./mvnw -B test -Dtest='Tailsonality*,Trademark*'` 全绿

## Dev Notes

> ⚠️ 前置 story 尚未实现：开工前先对照其实际代码核对本文件引用的类名/接口/字段，有出入先改本文件。（AC3 依赖 Story 2.1 的 `TailsonalityCatalog.QUESTION_IDS` / `TYPE_CODES` / 题套枚举名；若 2.1 实际命名不同，以代码为准改本 story 的正则与断言。）

### 必读：会被本 story 改到 / 参照的现有代码

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `petgo_app/lib/features/profile/domain/milestone_titles.dart` | `const Map<String, ({String en, String id})> kMilestoneTitles`，一行一条 `'C-S1': (en: '…', id: '…'),`，后端 `MilestoneCatalogI18nTest` 用正则跨库读它 | **不改**，只作格式先例：本 story 的内容表同样用 record `({String en, String id})`，且**键写成单引号字面量、一行起头**，让后端正则能抽 | — |
| `petgo-backend/src/test/java/com/tailtopia/profile/domain/MilestoneCatalogI18nTest.java` | L33-38：`Path.of("..", "petgo_app", …)` + `Pattern`；找不到文件即失败 | 不改，照抄其读文件与失败方式 | — |
| `petgo_app/test/l10n/microcopy_rules_test.dart` | 只扫 ARB：两语 key 对齐、每串 ≤1 emoji、en 值与 id 值相同时逐词查 `_sameInBothLocales`（L120-141） | **不改**。内容表不在其扫描范围，所以本 story 自带 AC2.4 机检 | 该测试对 ARB 的全部规则 |

### 内容表结构（键方案是跨库契约，别改形状）

```dart
// ts_questions.dart —— 键 = '<SET>.<QID>'，SET ∈ CAT|DOG|GENERAL，QID ∈ Q1..Q15|P1..P3
class TsQuestion {
  const TsQuestion({required this.stem, required this.options, this.imageGroup});
  final TsText stem;
  final List<TsText> options;   // 恰 4 个；下标 = 提交给后端的原始序号 0..3（0 = +2）
  final String? imageGroup;     // 仅 P1..P3：'p1'|'p2'|'p3' → assets/tailsonality/quiz_<group>_<i>.webp（素材未到，Story 2.3 渲染占位）
}
const Map<String, TsQuestion> kTsQuestions = {
  'CAT.Q1': TsQuestion(stem: (en: '…', id: '…'), options: [ (en: '…', id: '…'), … ]),
  …
  'CAT.P1': _kP1,
  …
};

// ts_roles.dart —— 键 = 四字母代号
class TsRole {
  const TsRole({required this.name, required this.slogan, required this.summary, required this.deepRead});
  final String name;      // Jaksel 原文，不翻译
  final String slogan;    // Jaksel 原文，不翻译（不带内容设计里的斜体星号）
  final TsText summary;   // 免费摘要（§5.3）
  final TsText deepRead;  // 角色专属深读（§5.5，付费，Story 3.2 展示）
}

// ts_readings.dart
const Map<String, TsText> kTsDimensionReadings = { 'E': …, 'I': …, 'N': …, 'S': …, 'T': …, 'F': …, 'J': …, 'P': … };
const Map<String, ({TsText label, TsText line})> kTsEnergy = { 'H': …, 'L': … };

// ts_match_copy.dart —— 相同字母数只比四字母，不比能量后缀
class TsMatchTier { final TsText name; final TsText review; final TsText summary; final String? slogan; … }
const Map<int, TsMatchTier> kTsMatchTiers = { 4: …, 3: …, 2: …, 1: …, 0: … };
// 差异句 / 逐轴详解的键 = 主人字母 + 宠物字母（与内容设计表头「你 X · 它 Y」同序）
const Map<String, TsText> kTsAxisDiffLines = { 'EI': …, 'IE': …, 'NS': …, 'SN': …, 'TF': …, 'FT': …, 'JP': …, 'PJ': … };
const Map<String, TsText> kTsAxisDetails   = { 'EE','EI','IE','II','NN','NS','SN','SS','TT','TF','FT','FF','JJ','JP','PJ','PP' → … };
```

- 后端 AC3 正则建议：题目键 `'(CAT|DOG|GENERAL)\.(Q(?:1[0-5]|[1-9])|P[1-3])'\s*:`；角色键 `'([EI][NS][TF][JP])'\s*:\s*TsRole\(`。
- `TsMatchTier.slogan`：设计资产清单 §3 新写了 5 条档位 slogan（Jaksel 原文，配型分享卡用，Story 4.2 消费），**本 story 一并录入**（不翻译）；内容设计 §4.3 尚未回写，权威源待定——录入时注释标来源。
- 选项顺序：**按内容设计原序（+2 → −2）存**，下标即提交值；App 本版本不打乱（Story 2.3）。

### 翻译清单（块 × 条数 × 处理方式）

| # | 块 | 来源（内容设计） | 条数 | 处理 |
|---|---|---|---|---|
| 1 | 行为题题干 | §6.2 猫 / §6.3 狗 / §6.4 通用，Q1–Q15 | 45 | **翻译** |
| 2 | 行为题选项 | 同上 | 180 | **翻译**（短：选项行 ≤ ~40 字符，热区 44px 下两行内放得下） |
| 3 | 图片题题干 | §6.5 P1–P3（三套共用） | 3 | **翻译** |
| 4 | 图片题文字标签 | §6.5（门口守着 / 客厅正中 / 角落 / 藏身处里 …） | 12 | **翻译**（≤ ~20 字符，图下单行；与图同一语义，设计资产清单 §6） |
| 5 | 角色名 | §4.1 / §5.2 | 16 | 照搬，不翻译 |
| 6 | Slogan | §5.2 | 16 | 照搬，不翻译 |
| 7 | 免费摘要 | §5.3 | 16 | **翻译** |
| 8 | 维度解读 | §5.4 | 8 | **翻译** |
| 9 | 角色专属深读 | §5.5 | 16 | **翻译** |
| 10 | 能量标签 | §4.2 | 2 | 照搬 EN/ID |
| 11 | 能量段正文 | §4.2「一句话」列（见下方矛盾说明） | 2 | **翻译** |
| 12 | 5 档名 + 总评 | 档名按设计图（D-18）；总评 §4.3 占位 | 5 + 5 | 档名照搬、总评占位待定稿 |
| 13 | 5 档总结句 | §4.3「5 档免费总结句」 | 5 | 照搬 EN/ID |
| 14 | 5 档 slogan | 设计资产清单 §3 | 5 | 照搬，不翻译 |
| 15 | 8 条差异句 | §4.3「8 条轴差异句」 | 8 | 照搬 EN/ID |
| 16 | 16 段逐轴详解 | §4.3「逐轴详细解读」 | 16 | **翻译** |
| 17 | 挽留弹窗 | §2.3（标题 / 正文 / 两按钮） | 4 | 照搬 EN/ID |
| 18 | 重测确认 | §2.6 正文 + 标题 / 按钮 | 4 | 正文照搬 EN/ID；标题 ID「Tes ulang?」EN「Retake the test?」；按钮 ID「Batal / Tes Ulang」EN「Cancel / Retake」 |

合计需翻译：48 题干 + 192 选项/标签 + 16 + 8 + 16 + 2 + 16 = **298 条 × EN/ID**。

### 口吻指南

- **EN**：口语、短句、第二人称 `you`；宠物用 `{pet}` 或 `it`（内容设计 EN 样例一律用 `it`）；不用感叹号堆砌、不加 emoji；保留原文的「转折 punchline」节奏（样例：*"You're in your feelings, it's asleep."*）。
- **ID**：Jakarta 口语，`kamu` / `dia`，`nggak / udah / aja / banget / sih` 自然使用；可混已高度普及的 Jaksel 词（literally、which is、justru、baper、santuy、mager、julid、vibes），**不生造词、不整句英文**；样例：*"Kamu lagi baper, dia lagi tidur."*、*"Setengah kamu, setengah dia sendiri. Cukup buat saling ngerti, cukup juga buat saling julid."*
- 题干：观察式问法，不扮演宠物；UI 稿 A4 的题干写法可参考句式（「Waktu ada tamu masuk rumah, {pet}…」），但**题意一律以内容设计为准**（见下方矛盾说明）。
- 全部正面或自嘲式可爱，不写贬低宠物的词（内容设计 §4.1 / §4.3）。

### 口吻样例（定调用，照这个语感翻其余条目）

| 块 · 键 | 中文原文 | EN | ID |
|---|---|---|---|
| 题目 `CAT.Q1` 题干 | 陌生人进门时，你家猫第一反应是？ | A stranger walks in. {pet}'s first move? | Ada orang asing masuk rumah, reaksi pertama {pet}… |
| `CAT.Q1` 选项 0..3 | 主动凑过去闻/蹭 · 远远看着，一会儿才靠近 · 躲到沙发底下，人走了才出来 · 直接消失，全程没露面 | Goes right up to sniff and rub · Watches from afar, comes closer later · Hides under the sofa till they leave · Vanishes — no show the whole visit | Langsung nyamperin, endus-endus · Ngeliatin dari jauh, lama-lama baru deketin · Ngumpet di kolong sofa sampai orangnya pulang · Langsung hilang, nggak nongol sama sekali |
| 摘要 `ENTJ` | {pet} 不只是精力旺，它有计划——全场哪个位置视野最好、几点闹你最有效，它都清楚。 | {pet} isn't just full of energy — it has a plan. The best view in the house, the exact hour bugging you works best: it knows. | {pet} bukan cuma aktif — dia punya rencana. Posisi paling strategis di rumah, jam berapa paling ampuh buat ngerecokin kamu, dia hafal semua. |
| 维度 `T` | 很少有事能真正惊动 {pet}。巨响、访客、新环境，它抬个头就过去了。……它不是无动于衷，只是不表演。它表达在乎的方式，比你以为的安静。 | Not much really rattles {pet}. Loud bangs, guests, new places — one look up and it's over. … It isn't indifferent; it just doesn't perform. The way it shows it cares is quieter than you think. | Jarang ada yang bisa bikin {pet} kaget beneran. Suara keras, tamu, tempat baru — dia cuma angkat kepala bentar, terus lanjut lagi. … Dia bukan cuek, cuma nggak suka drama. Cara dia nunjukin sayang lebih kalem dari yang kamu kira. |
| 逐轴详解 `EI`（你 E · 它 I） | 你想出门，它想回家。你带朋友回家是社交，对它是入侵。**它躲起来不是不爱你，是在给自己充电。**…… | You want to go out, it wants to go home. Bringing friends over is socializing for you — for it, it's an invasion. **It's not hiding because it doesn't love you; it's recharging.** … | Kamu pengen keluar, dia pengen pulang. Buat kamu, ngajak temen ke rumah itu sosialisasi — buat dia, itu invasi. **Dia ngumpet bukan karena nggak sayang, tapi lagi ngecas.** … |

（`…` 处按原文补全，样例只定语感；UI 稿 A8 的摘要示意「Momo bukan cuma aktif — dia punya rencana…」与上表同一口吻。）

### 已知文档矛盾（本 story 的处理）

- **UI 稿 A4 / A5 的题目与内容设计不符**：A4 第 3 题「Kalau ada suara aneh di luar」、A5 第 4 题「Ketemu kucing/anjing lain」、第 5 题逗猫棒（内容设计里是 Q12）、第 6 题图片选项「Di kasur / Di jendela / Di dalam kotak / Di bawah sofa」（内容设计 P1 是「门口 / 客厅正中 / 角落 / 藏身处」）。**以内容设计为准**（口径优先级），UI 稿只借句式。
- **能量段正文缺源**：内容设计 §5.1 说「能量后缀段 2，见 §3.2」，但 §3.2 没有这两段；唯一的能量文案是 §4.2「一句话」列（电量永远满格，睡醒就是开跑 / 能躺着绝不坐着，能明天绝不今天）。本 story 把这两句作为 `kTsEnergy[*].line` 翻译落地（UI 稿 A9 的 ID 示意「Baterai selalu penuh — bangun tidur langsung ngacir.」可直接采用）；**2026-09-29 决策 D-19：正文由产品后续提供**——先用这两句占位，内容表该块加注释 `// PENDING D-19: 能量段正文待提供`。
- **重测确认正文**：epics 2.4 AC 引用的是 UI 稿短版（「Hasil barunya perlu di-unlock lagi. Yang udah kamu unlock tetap tersimpan.」），内容设计 §2.6 有完整版。**以内容设计完整版为准**。
- **配型档位名（2026-09-29 决策 D-18：以设计图为准）**：4/4→0/4 依次为 **Literally Twins / Twin Flames / Backs Together / Counterweight / Magnetic Poles**，EN / ID 同名（专名不译）。档位总评、总结句、卡面标语**具体文案仍在讨论、后续会改**：本 story 先落内容设计 §4.3 的现有总评 / 总结句作占位，并在内容表该块加注释 `// PENDING D-18: 文案待定稿`，定稿后只改这一块。

### 验证层级

L0：AC1–AC4 全部（纯静态：`flutter test`、`./mvnw -B test` 的非 DB 测试）· 语感复核属发版检查单 RC-5（印尼语母语同事过一遍），不在本 story 验收内

### Project Structure Notes

- 内容表全部在 `lib/features/tailsonality/domain/content/`，展示层只通过这些常量取文案；**不得**在页面文件里再写题目 / 解读字面量。
- 后端测试放 `src/test/java/com/tailtopia/tailsonality/`；它依赖相对路径 `../petgo_app/`，与 `MilestoneCatalogI18nTest` 同一约束（Maven 工作目录 = `petgo-backend/`）。
- 付费文案随包下发可被解包读到——AD-2 已接受的风险，本 story 不做混淆。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.2/epics-v1.3.2-batch-a.md#Story 2.2]
- [Source: _bmad-output/planning-artifacts/v1.3.2/architecture-v1.3.2-batch-a-delta.md#AD-2, AD-20]
- [Source: _bmad-output/planning-artifacts/v1.3.2/tailsonality-内容设计.md#2.3, 2.6, 4.1–4.3, 5.1–5.5, 6.2–6.5]
- [Source: _bmad-output/planning-artifacts/v1.3.2/设计资产清单.md#3 配型卡卡面（5 档 slogan）, 6 图片题素材]
- [Source: _bmad-output/planning-artifacts/v1.3.2/决策日志-batch-a.md#D-8, 设计素材状态]
- [Source: _bmad-output/planning-artifacts/v1.3.2/PRD-v1.3.2-batch-a.md#3.2（合规红线、图片题图文成对）]
- [Source: _bmad-output/planning-artifacts/v1.3.2/ui-v1.3.2-batch-a.html#A4, A5, A8, A9（仅句式参考）]

## Dev Agent Record

### Agent Model Used

Claude Code 云端 session（headless，环境 tailtopia-L0）

### Debug Log References

- 后端 L0（`clean package -DskipTests` + 排除 L1 单测，`LC_ALL=C.UTF-8`）：**2770 例，0 失败 0 错误**（新增 `TailsonalityContentParityTest`、`TrademarkScanTest`，均 L0）。
- 前端：`flutter analyze` 零问题；`flutter test` 全绿（`test/tailsonality/` 新增 23 例）。
- 本 story 无迁移；`check-flyway-versions.sh origin/main` → OK。

### Completion Notes List

- **L1/L2 待本地验收**：本 story 全部 L0，无 L1 / L2；**语感复核**属发版检查单 RC-5（印尼语母语同事过一遍全部 ID 译文，重点：题干 / 选项口语度、深读长段）。
- **开工核对**：Story 2.1 实际命名与本文件一致（`TailsonalityCatalog.QUESTION_IDS` / `TYPE_CODES` / `WEIGHTS`、`TailsonalityQuestionSet { CAT, DOG, GENERAL }`），AC3 正则按 Dev Notes 建议写法直接可用，story 文件无需改名。
- **实现要点**：
  - 内容表 6 个文件放 `lib/features/tailsonality/domain/content/`；字符串一律双引号（译文多撇号），键仍为单引号字面量、一行起头，后端正则可抽。
  - 翻译清单 18 块全部落地：54 题（含 3 道图片题共用 `_kP1/_kP2/_kP3`，三套以 `identical` 钉住共用）、16 角色（名 / slogan 照搬，slogan 去掉斜体星号）、16 摘要、8 维度、16 深读、能量 2 × 2、5 档（档名按 D-18、总评 / 总结句照搬 §4.3、slogan 取自设计资产清单 §3）、8 差异句、16 逐轴详解、两个弹窗。
  - 逐字照搬块与原文逐条核对（复审也核过一遍）；口吻样例里给出的 EN / ID（`CAT.Q1`、`ENTJ` 摘要、维度 `T`、详解 `EI`）照用并按原文补全 `…` 部分。
  - `// PENDING D-18`（档位总评 / 总结句 / slogan）、`// PENDING D-19`（能量段正文，暂用 §4.2「一句话」）已加。
- **偏差（保守取舍，已记录）**：
  - AC2.4「可翻译字段 `en != id`」对两类条目豁免并在测试里写明原因：配型档位名（D-18：专名不译，EN / ID 同名）、挽留弹窗确认按钮「Unlock」（内容设计 §2.3 原文两语同词）。
  - 图片题文字标签按 ≤ ~20 字符收短（如「Middle of the living room」→「Center of the room」、「深夜 / 凌晨」→「Late night / Tengah malam」），语义与计分梯度不变。
  - 别名扫描 ID 列表在 story 所列 14 个之外补了 `Mediator`、`Virtuoso`（推测站点印尼语版对这两个不译）；云端未能对照 16personalities.com/id 核实实际译名。
- **复审（code-review）CONFIRMED 三条，均已修**：① INTJ 深读 EN 把「它不亲人，但它亲你」译成「it is your pet」，改为「It isn't cuddly with people, but it's close to you」；② ISTP 深读 ID 把「淡定、随性」译成「santai, santuy」（同义重复），随性改为「ngalir aja」；③ 中日韩检测正则漏假名 / 谚文 / 扩展区汉字，改为 `\p{Script=…}` 并加自检用例。
- 无被按设计打破的既有测试。
- **待确认**：① 16personalities 印尼语站点的实际别名译名（本地联网后核一遍，如有新词补进 `trademark_scan_test.dart`）；② 图片题标签收短的措辞；③ 全部 ID 译文语感（RC-5）。

### File List

App（新增）
- `petgo_app/lib/features/tailsonality/domain/content/ts_text.dart`
- `petgo_app/lib/features/tailsonality/domain/content/ts_questions.dart`
- `petgo_app/lib/features/tailsonality/domain/content/ts_roles.dart`
- `petgo_app/lib/features/tailsonality/domain/content/ts_readings.dart`
- `petgo_app/lib/features/tailsonality/domain/content/ts_match_copy.dart`
- `petgo_app/lib/features/tailsonality/domain/content/ts_dialog_copy.dart`
- `petgo_app/test/tailsonality/content_tables_test.dart`
- `petgo_app/test/tailsonality/trademark_scan_test.dart`

后端（新增）
- `petgo-backend/src/test/java/com/tailtopia/tailsonality/TailsonalityContentParityTest.java`
- `petgo-backend/src/test/java/com/tailtopia/tailsonality/TrademarkScanTest.java`

### Change Log

- 2026-09-30：Story 2.2 实现（Tailsonality 内容表 EN / ID 全量 + 跨库一致性 + 商标词 / 别名扫描）；复审三条已修；L0 双绿，置 review。
