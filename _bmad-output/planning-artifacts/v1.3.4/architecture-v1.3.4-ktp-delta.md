---
title: "V1.3.4 ktp 主题 · 架构增量"
status: draft
created: 2026-10-12
inputs: PRD-v1.3.4-ktp.md · ui-v1.3.4-ktp.html · 决策日志-ktp.md · ktp-assets/
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: feat/1.3.4-ktp-revamp @ 28a7d30d（= main，含 V1.3.3）
---

# V1.3.4 ktp 主题 · 架构增量（AD-1 ~ AD-14）

> 只写本版本新增或改变的技术决定，均已对照代码。**权威顺序**：`决策日志-ktp.md` > `PRD-v1.3.4-ktp.md` > 本文件 > UI 稿。

## 0. 现状要点（2026-10-12 核实）

- `id_cards`（V91 起）一卡一行快照；**无任何 CHECK**（`card_type` / `pet_type` / `gender` 均为自由字符串，靠请求白名单）。`card_no` / `passport_no` 有全局 `UNIQUE`（V95），`serial_id` 有 `uq_id_cards_serial`。
- `IdCardService.createCard`（:78-91）不读宠物档案：表单值即快照；**每张卡**（含学生卡）都分配 `serial_id` + `card_no` + `passport_no`。`CardNumberService`：物种 DOG→01、CAT→02、其他→00；性别 FEMALE+50 / MALE+10 / 其他 0。
- HD 解锁：PawCoin `IdCardHdService.purchaseCard` 当场 `markHdUnlocked`；QRIS 到账 `IdHdPaidHandler` → `completeCardByIntent`。**没有解锁时间**。
- 卡面字体 **Rubik 已在 App**（`assets/fonts/Rubik-VariableFont_wght.ttf`，三款卡面都在用）。画布：KTP / 学生卡 1988×1200、护照 1990×1548（设计尺寸 ×2）。
- 护照卡签发 / 到期日取 `DateTime.now()`（`passport_card.dart:55`）——D-5 修。
- 宠物护照（V1.3.2）复用身份证卡护照号的查询 `findReusableKtpPassportNo` 不区分卡种，且已排除「被 `pet_passports` 占用的号」，共用编号后无需改。

## 1. 数据模型

**AD-1 · 卡种与配色。**
- `card_type` 取值扩为 `KTP` / `PASSPORT` / `KITAS` / `SIM` / `STUDENT`（PRD 建议名，沿用）；新增列 `card_variant VARCHAR(16)`：学生卡 `BINTANG` / `MALAM` / `CERIA` / `KLASIK`，非学生卡为 `NULL`。
- 迁移：①加列；②存量 `STUDENT` 回填 `KLASIK`；③**补 CHECK**：`card_type IN (...)`、`card_variant IN (...)`、`(card_type = 'STUDENT') = (card_variant IS NOT NULL)`。存量 `card_type` 只可能是三个旧值（V93 起请求白名单），加约束安全；迁移前用一条计数查询自检并在注释中记录。
- 后端新增枚举 `IdCardType` / `StudentVariant`（现为裸字符串），请求白名单与 CHECK 同源。

**AD-2 · 解锁时间 `hd_unlocked_at`。**
- 新增 `id_cards.hd_unlocked_at TIMESTAMPTZ NULL`；`IdCard.markHdUnlocked()` 同时写入（PawCoin 与 QRIS 两条路径都经它）。
- 存量回填（PRD §3.5【建议】，本文件采纳）：已解锁卡取**该卡最早一条有效支付记录**的时间——PawCoin 行（无 `payment_intent_id`）取 `purchased_at`；QRIS 行取其支付意图到账（`PAID`）的 `updated_at`；都没有（V92 回填生成的老卡）取卡的 `created_at`。误差只影响「多张学生卡谁最早」，可接受。

**AD-3 · 编号唯一性：「同一用户内可重复、跨用户不可重复」用归属表实现。**
- 删除 `uq_id_cards_card_no` / `uq_id_cards_passport_no`；新建 `id_card_number_owners(kind CHAR(1), number VARCHAR(16), user_id BIGINT, PRIMARY KEY (kind, number))`，`kind` = `C`（身份码）/ `P`（护照号）。迁移按现有 `id_cards` 去重回填。
- 建卡写号时：`INSERT … ON CONFLICT (kind, number) DO NOTHING`，再查归属，**不是本人 → 抛错回滚**（发号出错的兜底，PRD）。不用排他约束（需 `btree_gist` 扩展，生产库装扩展有权限与发布风险）、不用触发器（逻辑藏在库里不易测）。
- `CardNumberService.cardNoExists` 等「号是否已存在」的判断改查归属表。

