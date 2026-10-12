# Story 1.3: SIM 与居住证卡面，护照日期改为建卡日

Status: ready-for-dev

> 版本 v1.3.4 · 主题 ktp · 分支 `feat/1.3.4-ktp-revamp`
> 口径优先级：`决策日志-ktp.md` > `PRD-v1.3.4-ktp.md` > `architecture-v1.3.4-ktp-delta.md` > `ui-v1.3.4-ktp.html`（均在 `_bmad-output/planning-artifacts/v1.3.4/`）
> 前置：Story 1.1（后端可建 `SIM` / `KITAS`）、Story 1.2（注册表 `IdCardStyle` 已建，`sim` / `kitas` 两项卡面为占位）。本 story **只替换这两项的占位卡面**，并修护照日期；纯 App，不动后端。

## Story

As a 用户,
I want 做一张宠物版 SIM 驾照或居住证，护照卡日期固定,
so that 我有新卡可做，旧卡隔天看也不会变日期。

## Acceptance Criteria

**AC1 — 编号格式化函数** `[L0]`（FR-142E.7，AD-7）
1. 新文件 `petgo_app/lib/features/profile/presentation/id_card/id_card_number_format.dart`，两个纯函数：
   - `String formatSimCardNo(String cardNo)` → `TTDDMM-YYYY-SPXXXX`；
   - `String formatKitasCardNo(String cardNo)` → `TT-DDMMYYSPXXXX`。
2. 输入为 14 位身份码 `TT + DDMMYY + SP + XXXX`（DD 已含性别加码，见 `ktp_fields.dart:117-129`，格式化**不解码、不改数字**，只重排与加分隔符）。SIM 的年份由 `card_no` 内的两位 `YY` 展开为 4 位（展开规则见 Completion Notes「待确认 1」，默认按 `20YY`）。
3. 单测（新文件 `petgo_app/test/profile/id_card_number_format_test.dart`）至少覆盖：
   - `TT230326020110` → SIM `TT2303-2026-020110`、KITAS `TT-230326020110`（epics 示例）；
   - PRD 示例：`TT130326020110` → SIM `TT1303-2026-020110`；`TT030326020110` → KITAS `TT-030326020110`；
   - 预览占位号（末四位 `0000`，如 `buildPreviewCardNo` 产出的 `TT600324020000`）照常格式化；
   - 不符合 `^TT\d{12}$` 的输入 → **原样返回**、不抛异常（防御，不在卡面上崩）。
4. 格式化**只用于 SIM / 居住证卡面**（含详情预览、HD 导出图、分享图——三者是同一个卡面组件）。Kartuku 列表（`id_card_page.dart:117-121`）与付费抽屉（`hd_paywall_sheet.dart:28-30`）**不调用**，继续显示原样 `card_no`。

