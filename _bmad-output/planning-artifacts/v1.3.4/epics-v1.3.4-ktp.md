---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
inputDocuments:
  - _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-ktp.md
  - _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-ktp-delta.md
  - _bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md
  - _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-ktp.html
  - _bmad-output/planning-artifacts/v1.3.4/ktp-assets/
  - _bmad-output/planning-artifacts/architecture.md                      # 跨版本基线（不重定向）
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md       # 跨 story 契约（不重定向）
version: v1.3.4
theme: ktp
status: draft
updated: '2026-10-12'
---

# petgo-platform V1.3.4 ktp - Epic Breakdown

## Overview

本文件把 V1.3.4「KTP 样式迭代」PRD（FR-142A ~ F）、UI 稿与架构增量拆成可实现的 story。

**范围**：App + 后端 + 后台「用户购买记录」卡种显示（决策日志 D-1）。

> 🔴 **口径优先级**：`决策日志-ktp.md`（D-1 ~ D-11）> `PRD-v1.3.4-ktp.md` > `architecture-v1.3.4-ktp-delta.md`（AD-1 ~ AD-14）> UI 稿（`ui-v1.3.4-ktp.html`）。PRD 中的 Albar 设计参考图仅说明意图，**不作视觉验收标准**；视觉以 UI 稿为准。

## Requirements Inventory

### Functional Requirements

**FR-142A Know Your Pet 聚合页改整宽列表**

- **FR-142A.1** 五项由两两并排（2+2+1）改为单列整宽：Kartu Identitas / Umur Hewanmu / Jejak Anabul / Tailsonality / Boarding Pass，顺序不变
- **FR-142A.2** 标题、副标题全部沿用现有 i18n（保留「Jejak Anabul」）；年龄卡对不适用物种置灰不可点；Tailsonality 的 NEW 徽标与 coachmark 锚点保留

**FR-142B 身份证列表页：双 Tab + 8 款样式**

- **FR-142B.1** 顶部两个 Tab：「Pilih Kartu」（样式选择）/「Kartuku」（已建的卡）；从聚合页进入、从详情页返回，一律停在 Pilih Kartu
- **FR-142B.2** Pilih Kartu：标题句 + 分两组 2 列网格——「Kartu Identitas」（KTP / Paspor / Kartu Izin Tinggal / SIM）与「Kartu Pelajar」（Bintang / Malam / Ceria / Klasik）；每格 = 静态缩略图 + 卡种名
- **FR-142B.3** 5 款新卡（居住证 / SIM / Bintang / Malam / Ceria）右上角红色 `new` 角标（复用 `insightNewBadge` 样式），到截止日期后下线（D-3）
- **FR-142B.4** 点任一格进入建卡页，卡面即该样式
- **FR-142B.5** 缩略图为静态图，由开发用卡面组件渲染导出（D-2），不按当前宠物实时渲染
- **FR-142B.6** 卡种数据：`card_type` = KTP / PASSPORT / KITAS / SIM / STUDENT；学生卡 `card_variant` = BINTANG / MALAM / CERIA / KLASIK；存量学生卡回填 KLASIK，展示零变化
- **FR-142B.7** Kartuku：顶部说明句；列表项 = 紫浅底线性证件图标 + 宠物名 +「{卡种} · No. {编号}」+「Dibuat {日期}」，倒序；不做迷你卡面
- **FR-142B.8** 「Buka HD」标签逻辑反转：未解锁的卡挂紫色「Buka HD」；已解锁不挂
- **FR-142B.9** 点「Buka HD」标签（热区 ≥ 44）直接弹付费抽屉，不进详情；关闭 → 留在列表；付费成功 → 留在列表、标签消失 + toast `idCardHdUnlockedToast`；点行其余位置 → 卡详情
- **FR-142B.10** 加载失败：`idCardLoadError` + 「Coba lagi」按钮重新拉取
- **FR-142B.11** 空态：「Belum ada kartu identitas」+ 说明 +「Buat Kartu Baru」主按钮 → 切到 Pilih Kartu；有卡时底部同一按钮，取代现有列表顶部「Kartu Baru」入口行

**FR-142C 建卡页**

- **FR-142C.1** 去掉顶部三个样式 Tab；样式只能在列表页选
- **FR-142C.2** 页面 = 所选样式的卡面预览（带水印）+「Ubah info」「Ganti foto」+「Buat Kartu」；预览默认按宠物档案填充
- **FR-142C.3** 断网 / 建卡失败沿用现状：预览照常显示，失败 SnackBar `idCardCreateError`
- **FR-142C.4** 点「Buat Kartu」→ 建卡（冻结快照 + 取号）→ 弹付费抽屉；请求带卡种与 `card_variant`
- **FR-142C.5** 必填缺失（名字 / 物种 / 性别 / 生日；KTP、居住证另加品种）→「Buat Kartu」置灰，按钮上方红字说明缺什么

**FR-142D 五套 Ubah info 表单**