## 2. 建卡与编号沿用（FR-142C / E）

**AD-4 · 建卡请求。**
- `CreateIdCardRequest`：`cardType` 白名单扩为 5 值；新增 `cardVariant`（学生卡必填、其他必须为空，否则 422）；`petType` 改为**必填**且限 `CAT|DOG|OTHER`；`gender` 改为**必填**且限 `MALE|FEMALE|UNKNOWN`（不再把空值默认成 UNKNOWN）；`breed` 对 KTP / KITAS 必填。
- `intro`：请求字段保留以兼容旧版 App，但**一律不写入**（存 `NULL`，PRD §3.4）。
- ⚠️ 兼容：线上 1.3.3 及更早 App 建卡时总会带 `gender`（默认 UNKNOWN）与档案的 `petType`；仅「档案没填物种」的极少数旧版用户会因 `petType` 必填建卡失败（422），可接受。
- 响应 `IdCardResponse` 加 `cardVariant`；建卡接口额外返回 `numberReused`（埋点用，PRD §4）。

**AD-5 · 编号沿用。** `createCard` 在发号前查「沿用来源」：
- 条件：本人、`card_type = 'STUDENT'`、`hd_unlocked = true`、`card_no` 非空（排除 V95 前旧卡）、快照 `birthday` / `gender` / `pet_type` 与新卡**完全相同**（UNKNOWN 也是一个值）；取 `hd_unlocked_at` 最早（同刻再按 id）的一张。
- 命中 → 新卡复制其 `card_no` 与 `passport_no`，不调发号器；`serial_id` 照常新取（PRD）。未命中 → 照常发号。
- 新卡一律 `hd_unlocked = false`（只共号、不共解锁）。新卡可以是 8 款任一款（含同款学生卡）。
- 判定在后端建卡事务内完成，不提示用户（PRD）；对同一用户加事务级 advisory 锁，避免并发两次建卡对「沿用 / 新发」判定不一致。

## 3. App

**AD-6 · 单一「卡款」注册表。** 新增 `IdCardStyle`（8 项：`cardType` + `cardVariant`、名称 l10n key、缩略图资源、是否 new、卡面构建函数、表单字段集）。列表网格、建卡页、Created 列表卡种名、详情页、付费抽屉、埋点都从这里取，**不在各页面各写一份 switch**。现有 `_styleIndex`（0/1/2）与 `effectiveCardType` 收口到这里；`card_style` 埋点值按 D-7 由注册表给出。

**AD-7 · 卡面。**
- 新增：SIM、居住证（KITAS）、学生卡 Bintang / Malam / Ceria（三款学生卡共用一个布局组件，按配色换底图与字色）。画布统一 **1988×1200**（设计素材 994×600 的 2 倍）；`ktp-assets/design-assets/` 中 `student01` / `student03` 的 `Background.png` 带透明留白，入库前裁到卡片本体 994×600。字段排版以 UI 稿 F3 ~ F7 为准，字体 Rubik（各素材 Font Note）。**不使用概念图里的示例号码**。
- 编号展示：SIM `TTDDMM-YYYY-SPXXXX`、居住证 `TT-DDMMYYSPXXXX`（纯展示层格式化 `card_no`，含 HD 图与分享图）；4 款学生卡显示「Nomor Induk Anabul」+ 原样 `card_no`；`card_no` 为空（V95 前旧卡）整块隐藏。Klasik 编号位按 UI 稿 F8（D-4）。新卡种不使用 `serial_id`。
- 护照卡：签发日 / 到期日改由卡的 `createdAt` 派生（D-5）；`IdCard.toIdCardData()` 补传 `createdAt`。SIM 签发日同取 `createdAt`。护照 Reg.No / NIKIM 仍由 `serial_id` 派生（PRD，不改）。
- 新卡素材放 `assets/id_card/{sim,kitas,student_bintang,student_malam,student_ceria}/`。