**AC2 — SIM 卡面（UI 稿 F4）** `[L0]` `[L2]`
1. 新文件 `petgo_app/lib/features/profile/presentation/id_card/sim_card.dart`：`SimFields` + 纯函数 `SimFields buildSimFields(IdCardData data)` + `SimCardFront`；画布复用 `kIdCardCanvas`（1988×1200，`ktp_card.dart:8`），圆角常量 `kSimCardCanvasRadius`（按底图实测，见 Task 1）。
2. 素材入库 `petgo_app/assets/id_card/sim/`：底图 `Driving.png`（无字底图 994×600）、`Logo.png`、`Sign.png`（爪印签章；与现有 `assets/ktp/ktp_paw_stamp.png` 逐字节相同，可直接复用后者，二选一并在 Completion Notes 写明）；`pubspec.yaml` 的 `flutter/assets` 登记 `assets/id_card/sim/`。
3. 叠字（文字内容按 UI 稿 F4，位置 / 字号按下表起步，最终以与 UI 稿 F4 叠图逐像素校准为准）：

   | 元素 | 内容 | UI 稿位置（卡宽 / 卡高百分比） | 字体（Rubik） | 颜色 |
   |---|---|---|---|---|
   | Logo | `Logo.png` | left 4.2% · top 5.8% · 宽 11.8% | — | — |
   | 主标题 | `TAILTOPIA` | 18.3% · 3.5% | Bold + Italic，6.2cqw | 白 |
   | 副标题 | `PET DRIVING LICENSE` | 64.5% · 7.8% | Italic，2.6cqw | 白 |
   | 抬头 | `SURAT IZIN MENGEMUDI HEWAN` | 18.3% · 18.5% | Medium，2.7cqw | `#1E1B1B` |
   | 车型位 | `T`（PRD：去掉车型代码改为字母 T） | 89.5% · 17% | ExtraBold(800)，6.6cqw | `#111111` |
   | 照片 | 快照 `avatarUrl` | 4% · 31.5% · 宽 22.6% · 高 45.5% | — | — |
   | 字段列（标签 / 值交替，行距 5.35%） | Nama / Tempat, Tanggal Lahir / Jenis Kelamin / Alamat / Pekerjaan | 起点 30% · 34.5% | 标签 Regular、值 Bold，2.25cqw | `#1E1B1B` |
   | 编号 | `formatSimCardNo(card_no)` | 70.5% · 34.5% | Bold，2.3cqw，等宽数字 | `#1E1B1B` |
   | 签章 | `Sign.png` | 75.5% · 61% · 宽 15.6% | — | — |
   | 签发方 | 标签 `Diterbitkan Oleh` / 值 `Tailtopia` | 5.2% · 85% / 90.3% | Regular / Bold，2.25cqw | `#1E1B1B` |
   | 签发日期 | 建卡日 `DD-MM-YYYY` | 76% · 90.3% | Bold，2.25cqw，等宽数字 | `#1E1B1B` |

   换算：画布坐标 = 百分比 × 1988（横）/ × 1200（纵）；`cqw` 字号 = 数值 × 19.88 px（如 2.25cqw ≈ 44.7）。UI 稿的 `top` 是文字盒顶而非基线，按 `ktp_card.dart` / `passport_card.dart` 的 `Baseline` 定位法换算并校准。
4. 字段取值（`buildSimFields`）：
   - Nama ← `name`；Tempat, Tanggal Lahir ← `birthCity` + `, ` + 生日 `DD-MM-YYYY`；Jenis Kelamin ← `gender`（MALE / FEMALE / UNKNOWN → Jantan / Betina / `-`，与 `KtpDefaults.jenisKelaminFor` 同义）；Alamat ← `address`；Pekerjaan ← `occupation`；
   - 选填（出生城市 / 地址 / 职业）留空 → 用现有趣味默认值（同 `KtpDefaults` 的 BANDUNG / JL. MELATI NO. 25, BANDUNG / CHIEF HAPPINESS OFFICER 语义，FR-142D.5）；大小写见 Completion Notes「待确认 3」；
   - 签发日期 = 卡的 `createdAt`（AC4 补传），按设备本地日期（`toLocal()`，与 Kartuku「Dibuat {日期}」`id_card_page.dart:162` 同口径）；`createdAt` 为 null（建卡页预览，尚未落库）→ 取当天 `DateTime.now()`；
   - 编号：`card_no` 非空 → `formatSimCardNo`；为空 → 见 Completion Notes「待确认 2」。
5. 卡面**不出现** `serial_id` 及其派生值（FR-142E.9）；**不使用**概念图 `16-style-sim.png` 上的示例号码 / 字段值（`TT1301-1231-123132`、`Jl.Harapan`、`Tukang Jaga Uang`、`01-01-2022` 等）。

**AC3 — 居住证卡面（UI 稿 F3）** `[L0]` `[L2]`
1. 新文件 `petgo_app/lib/features/profile/presentation/id_card/kitas_card.dart`：`KitasFields` + `KitasFields buildKitasFields(IdCardData data)` + `KitasCardFront`；画布 `kIdCardCanvas`，圆角常量 `kKitasCardCanvasRadius`（按底图实测）。
2. 素材入库 `petgo_app/assets/id_card/kitas/`：`Background.png`（**原图 1008×604，入库前按 alpha 实测裁到卡片本体 994×600**，同 AD-7 对 student01 / 03 的处理）、`Label.png`（抬头带，994×146）、`Logo.png`（右上 TailTopia 标）、`Sign.png`（爪印签章 177×163）；`pubspec.yaml` 登记 `assets/id_card/kitas/`。
3. 叠字（UI 稿 F3）：

   | 元素 | 内容 | UI 稿位置 | 字体（Rubik） | 颜色 |
   |---|---|---|---|---|
   | 抬头带 | `Label.png` | left 0 · top 4.2% · 宽 100% | — | — |
   | 抬头第一行 | `Pengurus Administrasi Hewan` | 水平居中 · 9.5% | Medium，2.9cqw | 白 |
   | 抬头第二行 | `TAILTOPIA` | 水平居中 · 18% | Bold，2.9cqw | `#4B33A8` |
   | 右上 Logo | `Logo.png` | 85.5% · 9.8% · 宽 8% | — | — |
   | 照片 | 快照 `avatarUrl` | 4% · 37.5% · 宽 22.6% · 高 45% | — | — |
   | 字段列（行距 5.35%） | Nama / Spesies / Ras / Tanggal Lahir / Jenis Kelamin | 起点 30% · 33.5% | 标签 Regular `#2E2A45`、值 Bold `#4B33A8`，2.25cqw | 见左 |
   | 编号 | `formatKitasCardNo(card_no)` | **右对齐**到 95.5% · 33.5% | Bold，2.3cqw，等宽数字 | `#2E2A45` |
   | 签章 | `Sign.png` | 77% · 49% · 宽 15% | — | — |
   | 底部副标题 | `Kartu Izin Tinggal Hewan` | 水平居中 · 93.2% | Italic，2.1cqw | `#4B33A8` |