- **FR-142D.1** 保持底部抽屉；长表单抽屉内滚动、「Selesai」吸底
- **FR-142D.2** 按卡种字段集（只采集卡面字段 + 编号所需的物种 / 性别）：KTP 9 项、Paspor 5 项、居住证 5 项、SIM 7 项、Pelajar 6 项（字段表见 PRD §3.4）
- **FR-142D.3** 物种 / 品种在输入框下方展开下拉菜单（不再弹底部抽屉），品种长列表菜单内滚动、末项「Lainnya」展开为输入框；只改身份证表单，宠物档案表单不动
- **FR-142D.4** 去掉「一句话介绍」；新建卡不写 `intro`（存 NULL），列与存量保留
- **FR-142D.5** 选填留空 → 卡面渲染趣味默认值（沿用）
- **FR-142D.6** 必填项未填：表单内对应字段下方红字（生日沿用 `idCardBirthdayRequired`；物种「Jenis hewan wajib diisi」、性别「Jenis kelamin wajib dipilih」）；「Selesai」不拦
- **FR-142D.7** 档案有物种 / 性别则预选；没有则不预选（物种显示「Pilih jenis」）

**FR-142E 编号规则**

- **FR-142E.1** 学生卡按现有代码生成身份码 `card_no`，列表显示该号
- **FR-142E.2** 用户已有**已解锁 HD** 的学生卡，新建任意款式的卡（含其他学生卡配色与同款）且新卡生日 / 性别 / 物种与该学生卡快照**完全相同**（UNKNOWN 也算一个值）→ 沿用其 `card_no` 与 `passport_no`；任一不同、学生卡未解锁、或为 V95 前旧卡 → 照常取新号
- **FR-142E.3** 多张满足条件的学生卡 → 取**最早解锁**的那张（`hd_unlocked_at`）；新增 `hd_unlocked_at`，存量回填口径见 D-8
- **FR-142E.4** 只共用编号、不共用解锁：沿用编号的新卡仍未解锁
- **FR-142E.5** 唯一约束改为「同一用户内可重复、跨用户不可重复」；`serial_id` 不共用
- **FR-142E.6** 后端建卡时判定，前后都不提示用户
- **FR-142E.7** 卡面编号：SIM `TTDDMM-YYYY-SPXXXX`、居住证 `TT-DDMMYYSPXXXX`（含 HD 图与分享图）；Kartuku 列表与付费抽屉一律原样 `card_no`
- **FR-142E.8** 4 款学生卡卡面都显示「Nomor Induk Anabul」+ `card_no`（含 Klasik，位置按 UI 稿 F8，D-4）；V95 前旧学生卡（`card_no` 为空）编号位连同标签隐藏；列表仍显示流水号补零
- **FR-142E.9** 新增卡种卡面不使用 `serial_id`；现有护照卡 Reg No. / NIKIM 仍由 `serial_id` 派生

**FR-142F 卡详情页与付费**

- **FR-142F.1** 卡详情页无视觉变更；预览恒带水印，仅导出 HD 图无水印；免责声明保留
- **FR-142F.2** `idCardUnlockHd` 文案「Buka unduhan HD」改为「Buka HD」（en / id 同步）
- **FR-142F.3** 付费入口三个：建卡后、详情页「Buka HD」、Kartuku 列表标签；抽屉内容不变
- **FR-142F.4** 从建卡页进入：关闭或付费成功都跳到新卡详情页（建卡页出栈），从详情返回列表页
- **FR-142F.5** 从详情页进入：关闭留在详情；付费成功按钮变「Unduh HD」
- **FR-142F.6** QRIS 等待支付、断网价格失败态沿用现状
- **FR-142F.7** 价格读 `pricing_config.id_hd_download_price`，8 款同价，客户端不硬编码
- **FR-142F.8** 护照卡签发日 = 建卡日、到期日 = 建卡日 + 5 年（D-5，修现有 bug）；SIM 签发日 = 建卡日

**其他**

- **FR-142G.1** 新卡种复用现有分享与分享奖励；奖励口径不变（每个宠物档案一次，不按卡种拆）
- **FR-142G.2** 后台「用户购买记录」显示「{卡种名} · No. {身份码} · {宠物名}」，8 款各自名称；无身份码老卡显示流水号补零（D-6）
- **FR-142G.3** 埋点：新增 `id_card_tab_view` / `id_card_style_click` / `id_card_form_open` / `id_card_create` / `id_card_buka_hd_click` / `id_card_paywall_close`；既有建卡、付费、分享事件补 `card_type` / `card_variant`；`card_style` 保留并扩到 0~4（D-7）

### NonFunctional Requirements

- **NFR1 存量零变化**：现存 KTP / PASSPORT / STUDENT 卡展示除「V95 后学生卡补显编号」与「护照日期改为建卡日」（D-5）外不变
- **NFR2 编号一致性**：共用编号的卡，卡面编号与卡面信息（生日 / 性别 / 物种）永远一致；跨用户编号不重复
- **NFR3 价格同源**：展示价与扣费价都来自 `pricing_config`，不硬编码
- **NFR4 兼容旧版 App**：旧版建卡请求（带 intro、无 cardVariant）仍可处理；仅档案无物种的旧版用户可能 422（D-9）
- **NFR5 不牵动共享组件**：宠物档案表单的 `SpeciesField` / `BreedField` 行为不变
- **NFR6 迁移规范**：Flyway 时间戳命名；加 CHECK 前自检存量