**AD-8 · 缩略图（D-2）。** 用卡面组件 + 固定示例数据渲染导出 8 张静态图（`assets/id_card/styles/*.webp`），由一个可重复执行的生成脚本（widget test 截图）产出并入库；设计日后可直接替换文件。

**AD-9 · 表单。** 每款一套字段集（PRD §3.4 表），由注册表驱动同一个底部抽屉组件渲染；物种 / 品种在身份证表单内改为**输入框下方下拉菜单**（新建身份证模块专用组件），共享的 `SpeciesField` / `BreedField` 不动（UI 稿审阅意见 ③）。必填缺失：表单内红字（生日沿用 `idCardBirthdayRequired`，新增物种 / 性别两条）；「Selesai」不拦；建卡页「Buat Kartu」置灰并在上方说明缺项（UI 稿 C4）。档案有值则预填，无值不预选。

**AD-10 · 列表页与付费入口。**
- 列表页改双 Tab（Pilih Kartu / Kartuku），从任何入口进入默认 Pilih Kartu。
- 付费抽屉的打开与成功后处理抽成一个共享函数，带 `entry`（`create` / `detail` / `list_badge`），三个入口共用；落点：`create` → 关闭或成功都 `pushReplacement` 到新卡详情（建卡页出栈）；`detail` → 留在详情；`list_badge` → 留在列表，成功后刷新列表 + toast。

**AD-11 · `new` 角标（D-3）。** 常量 `kIdCardNewStylesBadgeUntil`（DateTime，WIB），列为发版检查项；`DateTime.now()` 超过即全部不显示。

## 4. 埋点

**AD-12.** 新增客户端事件（PRD §4）：`id_card_tab_view{tab, has_cards}`、`id_card_style_click{card_type, card_variant}`、`id_card_form_open{card_type}`、`id_card_create{card_type, card_variant, number_reused}`、`id_card_buka_hd_click{card_type, entry}`、`id_card_paywall_close{card_type, entry}`。既有 `ktp_unlock_*`、`id_card_share_*` 补 `card_type` / `card_variant`（`card_style` 保留，D-7）。服务端 `ktp_unlock_succeeded` 补 `card_type` / `card_variant`：PawCoin 事件 `IdHdPawcoinUnlockedEvent` 加卡 id，QRIS 由意图反查卡；`AnalyticsEventGuard` 白名单同步加属性键。事件前缀登记测试（`v112_events_test`）同步。

## 5. 其他

**AD-13 · 后台购买记录（D-6）。** `AdminUserPurchasesQuery.KTP_SQL` 增选 `card_type` / `card_variant` / `card_no`；`UserPurchasesView.KtpCard` 加字段；模板按「{卡种名} · No. {编号} · {宠物名}」渲染，卡种名 8 个 i18n key（zh / en / id）。

**AD-14 · 聚合页。** `insightRows()` 改单列；`InsightEntryCard`、顺序、文案、年龄卡置灰、Tailsonality NEW 与 coachmark 锚点不变。

## 6. 迁移清单（时间戳命名）

| 迁移 | 内容 |
|---|---|
| `…__id_cards_card_variant_and_checks.sql` | `card_variant` + STUDENT 回填 KLASIK + 三条 CHECK |
| `…__id_cards_hd_unlocked_at.sql` | `hd_unlocked_at` + 存量回填（AD-2） |
| `…__id_card_number_owners.sql` | 归属表 + 回填 + 删除两条全局 UNIQUE |

## 7. 会被打破的既有测试（开发时以实际为准）

`id_card_page_test`（列表结构 / 入口行）· `id_card_watermark_test`（三类型 → 八款）· `pet_insights_test`（布局）· `IdCardServiceTest` / `IdCardEndpointIntegrationTest`（白名单、必填、编号沿用）· `CardNumberServiceTest`（唯一性判断改查归属表）· `AdminUserPurchasesIntegrationTest` · `KtpUnlockAnalyticsListenerTest` · `v112_events_test`。`IdCardCreatePage`、表单、`insightRows` 目前**零测试**，本版本补齐。

## 8. 发现但不在本版本处理

1. 分享奖励按「当前」宠物档案去重，不看卡所属宠物（PRD 已定奖励口径不变）
2. 后端品种长度上限 80，App 自定义品种输入上限 60
3. `IdCardHdService` 类注释、V86 表注释仍写「每账号一行」（V92 已废弃该模型）
4. 详情页导出自有 `_capture`，未走共享 `card_render` 管线