4. 字段取值（`buildKitasFields`）：Nama ← `name`；Spesies ← `petType`（CAT / DOG / OTHER → Kucing / Anjing / Hewan，与 `KtpDefaults.spesies` 同义）；Ras ← `breed`（空 → `-`，同 KTP `ktp_fields.dart:150`）；Tanggal Lahir ← 生日 `DD-MM-YYYY`；Jenis Kelamin ← `gender`（同 SIM）。编号：`card_no` 非空 → `formatKitasCardNo`；为空 → 同 SIM（待确认 2）。
5. 不使用 `serial_id`；不使用概念图 `17-style-izin-tinggal.png` 的示例号码 `TT-00000000000` 与示例值；概念图爪印下方的签名横线 UI 稿 F3 未画，**不画**。

**AC4 — 护照签发 / 到期日改为建卡日** `[L0]`（D-5，FR-142F.8，NFR1 的唯一例外）
1. `IdCardData`（`domain/id_card.dart:8-88`）新增可空字段 `DateTime? createdAt`；`IdCard.toIdCardData()`（`domain/id_card.dart:188-207`）补传 `createdAt: createdAt`。
2. `buildPassportFields`（`passport_card.dart:51-77`）：签发日 `issue` = `data.createdAt` 换算到设备本地日期（`toLocal()`，同 AC2.4 口径）；`createdAt` 为 null（建卡页预览）→ 回落 `DateTime.now()`（即「今天建卡」的日期，预览观感不变）。到期日 = 签发日 + 5 年（沿用 `DateTime(issue.year + 5, issue.month, issue.day)` 写法）。删除 `:55` 的无条件 `DateTime.now()`。
3. Reg.No / NIKIM / 旧拼号护照号仍由 `serial_id` 派生（`passport_card.dart:52-53, 59-61, 71`），**不改**（PRD §3.5「现有护照卡保持原样」）。
4. 单测（新文件 `petgo_app/test/profile/passport_fields_test.dart`）：
   - `createdAt = 2026-08-01T10:00:00Z` → `dateOfIssue` / `dateOfExpiry` 为对应本地日期与 +5 年，且与测试运行当天无关（同一输入两次调用结果相同，不依赖 `DateTime.now()`）；
   - `createdAt` 为 null → 签发日为今天；
   - `regNo` / `nikim` / 旧卡 `passportNo` 与改动前一致（回归）。
5. 存量护照卡的日期随之变成各自建卡日——这是 D-5 拍板的预期变化，不是回归。