### Additional Requirements

（来自 `architecture-v1.3.4-ktp-delta.md`）

- **AR1（AD-1）** 新列 `card_variant` + STUDENT 回填 KLASIK + 三条 CHECK；后端枚举 `IdCardType` / `StudentVariant`
- **AR2（AD-2）** 新列 `hd_unlocked_at`，`markHdUnlocked()` 写入；存量回填按 D-8
- **AR3（AD-3）** 归属表 `id_card_number_owners(kind, number, user_id)` 取代两条全局 UNIQUE；`ON CONFLICT DO NOTHING` + 归属校验；号存在性判断改查归属表
- **AR4（AD-4）** 建卡请求：卡种 5 值、`cardVariant` 规则、`petType` / `gender` 必填与白名单、KTP / KITAS 品种必填、`intro` 不写入；响应加 `cardVariant`，建卡返回 `numberReused`
- **AR5（AD-5）** 编号沿用判定在建卡事务内，按用户加 advisory 锁
- **AR6（AD-6）** App 单一卡款注册表 `IdCardStyle`（8 项）驱动网格、建卡、列表、详情、付费、埋点
- **AR7（AD-7）** 新卡面组件（SIM / KITAS / 学生卡三配色共用布局），画布 1988×1200，素材裁剪入库 `assets/id_card/…`；编号格式化函数；护照 / SIM 日期取 `createdAt`
- **AR8（AD-8）** 8 张缩略图由可重复执行的渲染脚本产出
- **AR9（AD-9）** 表单由注册表字段集驱动；身份证专用物种 / 品种下拉组件
- **AR10（AD-10）** 付费抽屉打开与成功处理抽成共享函数（entry = create / detail / list_badge）及三种落点
- **AR11（AD-11）** `kIdCardNewStylesBadgeUntil` 常量（发版检查项）
- **AR12（AD-12）** 新事件与属性；服务端 `ktp_unlock_succeeded` 补卡种属性，`AnalyticsEventGuard` 白名单同步
- **AR13（AD-13）** 后台购买记录 SQL / DTO / 模板 / 8 个卡种名 i18n
- **AR14（AD-14）** `insightRows()` 单列
- **AR15** 三个迁移（AD §6）；会被打破的测试清单见 AD §7；建卡页、表单、`insightRows` 补测试

### UX Design Requirements

（来自 `ui-v1.3.4-ktp.html`）

- **UX-DR1（A1 / A2）聚合页**：单列整宽，`InsightEntryCard` 不变（白底、14 圆角、38px 紫浅底图标块）；年龄卡不适用时整卡 45% 透明、图标块变灰、副标题换 `ageCardUnavailableForSpecies`
- **UX-DR2（B1）Pilih Kartu**：两组标题、2 列网格；格子 16 圆角 + 8 内边距、缩略图 8 圆角 + 1px 低透明描边、轻阴影；护照缩略图（非 16:9）等高居中；new 角标
- **UX-DR3（B2）Kartuku 空态**：紫浅底圆形 + 线性证件图标（取代 🪪）；`idCardListEmptyTitle` / `Body`；吸底主按钮「Buat Kartu Baru」
- **UX-DR4（B3）Kartuku 有卡**：顶部说明句；行首紫浅底证件图标；编号等宽数字；「Buka HD」标签独立热区；吸底「Buat Kartu Baru」
- **UX-DR5（B4 / B5）**：加载中居中转圈；失败 = 断网图标 + `idCardLoadError` + 「Coba lagi」描边按钮（高 44）
- **UX-DR6（C1 ~ C4）建卡页**：无样式 Tab；提交中主按钮内白色转圈、其余按钮不可点；必填缺失置灰 + 上方红字（文案按缺项组合）
- **UX-DR7（D1 ~ D7）表单**：五套字段如 UI 稿；校验态红字；下拉菜单在框下展开、当前项打勾、最高约 5 项内滚、「Lainnya」展开输入框
- **UX-DR8（E1 ~ E5）付费抽屉**：内容不改；E5 从列表进入叠在列表上
- **UX-DR9（G1 / G2）详情页**：按钮文案「Buka HD」；已解锁「✓ HD」行 +「Unduh HD」
- **UX-DR10（F3 ~ F8）卡面**：居住证（Permit 素材：底图 + 抬头 Label + Logo + 签章；字段名字 / 物种 / 品种 / 生日 / 性别）、SIM（Driving 素材；字段名字 / 出生城市 + 生日 / 性别 / 地址 / 职业；右下签发日期）、Bintang / Malam / Ceria（Student/02 / 03 / 01 素材；学校 / 院系 / 名字 / 物种 / 生日 + 编号）、Klasik 新增编号位（F8）、旧卡隐藏（F9）；字体 Rubik

### 新增文案（UI 稿提议值，PRD 已拍板）