**AC5 — 接入注册表、详情页与导出** `[L0]` `[L2]`
1. 注册表 `IdCardStyle`（Story 1.2，`domain/id_card_style.dart`）中 `sim` / `kitas` 两项的卡面构建由占位改为 `SimCardFront(fields: buildSimFields(data))` / `KitasCardFront(fields: buildKitasFields(data))`，画布 `kIdCardCanvas`、圆角取 AC2 / AC3 的常量。**不改**注册表的其他属性（`cardType` / `analyticsCardStyle` SIM 3 / 居住证 4 等由 1.2 定）。
2. 详情页（`id_card_detail_page.dart`）对 SIM / KITAS 卡：预览恒盖 `IdCardWatermark`；付费 HD 导出（内层 `idCardBoundaryKey`）无水印、导出尺寸 1988×1200；免费分享（外层 `idCardWatermarkedBoundaryKey`）带水印。导出画布取自注册表（1.2 已把 `_capture` 的 `switch (_styleIndex)`（现 `:392-396`）收口；若未收口，本 story 补上，不得落回 KTP 以外的错误尺寸）。
3. `petgo_app/test/profile/id_card_watermark_test.dart` 的卡种循环（现 `:83` `['PASSPORT','STUDENT','KTP']`，1.2 会扩到学生卡四款）补 `SIM`、`KITAS`：详情页盖水印、水印在 HD 导出 boundary 之外。
4. 新增 widget 测试 `petgo_app/test/profile/sim_kitas_card_test.dart`：
   - `SimCardFront` 渲染于 1988×1200，含 `SURAT IZIN MENGEMUDI HEWAN`、`T`、`Diterbitkan Oleh`、格式化编号（如 `TT2303-2026-020110`）、签发日期 = 传入 `createdAt` 的日期；
   - `KitasCardFront` 含 `Pengurus Administrasi Hewan`、`Kartu Izin Tinggal Hewan`、格式化编号 `TT-230326020110`；
   - 两款卡面树中**找不到** `serialId` 派生文本（给 `serialId: 123456` 断言 `find.textContaining('123456')` 为空）；
   - 选填留空时字段值为趣味默认值；`gender: 'UNKNOWN'` → `-`。

**AC6 — 回归** `[L0]`
1. KTP / 学生卡卡面、Kartuku 列表编号、付费抽屉编号与改动前一致；`ktp_fields_test`、`ktp_card_test`、`id_card_page_test`、`id_card_share_reward_hint_test` 通过。
2. `cd petgo_app && flutter analyze && flutter test` 全绿。

## Tasks / Subtasks

- [ ] Task 1：素材入库（AC2.2、AC3.2）
  - [ ] 1.1 拷 `ktp-assets/design-assets/driving/{Driving,Logo,Sign}.png` → `petgo_app/assets/id_card/sim/`（Sign 可改为复用 `assets/ktp/ktp_paw_stamp.png`）
  - [ ] 1.2 `permit/Background.png` 按 alpha 实测裁到 994×600 → `assets/id_card/kitas/Background.png`；`Label.png` / `Logo.png` / `Sign.png` 原样入库
  - [ ] 1.3 量两张底图的卡角半径（994 尺度 ×2）→ `kSimCardCanvasRadius` / `kKitasCardCanvasRadius`
  - [ ] 1.4 `pubspec.yaml` 登记 `assets/id_card/sim/`、`assets/id_card/kitas/`（注释写来源与版本）
- [ ] Task 2：编号格式化（AC1）
  - [ ] 2.1 `id_card_number_format.dart` 两个函数 + 非法输入原样返回
  - [ ] 2.2 `id_card_number_format_test.dart`
- [ ] Task 3：SIM 卡面（AC2）
  - [ ] 3.1 `sim_card.dart`：`SimFields` / `buildSimFields` / `SimCardFront`，布局常量集中在私有 `_SimLayout`（仿 `_KtpLayout` 写法，注释写清取值来源 = UI 稿 F4）
  - [ ] 3.2 与 UI 稿 F4 叠图校准位置 / 字号（L2，本地模拟器）
- [ ] Task 4：居住证卡面（AC3）
  - [ ] 4.1 `kitas_card.dart`：`KitasFields` / `buildKitasFields` / `KitasCardFront`，`_KitasLayout`
  - [ ] 4.2 与 UI 稿 F3 叠图校准（L2）
- [ ] Task 5：护照日期（AC4）
  - [ ] 5.1 `IdCardData.createdAt` + `toIdCardData()` 补传
  - [ ] 5.2 `buildPassportFields` 签发 / 到期改由 `createdAt` 派生
  - [ ] 5.3 `passport_fields_test.dart`
- [ ] Task 6：接入注册表与详情页（AC5）
  - [ ] 6.1 `IdCardStyle.sim` / `.kitas` 卡面构建替换占位
  - [ ] 6.2 核对详情页导出画布 / 水印对两款生效
  - [ ] 6.3 `id_card_watermark_test` 补两款；新增 `sim_kitas_card_test.dart`
- [ ] Task 7：回归（AC6）——`flutter analyze` / `flutter test`；本地模拟器用 API 造一张 SIM、一张 KITAS、一张存量护照卡，看详情预览、HD 下载、分享图（L2）