| 用处 | 印尼文 | 备注 |
|---|---|---|
| Tab | Pilih Kartu / Kartuku | PRD §3.2 拍板 |
| 网格标题句 | Pilih dan buat kartu identitas untuk hewanmu! | PRD 提议 |
| 组标题 | Kartu Identitas / Kartu Pelajar | PRD 拍板 |
| 卡种名 | KTP · Paspor · Kartu Izin Tinggal · SIM · Pelajar Bintang · Pelajar Malam · Pelajar Ceria · Pelajar Klasik | PRD 拍板 |
| Kartuku 说明 | Setiap kartu adalah snapshot tersimpan dengan nomor serinya sendiri. Kartu lama tetap tersimpan untuk dilihat dan diunduh. | UI 稿 B3 |
| 主按钮 | Buat Kartu Baru | UI 稿 |
| 标签 / 详情按钮 | Buka HD | 改 `idCardUnlockHd` |
| 物种必填 | Jenis hewan wajib diisi | PRD 提议 |
| 性别必填 | Jenis kelamin wajib dipilih | PRD 提议 |
| 建卡页缺项提示 | 例「Lengkapi jenis hewan dan jenis kelamin dulu」（按缺项组合） | UI 稿 C4 |
| 学生卡编号标签 | Nomor Induk Anabul | 卡面文字 |

**英文（决策日志 D-11 审定）**：Tab = **Create new** / My Cards；网格标题 Pick and create an ID card for your pet!；组标题 ID Cards / Student Cards；卡种名 KTP · Passport · Residence Permit · Driving License · Student Star · Student Night · Student Cheerful · Student Classic；Kartuku 说明用 PRD 原英文；主按钮 Create New Card；Buka HD = Unlock HD；物种必填 Please choose a species；性别必填 Please choose a gender；缺项提示 Fill in {missing fields} first（缺几项拼几项，印尼文同理「Lengkapi … dulu」）；「Nomor Induk Anabul」为卡面文字不翻译。后台 8 个卡种名另配中文。

### FR Coverage Map

| FR | Epic |
|---|---|
| FR-142A.1 ~ A.2 | Epic 1 |
| FR-142B.1 ~ B.11 | Epic 1 |
| FR-142C.1 ~ C.5 | Epic 1 |
| FR-142D.1 ~ D.7 | Epic 1 |
| FR-142E.1、E.7 ~ E.9 | Epic 1（卡面编号展示） |
| FR-142E.2 ~ E.6 | Epic 2（编号沿用、解锁时间、唯一性） |
| FR-142F.1 ~ F.8 | Epic 1 |
| FR-142G.1 ~ G.3 | Epic 1（埋点中的 `number_reused` 在 Epic 2 接上真值） |

## Epic List

### Epic 1: 8 款卡随心选——身份证模块改版
用户在聚合页看到整宽入口，进入后先看到 8 款卡样式（含 5 款新卡），选一款进入建卡页，按该卡种的表单填写，建卡后付费解锁；已建的卡在「Kartuku」里一目了然，未解锁的卡挂「Buka HD」可直接付费。后台购买记录能正确显示 8 款卡种。
**FRs covered:** FR-142A、FR-142B、FR-142C、FR-142D、FR-142E.1 / E.7 ~ E.9、FR-142F、FR-142G
**依赖：** 无。全部改动集中在身份证模块（同一组页面与组件），合为一个 epic，按「后端卡种 → 卡面 → 聚合页 → 列表 → 建卡 → 付费落点」排序。

### Epic 2: 已解锁学生卡的编号沿用
已为学生卡解锁 HD 的用户，再为同一只宠物（生日 / 性别 / 物种一致）建任何款式的卡时，新卡沿用那张学生卡的身份码与护照号——同一只宠物在所有卡上是同一个号。
**FRs covered:** FR-142E.2 ~ E.6
**依赖：** Epic 1（新卡种与 `card_variant` 已存在）。纯后端；独立成 epic 是因为它改编号唯一约束、是本主题风险最高的一块，单独验收。

---

## Epic 1: 8 款卡随心选——身份证模块改版

用户先看到 8 款卡样式，选一款按该卡种表单建卡、付费解锁；已建的卡一目了然，未解锁的可直接付费；后台购买记录正确显示卡种。（FR-142A ~ D、E.1 / E.7 ~ E.9、F、G；AD-1、AD-4、AD-6 ~ AD-14）

### Story 1.1: 后端支持 5 种卡种与学生卡配色

As a 用户,
I want 系统能建居住证、SIM 和三款新配色的学生卡,
So that 我有更多卡可以做。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看 `id_cards`
**Then** 新增 `card_variant VARCHAR(16)`；存量 `STUDENT` 全部回填 `KLASIK`；新增 CHECK：`card_type IN ('KTP','PASSPORT','KITAS','SIM','STUDENT')`、`card_variant IN ('BINTANG','MALAM','CERIA','KLASIK')`、`(card_type = 'STUDENT') = (card_variant IS NOT NULL)`；迁移注释记录加约束前的存量自检结果（AD-1，NFR6）

**Given** 建卡请求 `POST /api/v1/pet-profiles/me/id-cards`
**When** `cardType` 为 5 值之一、学生卡带合法 `cardVariant`、非学生卡不带
**Then** 建卡成功，快照写入 `card_type` / `card_variant`
**And** `cardType` 不在白名单、学生卡缺 `cardVariant`、非学生卡带 `cardVariant` → 422

**Given** 建卡请求
**When** 缺 `petType`（或不在 `CAT|DOG|OTHER`）、缺 `gender`（或不在 `MALE|FEMALE|UNKNOWN`）、缺 `birthday`、或 KTP / KITAS 缺 `breed`
**Then** 422（AD-4；物种 / 性别不再默认填充）

**Given** 请求带 `intro`（旧版 App）
**When** 建卡
**Then** 正常建卡，`intro` 不写入（存 NULL）（FR-142D.4，NFR4）

**Given** 列表 / 详情接口
**When** 返回卡片
**Then** `IdCardResponse` 带 `cardVariant`；存量学生卡返回 `KLASIK`

**Given** 后台用户抽屉「购买记录」
**When** 用户有各卡种已解锁的卡
**Then** 每行显示「{卡种名} · No. {身份码} · {宠物名}」，8 款各自名称（zh / en / id 三语 i18n）；无身份码的老卡显示流水号补零；不再一律显示「KTP #流水号」（D-6，FR-142G.2）

**Given** 本 story
**When** 交付
**Then** 后端新增枚举 `IdCardType` / `StudentVariant`，请求白名单与 CHECK 同源；`IdCardServiceTest` / `IdCardEndpointIntegrationTest` / `AdminUserPurchasesIntegrationTest` 按新口径更新通过

### Story 1.2: 卡款注册表与四款学生卡卡面

As a 用户,
I want 学生卡有 Bintang / Malam / Ceria / Klasik 四款，卡上印着身份码,
So that 我能挑喜欢的风格、卡面也更像真证件。

**Acceptance Criteria:**

**Given** App 新增卡款注册表 `IdCardStyle`
**When** 本 story 完成
**Then** 含 8 项（KTP / Paspor / Kartu Izin Tinggal / SIM / Pelajar Bintang / Malam / Ceria / Klasik），每项给出 `cardType` + `cardVariant`、名称 l10n key（D-11）、是否 new、卡面构建函数、表单字段集、`card_style` 埋点值（KTP 0 / 护照 1 / 学生卡 2 / SIM 3 / 居住证 4，D-7）；现有 `_styleIndex` / `effectiveCardType` 的判断收口到注册表（AD-6）
**And** SIM 与居住证两项的卡面在 Story 1.3 交付前回落为占位（不崩溃）

**Given** Bintang / Malam / Ceria 学生卡
**When** 渲染卡面
**Then** 按 UI 稿 F5 ~ F7：素材 `Student/02`（Bintang）/ `Student/03`（Malam）/ `Student/01`（Ceria），底图裁到卡片本体后放 `assets/id_card/student_{bintang,malam,ceria}/`；画布 1988×1200；字段学校 / 院系 / 名字 / 物种 / 生日 +「Nomor Induk Anabul」+ 原样 `card_no`；字体 Rubik（素材 Font Note）；选填留空用现有趣味默认值（AD-7，UX-DR10）
**And** 不使用概念图的示例号码

**Given** Klasik（现有紫色学生卡）且 `card_no` 非空
**When** 渲染
**Then** 在 UI 稿 F8 位置（字段区下方）新增「Nomor Induk Anabul」+ `card_no`（D-4，FR-142E.8）

**Given** V95 前旧学生卡（`card_no` 为空）
**When** 渲染
**Then** 编号位连同标签整体隐藏，卡面与现状一致（UI 稿 F9）

**Given** 详情页与导出
**When** 查看 / 下载 HD / 分享
**Then** 四款都带水印预览、无水印 HD 导出、分享图正常；`id_card_watermark_test` 扩到新款

### Story 1.3: SIM 与居住证卡面，护照日期改为建卡日

As a 用户,
I want 做一张宠物版 SIM 驾照或居住证，护照卡日期固定,
So that 我有新卡可做，旧卡隔天看也不会变日期。

**Acceptance Criteria:**

**Given** SIM 卡
**When** 渲染卡面
**Then** 按 UI 稿 F4：`Driving/` 素材（底图 + Logo + 爪印签章）；字段名字 / 出生城市 + 生日 / 性别 / 地址 / 职业；编号 `TTDDMM-YYYY-SPXXXX`（由 `card_no` 格式化，年份展开 4 位）；右下签发日期 = 建卡日；字体 Rubik（FR-142E.7，UX-DR10）

**Given** 居住证（KITAS）
**When** 渲染卡面
**Then** 按 UI 稿 F3：`Permit/` 素材（底图 + 抬头 Label + Logo + 签章）；字段名字 / 物种 / 品种 / 生日 / 性别；编号 `TT-DDMMYYSPXXXX`

**Given** 编号格式化函数
**When** 单测覆盖 SIM / KITAS 示例
**Then** `TT230326020110` → SIM `TT2303-2026-020110`、KITAS `TT-230326020110`；格式化只在卡面（含 HD 图与分享图），Kartuku 列表与付费抽屉显示原样 `card_no`

**Given** 新卡种
**When** 渲染
**Then** 不使用 `serial_id`（FR-142E.9）