## Dev Notes

### 现状（改动前必读）

- **护照日期 bug**（D-5）：`passport_card.dart:55` `final issue = DateTime.now();`，`:56` 到期 = `issue` + 5 年 → 同一张卡每天打开日期都不同，HD 导出图也随下载日变化。`:52` `serial = data.serialId ?? 0` 派生 NIKIM（`:53`、`:87-94`）、Reg.No（`:71`）与旧卡护照号（`:59-61`）——这些**保持**。
- **`IdCardData` 没有 `createdAt`**（`domain/id_card.dart:8-88`）；`IdCard` 有 `createdAt`（`:143-144`，UTC ISO8601 解析，`:173`），但 `toIdCardData()`（`:188-207`）没传 → 卡面函数拿不到建卡日。`IdCardData.fromJson` 是旧单卡端点用的，可不读 `createdAt`。
- **卡面函数模式**：每款 = 「展示字段类 + 纯函数 `buildXxxFields(IdCardData)` + `XxxCardFront` StatelessWidget」，`Stack` 内 `Positioned` + `Baseline` 绝对定位，字体 `Rubik` 可变字体按 `fontVariations` 取字重（`passport_card.dart:141-144, 301-316`；`ktp_card.dart:104-109`）。Italic 无独立字体文件，由引擎合成斜体（护照副标题已这样用，`passport_card.dart:204-205`）。
- **画布**：KTP / 学生卡 `1988×1200`（`ktp_card.dart:8`、`student_card.dart:8`），护照 `1990×1548`（`passport_card.dart:7`）。SIM / KITAS 与 KTP 同画布。
- **编号**：`card_no` = `TT + DD(日+性别加码 母50/公10/未知0) + MMYY + SP + XXXX`，14 位；建卡预览用 `buildPreviewCardNo`（`ktp_fields.dart:117-129`，末四位 `0000`；**生日缺失返回 null**）。KTP 卡面 `card_no` 为空时走 `serial` 旧拼号（`ktp_fields.dart:146`）——新卡种**不得**照抄这条回落（FR-142E.9）。
- **原样显示编号的两处**（本 story 不动）：Kartuku 列表 `id_card_page.dart:117-121`、付费抽屉 `hd_paywall_sheet.dart:28-30`。
- **详情页 / 导出**：`id_card_detail_page.dart:72-97` 按卡种选卡面；`:115-127` 外层 boundary（含水印，供免费分享）/ 内层 boundary（不含水印，供 HD 导出）；`:392-396` 按卡种选导出画布；`:257` 分享埋点 `card_style`。三处在 Story 1.2 会改为读注册表——本 story 以 1.2 的实现为准，只往注册表里填两款卡面。
- **建卡页**：`id_card_create_page.dart:80-99` 的 `_preview` 没有 `createdAt`（→ 预览签发日取今天，符合预期）。本 story 时建卡页尚无 SIM / 居住证入口（Story 1.5 网格 / 1.7 建卡页才接上），因此 SIM / KITAS 卡面在本 story 只能经「API 造卡 → 详情页」与 widget 测试验收。
- **素材现状**：`driving/Sign.png` 与 App 现有 `assets/ktp/ktp_paw_stamp.png` 逐字节相同；`permit/Logo.png`（81×80）与 `assets/ktp/ktp_logo.png`（110×110）不是同一文件——UI 稿 F3 的 HTML 在 Logo 位引用的是 `app-assets/ktp_logo.png`，epics / PRD 写的是 `Permit/` 套内 Logo，按 epics 用 `permit/Logo.png`（见待确认 4）。`permit/Background.png` 为 1008×604，比 994×600 大一圈。
- **测试现状**：`buildPassportFields` 目前**零测试**；`id_card_watermark_test.dart:81-91` 按卡种循环验水印；`ktp_card_test.dart` 是卡面渲染测试的写法参考。

### 架构约束