**Given** 护照卡（任意存量或新建）
**When** 渲染 / 导出
**Then** 签发日 = 卡的 `createdAt`、到期日 = 签发日 + 5 年，不再取渲染当天；`IdCard.toIdCardData()` 补传 `createdAt`（D-5，FR-142F.8）；Reg.No / NIKIM 仍由 `serial_id` 派生

**Given** 详情页与导出
**When** 查看 / 下载 HD / 分享 SIM、居住证
**Then** 水印预览、无水印 HD、分享图正常

### Story 1.4: Know Your Pet 聚合页改为单列整宽

As a 用户,
I want 聚合页每个入口一整行、副标题完整显示,
So that 看得更清楚，以后也好加新入口。

**Acceptance Criteria:**

**Given** 聚合页
**When** 渲染
**Then** 五项单列整宽，顺序 Kartu Identitas / Umur Hewanmu / Jejak Anabul / Tailsonality / Boarding Pass；`InsightEntryCard` 样式、图标、标题、副标题不变（UX-DR1，FR-142A）

**Given** 宠物物种非猫狗
**When** 渲染年龄卡
**Then** 整卡 45% 透明、图标块变灰、副标题为 `ageCardUnavailableForSpecies`、不可点（不变）

**Given** Tailsonality 入口
**When** 渲染 / 触发引导
**Then** NEW 角标与 coachmark 锚点行为不变；`pet_insights_test`、`tailsonality_entry_coachmark_test` 通过，并为 `insightRows` 补测试

### Story 1.5: 身份证列表页双 Tab 与「Create new」样式网格

As a 用户,
I want 一进身份证页就看到 8 款卡的样子并选一款,
So that 我知道能做什么卡、直接去做。

**Acceptance Criteria:**

**Given** 从聚合页、时间线或任何入口进入列表页，或从卡详情页返回
**When** 页面打开
**Then** 顶部两个 Tab「Pilih Kartu / Create new」与「Kartuku / My Cards」，默认停在 Pilih Kartu（FR-142B.1）

**Given** Pilih Kartu Tab
**When** 渲染
**Then** 标题句「Pilih dan buat kartu identitas untuk hewanmu!」；两组「Kartu Identitas」（KTP / Paspor / Kartu Izin Tinggal / SIM）与「Kartu Pelajar」（Bintang / Malam / Ceria / Klasik）；2 列网格，格子 16 圆角 + 8 内边距、缩略图 8 圆角 + 1px 低透明描边、轻阴影，护照缩略图等高居中；格下卡种名（UX-DR2，FR-142B.2）

**Given** 网格缩略图
**When** 本 story 完成
**Then** 8 张静态图由可重复执行的渲染脚本（widget 测试截图，示例宠物数据）产出入库 `assets/id_card/styles/`，非实时渲染（D-2，AD-8）

**Given** 当前时间早于 `kIdCardNewStylesBadgeUntil`
**When** 渲染
**Then** 居住证 / SIM / Bintang / Malam / Ceria 右上红色 `new` 角标（`insightNewBadge` 样式）；晚于该日期全部不显示；常量注释写明「发版检查项：上线日 + 30 天」（D-3）

**Given** 点任一格
**When** 跳转
**Then** 进入建卡页并带上该卡款（Story 1.7 前建卡页按该款渲染卡面即可）；埋点 `id_card_style_click{card_type, card_variant}`

**Given** 进入页面或切换 Tab
**When** 上报
**Then** `id_card_tab_view{tab: style|created, has_cards}`

### Story 1.6: Kartuku 列表——Buka HD 标签直接付费

As a 用户,
I want 在已建的卡列表里一眼看出哪些还没解锁 HD、点一下就能付费,
So that 我不用进详情页也能解锁。

**Acceptance Criteria:**

**Given** Kartuku Tab 有卡
**When** 渲染
**Then** 顶部说明句；每行 = 紫浅底线性证件图标 + 宠物名 +「{卡种名} · No. {编号}」（编号等宽数字：`card_no` 原样，无则流水号补零）+「Dibuat {日期}」，倒序；底部吸底主按钮「Buat Kartu Baru」，取代现有顶部「Kartu Baru」入口行（UX-DR4，FR-142B.7 / B.11）

**Given** 某卡未解锁 HD
**When** 渲染
**Then** 行内挂紫色「Buka HD」标签；已解锁的卡不挂任何标签（FR-142B.8）

**Given** 点「Buka HD」标签（热区 ≥ 44）
**When** 触发
**Then** 直接弹付费抽屉（内容同现有 E1，不进详情）；关闭 → 留在列表；付费成功（PawCoin 当场 / QRIS 到账）→ 留在列表、刷新、该卡标签消失 + toast `idCardHdUnlockedToast`；点行其余位置 → 卡详情（FR-142B.9，UX-DR8）
**And** 付费抽屉的打开与成功处理抽成共享函数（`entry = list_badge`，AD-10），本 story 起列表与详情共用

**Given** 无卡
**When** 渲染
**Then** 紫浅底圆形证件图标（取代 🪪）+ `idCardListEmptyTitle` / `Body` + 吸底「Buat Kartu Baru」；按钮切回 Pilih Kartu（UX-DR3）

**Given** 加载中 / 失败
**When** 渲染
**Then** 居中转圈 / 断网图标 + `idCardLoadError` +「Coba lagi」描边按钮（高 44，`commonRetry`），点击重新拉取（UX-DR5，FR-142B.10）

**Given** 点「Buka HD」/ 关闭抽屉
**When** 上报
**Then** `id_card_buka_hd_click{card_type, entry: list_badge}`、`id_card_paywall_close{card_type, entry: list_badge}`；既有 `ktp_unlock_*` 带 `entry = list_badge` 与 `card_type` / `card_variant`

### Story 1.7: 建卡页去掉样式 Tab，按卡种填表

As a 用户,
I want 建卡时只填这张卡上会印出来的信息,
So that 填表更快、不被无关字段干扰。

**Acceptance Criteria:**

**Given** 从网格进入建卡页
**When** 渲染
**Then** 无顶部样式 Tab；卡面预览按所选卡款渲染（带水印），按钮「Ubah info」「Ganti foto」「Buat Kartu」不变；预览默认按宠物档案填充（FR-142C.1 / C.2）

**Given** 点「Ubah info」
**When** 打开底部抽屉
**Then** 按卡款字段集显示（KTP 9 项 / Paspor 5 项 / 居住证 5 项 / SIM 7 项 / Pelajar 6 项，PRD §3.4 表），无「一句话介绍」；长表单抽屉内滚动、「Selesai」吸底；点「Selesai」回建卡页、卡面即时刷新（FR-142D.1 / D.2 / D.4，UX-DR7）；埋点 `id_card_form_open{card_type}`

**Given** 物种 / 品种字段
**When** 点击
**Then** 在输入框下方展开下拉菜单（当前项打勾、最多约 5 项可见、超出滚动），品种末项「Lainnya」展开为输入框；不再弹底部抽屉；为身份证模块新建该组件，宠物档案表单的 `SpeciesField` / `BreedField` 行为不变（FR-142D.3，NFR5）

**Given** 宠物档案有物种 / 性别
**When** 打开建卡页
**Then** 预选档案值；档案无值则不预选（物种显示「Pilih jenis」，性别三个 chip 都不选）（FR-142D.7）

**Given** 名字 / 物种 / 性别 / 生日（KTP、居住证另加品种）任一缺失
**When** 查看表单与建卡页
**Then** 表单内对应字段下方红字（生日 `idCardBirthdayRequired`，物种「Jenis hewan wajib diisi」，性别「Jenis kelamin wajib dipilih」）；「Selesai」不拦；建卡页「Buat Kartu」置灰，按钮上方红字「Lengkapi {缺的项} dulu」（UX-DR6，FR-142C.5 / D.6）

**Given** 点「Buat Kartu」
**When** 提交中 / 失败
**Then** 主按钮内白色转圈、其余按钮不可点；失败时预览保持、SnackBar `idCardCreateError`（FR-142C.3）
**And** 请求带 `cardType` + `cardVariant`；成功埋点 `id_card_create{card_type, card_variant, number_reused}`（`number_reused` 取后端返回值，Epic 2 前恒为 false）

**Given** 本 story
**When** 交付
**Then** 为建卡页与表单补 widget 测试（当前零覆盖）

### Story 1.8: 付费落点与详情页按钮统一为「Buka HD」

As a 用户,
I want 建完卡无论付不付费都能直接看到这张新卡,
So that 我不会回到建卡页重复建卡。

**Acceptance Criteria:**

**Given** 建卡成功后弹出付费抽屉（`entry = create`）
**When** 关闭抽屉或付费成功
**Then** 以新卡 id 替换当前页进入卡详情（建卡页出栈）：未付费为未解锁态，付费成功为已解锁态（「✓ HD」+「Unduh HD」）+ toast；从详情返回 → 列表页（停 Pilih Kartu）（FR-142F.4）

**Given** 从详情页点「Buka HD」（`entry = detail`）
**When** 关闭 / 付费成功
**Then** 留在详情页；成功后按钮变「Unduh HD」（FR-142F.5）

**Given** 详情页未解锁态
**When** 渲染
**Then** 右侧按钮文案为「Buka HD」/「Unlock HD」（`idCardUnlockHd` 改值），其余不变；预览恒带水印（FR-142F.1 / F.2，UX-DR9）

**Given** 三个入口
**When** 打开付费抽屉
**Then** 共用 Story 1.6 的共享函数；价格读 `pricing_config.id_hd_download_price`、8 款同价；QRIS 等待、断网价格失败态沿用（FR-142F.3 / F.6 / F.7）
**And** 埋点 `id_card_buka_hd_click{entry: detail}`、`id_card_paywall_close{entry: create|detail}`，均带 `card_type`

**Given** 服务端 HD 解锁成功
**When** 上报 `ktp_unlock_succeeded`
**Then** 带 `card_type` / `card_variant`：PawCoin 事件 `IdHdPawcoinUnlockedEvent` 加卡 id，QRIS 由意图反查卡；`AnalyticsEventGuard` 白名单同步；`KtpUnlockAnalyticsListenerTest` 更新（AD-12）