- AD-7：画布 1988×1200；字段排版以 UI 稿 F3 / F4 为准，字体 Rubik；不用概念图示例号码；编号格式化纯展示层、不新增存储字段；新卡种不用 `serial_id`；护照 / SIM 日期取 `createdAt`；新素材放 `assets/id_card/{sim,kitas}/`。
- AD-6：卡面入口统一走注册表 `IdCardStyle`，页面里不再新增 `switch (cardType)` 分支。
- 水印约束（`id_card_watermark.dart:3-11`）：水印层只能是 `RepaintBoundary` 外的兄弟层，**不得**挪进内层 boundary（会污染付费导出图）。
- 卡面上的印尼语固定文案（标题、标签、`Diterbitkan Oleh`、`Tailtopia`、`Kartu Izin Tinggal Hewan` 等）是**卡面设计常量**，不进 l10n（与 KTP / 护照一致）。
- 不新增依赖、不动后端。

### 本 story 不做

- 不建注册表、不做学生卡（Story 1.2）；不做列表网格 / 缩略图（1.5，缩略图脚本 AD-8 会调用本 story 的卡面组件）；不做建卡页 / 表单（1.7）；不改付费流程（1.6 / 1.8）。
- 不改 KTP / 学生卡卡面；不改护照 Reg.No / NIKIM / 护照号；不改 Kartuku 列表与付费抽屉的编号显示。
- 不做编号沿用（Epic 2）。

### 测试

- L0（云端可跑）：`id_card_number_format_test.dart`、`passport_fields_test.dart`、`sim_kitas_card_test.dart`、`id_card_watermark_test.dart`（扩 SIM / KITAS）；`flutter analyze` / `flutter test` 全绿。
- L2（本地模拟器）：用 API 造 SIM / KITAS 卡（造数法见记忆「模拟器测试账号的状态」），详情页对照 UI 稿 F3 / F4 看排版；下载 HD（无水印、1988×1200）、免费分享（带水印）；打开一张存量护照卡，签发日 = 列表「Dibuat」日期、到期 = +5 年，隔天再看不变。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-ktp.md#Story 1.3]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-ktp-delta.md#AD-6 / AD-7]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-ktp.md#3.4 字段表（SIM / Kartu Izin Tinggal 列）/ #3.5 新卡种的编号展示 / #3.6 FR-142F]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md#D-5]
- [Source: _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-ktp.html#F3 Kartu Izin Tinggal / #F4 SIM（`.cf-sim` / `.cf-kitas` 及 `.sim-*` / `.kt-*` 样式）]
- [Source: _bmad-output/planning-artifacts/v1.3.4/ktp-assets/design-assets/driving/、permit/（含 FontNote.txt）；概念图 16-style-sim.png、17-style-izin-tinggal.png（仅定方向）]

## 待确认项裁定（2026-10-12，权威，覆盖本文件上文的对应写法）

> 依据 `_bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md` D-13 ~ D-19 与技术裁定 T-1 ~ T-22。Completion Notes 里原列的「待确认」均已按此关闭。

- T-1：`formatSimCardNo(cardNo, birthday)`，4 位年份取快照生日年
- T-2：无 `card_no` 时编号位留空
- T-3：大小写按 UI 稿
- T-4：日期按手机本地日期
- D-18：居住证 Logo 用 Permit 素材包自带的

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- 待确认 1：SIM 编号「年份展开 4 位」只能从 `card_no` 的两位 `YY` 推出世纪。PRD / epics 未写规则，本 story 默认按 `20YY`（宠物生日不早于 2000 年）；若要改为取卡快照 `birthday.year`，函数签名需改为传入生日。
- 待确认 2：SIM / 居住证 `card_no` 为空时的编号位。后端建卡总会分配 `card_no`，只有建卡页预览「生日未填」时 `buildPreviewCardNo` 返回 null；文档未规定此时显示什么。本 story 默认编号位留空（不显示、不崩、不回落 `serial_id`）。
- 待确认 3：卡面文字大小写。KTP / 护照卡面把值统一转大写；UI 稿 F3 / F4 的示例是首字母大写（`Oyen`、`Jl. Melati No. 25`、`Chief Happiness Officer`、`Kucing`）。本 story 按 UI 稿：用户填的值原样显示（trim），趣味默认值与物种 / 性别映射用首字母大写形式。
- 待确认 4：居住证右上 Logo 用 `permit/Logo.png`（epics / PRD 所说的 Permit 套素材），而 UI 稿 F3 的 HTML 引用的是 App 现有 `ktp_logo.png`，两图不同。
- 待确认 5：签发日 / 护照日期的时区按设备本地日期（`createdAt.toLocal()`），与 Kartuku「Dibuat」日期一致；若要求按 WIB 固定口径（与护照号年份同口径），需另行说明。

### File List