**Given** 分享
**When** 任一款卡分享
**Then** 复用现有分享与奖励（每宠物档案一次，口径不变）；`id_card_share_tapped` 保留 `card_style`（0~4）并加 `card_type` / `card_variant`（D-7，FR-142G.1）；事件前缀测试同步

---

## Epic 2: 已解锁学生卡的编号沿用

已为学生卡解锁 HD 的用户，再为同一只宠物建任何款式的卡时沿用那张学生卡的身份码与护照号。纯后端。（FR-142E.2 ~ E.6；AD-2、AD-3、AD-5）

### Story 2.1: 记录每张卡的 HD 解锁时间

As a 系统,
I want 知道每张卡是什么时候解锁 HD 的,
So that 多张学生卡都符合条件时，能找到最早解锁的那张。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看 `id_cards`
**Then** 新增 `hd_unlocked_at TIMESTAMPTZ NULL`（AD-2）

**Given** PawCoin 购买成功或 QRIS 到账
**When** 卡被置为已解锁（`IdCard.markHdUnlocked()`）
**Then** 同时写入 `hd_unlocked_at = 当前时刻`；已解锁的卡重复回调不改写该时间

**Given** 存量已解锁卡（迁移回填）
**When** 回填
**Then** 取该卡最早一条有效支付记录：PawCoin 行（无支付意图）取 `purchased_at`；QRIS 行取其支付意图**到账（PAID）**时间；都没有（V92 回填的老卡）取卡的 `created_at`（D-8）
**And** 未解锁卡保持 NULL

**Given** 本 story
**When** 交付
**Then** `IdCardHdIntegrationTest` / `IdHdPaidHandlerTest` 覆盖两条路径写入时间

### Story 2.2: 编号唯一性改为「同一用户内可重复、跨用户不可重复」

As a 系统,
I want 允许同一用户的多张卡共用一个号、同时保证不同用户的号永不重复,
So that 编号沿用可行，发号出错时仍有兜底。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看库表
**Then** 新建 `id_card_number_owners(kind CHAR(1), number VARCHAR(16), user_id BIGINT, PRIMARY KEY(kind, number))`（`C` = 身份码、`P` = 护照号），按现有 `id_cards` 去重回填；删除 `uq_id_cards_card_no` / `uq_id_cards_passport_no`；`uq_id_cards_serial` 不动（AD-3）
**And** 回填前自检：同一个号若已被不同用户持有，迁移失败并在日志中列出（理论上不会出现，现为全局唯一）

**Given** 建卡写号
**When** 号未登记
**Then** 登记为本用户；号已登记且属于本用户 → 通过；属于其他用户 → 建卡失败并回滚（不写卡）

**Given** `CardNumberService` 发新号
**When** 判断候选号是否已存在
**Then** 改查归属表（不再查 `id_cards`），发号格式与计数器逻辑不变；`CardNumberServiceTest` 更新

**Given** 宠物护照取号（V1.3.2 `findReusableKtpPassportNo`）
**When** 多张卡共用同一护照号
**Then** 行为不变：已被 `pet_passports` 占用的号不会再被取（回归测试覆盖）

### Story 2.3: 已解锁学生卡的编号沿用

As a 已为学生卡解锁 HD 的用户,
I want 再给同一只宠物建其他卡时用同一个号,
So that 我家宠物在所有卡上都是同一个身份码。

**Acceptance Criteria:**

**Given** 用户有一张已解锁的学生卡（任意配色），`card_no` 非空，快照生日 / 性别 / 物种为 A
**When** 新建任意款式的卡（含其他学生卡配色与同款学生卡），生日 / 性别 / 物种也为 A
**Then** 新卡的 `card_no` 与 `passport_no` 与该学生卡相同，不调用发号器；`serial_id` 照常新取；新卡 `hd_unlocked = false`（FR-142E.2 / E.4 / E.5，AD-5）
**And** 建卡响应 `numberReused = true`

**Given** 三项中任一不同（性别 UNKNOWN 与 MALE 也算不同）
**When** 建卡
**Then** 照常取新号，`numberReused = false`

**Given** 学生卡未解锁，或为 V95 前旧卡（`card_no` 为空），或已解锁的是 KTP / 护照 / SIM / 居住证而非学生卡
**When** 建卡
**Then** 照常取新号（沿用来源只认已解锁的学生卡）

**Given** 两张以上已解锁学生卡都满足条件
**When** 建卡
**Then** 沿用 `hd_unlocked_at` 最早（同刻按 id 最小）那张的号（FR-142E.3）

**Given** 比对口径
**When** 判定
**Then** 比的是两张卡的**快照值**（`birthday` / `gender` / `pet_type`），不是宠物档案当前值；判定在建卡事务内、按用户加事务级 advisory 锁，并发建卡结果一致

**Given** 沿用编号的新卡
**When** 用户查看
**Then** 前端不提示「沿用 / 已沿用」；卡面、列表、付费抽屉显示该号；仍需单独付费解锁 HD（FR-142E.6）

**Given** 本 story
**When** 交付
**Then** `IdCardServiceTest` 与集成测试覆盖：命中、三项各不同、未解锁、V95 前旧卡、非学生卡来源、多张取最早、并发、跨用户号冲突兜底
