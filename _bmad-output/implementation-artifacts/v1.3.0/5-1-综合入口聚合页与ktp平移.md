---
baseline_commit: ac3d57b733769b3bbc374e82db59a07082e26522
epic: 5
story: 1
batch: A
verification: [L0, L1, L2]
---

# Story 5.1: 综合入口聚合页与 KTP 平移

Status: review

## Story

As a 用户，
I want 宠物身份证和其他小工具在一个入口里，
so that 我知道去哪找、而不是散落在页面各处。

---

> **含一条安全攸关约束**：新路由必须落 `/profile/` 前缀下自动继承游客门控，**不得进例外集合** —— 那等于把安全默认反转（CLAUDE.md「安全规则层只升不降不可绕过」）。
>
> **Epic 5 的第一条**，后续三条都挂在它交付的入口上。

---

## Acceptance Criteria

### AC1 — 入口条改造（L2）

**Given** 成长档案页头部入口区
**When** 渲染
**Then** 原「宠物身份证」入口卡改为综合入口，标题 EN `Know Your Pet` / ID `Kenali Hewanmu` `[L2]`
**And** 副文案沿用现成的 `Tap to view` / `Ketuk untuk lihat`，**不新写** `[L0]`
**And** 两张入口卡改为横向布局（左图标 + 右文字），与聚合页两卡同一样式 `[L2]`

### AC2 — 聚合页只有两卡（L2）

**Given** 点击该入口
**When** 进入聚合页
**Then** 显示 2 卡 2 列网格：宠物身份证、年龄换算卡 `[L2]`
**And** 页面标题与入口名同源 `[L2]`
**And** **不出现**护照与性格测试的卡位、占位或置灰项 `[L2]`
**And** 实现中**不预埋隐藏卡位** —— 批次 C 是「新增卡」，不是「解锁占位」 `[L0]`

### AC3 — KTP 整体平移（L2）

**Given** 宠物身份证
**When** 从聚合页进入
**Then** 功能与改造前完全一致 `[L2]`

### AC4 — 🔴 旧路径不断链（L2）

**Given** 旧路径 `/profile/id-card`
**When** 被站内跳转或历史深链访问
**Then** 重定向到新路径，**不断链** `[L2]`
**And** 站内两处既有跳转（`growth_archive_page.dart` 约 368 / 706 行）均可达 `[L2]`
**And** 重定向发生在游客门控**之后**，不构成绕过门控的旁路 `[L1]`

### AC5 — 🔴 游客门控只升不降（安全攸关，L1）

**Given** 新增的聚合页与年龄卡页路由
**When** 未登录游客访问
**Then** 被既有 `/profile` 前缀门控拦截 `[L1]`
**And** 这些路由**不得**被加入门控例外集合 `[L0]`

> 例外集合的三条硬约束（路由表注释在案）：只接受完整路径字面量、不得为放行子页把 `/profile/xxx` 塞进来、判定用 `contains` 不用 `startsWith`。

### AC6 — 🔴 迁移时禁止前缀替换（L0）

**Given** 迁移旧路径
**When** 修改代码
**Then** **不使用前缀字符串替换** `[L0]`

> `/profile/id-card`（本次迁移）与 `/profile/id-cards/create`、`/profile/id-cards/:id`（多卡子路由，**不受本次迁移影响**）只差一个字母，前缀替换会误伤后两者。

### AC7 — 非猫狗置灰（L2）

**Given** 当前宠物既不是猫也不是狗
**When** 查看聚合页
**Then** 年龄换算卡**原地置灰**，副文案显示 EN `Available for cats & dogs` / ID `Hanya untuk kucing & anjing` `[L2]`
**And** 点击无反应，**不跳转、不弹层、不新开页** `[L2]`
**And** 宠物身份证卡对全物种可用 `[L2]`

### AC8 — 页头三态（L2）

**Given** 档案页头是本人/游客/访客三态共用组件
**When** 游客态或访客态渲染
**Then** **不渲染**该综合入口条 `[L2]`
**And** 沿用组件既有的三态判定，不另写一次态判断 `[L0]`

---

## Tasks / Subtasks

- [x] 新增聚合页路由（落 `/profile/` 前缀）与页面（AC2/AC5）
- [x] 档案头部入口卡改造为综合入口，改横向布局（AC1）
- [x] KTP 平移到新路径；旧路径保留重定向（AC3/AC4）
- [x] 逐个替换站内两处跳转，**不做前缀替换**（AC6）
- [x] 非猫狗置灰态（AC7）
- [x] 页头三态分支（AC8）—— 沿用既有 `readOnly`，未新写态判定
- [x] 两语 l10n key（AC1/AC7）
- [x] 游客态访问新路由的拦截验证（AC5）—— L0 已钉；**L1 真跑仍待本地**

## Dev Notes

### 要改的文件

| 文件 | 改什么 |
|---|---|
| `petgo_app/lib/core/router/app_router.dart` | 新路由（约 675 行 `/profile/id-card` 旁）；**`_controlledLocations` 例外集合不得改** |
| `petgo_app/lib/features/profile/presentation/widgets/diary_header.dart` | `diaryIdCardButton`（约 185 行）改综合入口；`_entryCard` 改横向；三态分支 |
| `petgo_app/lib/features/profile/presentation/`（新建） | 聚合页 |
| `petgo_app/lib/features/profile/presentation/id_card_page.dart` | 平移，逻辑不改 |
| `petgo_app/lib/features/profile/presentation/growth_archive_page.dart` | 两处跳转（368 / 706） |
| `petgo_app/lib/l10n/app_en.arb` / `app_id.arb` | 入口名、置灰提示 |

### 验证层级

- `[L0]` 编译、例外集合未改、无前缀替换、无隐藏卡位
- `[L1]` 游客门控拦截
- `[L2]` 入口视觉、深链可达、置灰态、三态渲染

### References

- 架构 delta `AD-A17`（含 5/6/7）· `AD-A24`（页头三态）· `文案需求清单.md` §1/§2

---

## Dev Agent Record

### Context Reference

- 本分支产物：架构 delta `AD-A17`（新路由 / 重定向 / 只有两卡 / 置灰 / 门控 / 禁前缀替换）、`AD-A24`（页头三态）
- 文案：`文案需求清单.md` §1（入口名）、§2（置灰提示），均已定稿

### Agent Model Used

云端 headless session（claude.ai/code），只跑 L0。

### Debug Log References

- `flutter analyze`：No issues found
- `flutter test`：**1704 通过 / 0 失败**（本 story 新增 18 条，见 `test/profile/pet_insights_test.dart`）
- 后端零改动，无迁移

### Completion Notes List

**L1/L2 待本地验收。** 真机 / 集成走查清单：

| AC | 要做的动作 | 看什么 |
|---|---|---|
| AC1 | 看成长档案页头部入口区 | 右卡标题变成「Know Your Pet / Kenali Hewanmu」，**横向布局**（左图标 + 右文字），与左边健康记录卡等高；副文案仍是现成的 `Tap to view / Ketuk untuk lihat` |
| AC1 | 切 ID 语言看一眼 | `Kenali Hewanmu`（14 字符）不折行、不撑高整行 |
| AC2 | 点进聚合页 | 2 卡 2 列；页面标题与入口卡标题一致；**没有**护照 / 性格测试的任何卡位、占位、灰项 |
| AC3 | 从聚合页点进宠物身份证 | 功能与改造前**完全一致**（页面代码一字未改，只换了挂载路径） |
| AC4 | **L1**：用旧深链 `/profile/id-card` 进（登录态） | 重定向到 `/profile/pet-insights/id-card`，不断链 |
| AC4 | 站内两处跳转：档案页入口卡、时间线「证件卡」条目 | 前者进聚合页；后者**直达身份证页**（点一条具体记录却落在功能列表上是走回头路） |
| AC5 | **L1 · 安全攸关**：退成游客，深链 `/profile/pet-insights`、`/profile/pet-insights/age-card`、旧的 `/profile/id-card` | 三条**全部**被门控挡回 `/home`；没有任何一条漏网 |
| AC7 | 把当前宠物切成非猫狗（或用 OTHER 档案） | 年龄卡**原地置灰** + 副文案「Available for cats & dogs / Hanya untuk kucing & anjing」；**点了没有任何反应**（不跳转、不弹层、不新开页）；身份证卡照常可用 |
| AC8 | 游客态 Diary 页 / 访客态（看别人的宠物） | **都不渲染**这个入口条（连同未庆祝角标一起，由既有 `readOnly` 一个判定挡掉） |

**⚠️ 本 commit 的已知中间态**：聚合页的年龄卡点下去会落到 `/profile/pet-insights/age-card`，
而**该页面属 Story 5.2**，本 commit 里还没有 —— go_router 会显示未知路由页。5.2 落地即通。
（不做占位页：AC2 明令不预埋卡位，弄个假页面同样是"占位"的一种。）

**实现要点（复审着眼处）**：

1. **🔴 安全攸关 AC5 的落实方式是「什么都不做」**：新路由落在 `/profile/` 前缀下，
   路由表对该前缀是「默认拦截、子页自动受控」，因此**一行安全代码都不用写**。
   测试反向钉住两件事：`_controlledExactExceptions` 仍只有 `'/profile'` 一条、
   `_controlledLocations` 仍含 `/profile`。为放行子页往例外集合里塞东西，
   等于把安全默认反转 —— 那是 CLAUDE.md「安全规则层只升不降不可绕过」这条红线。
2. **重定向在门控之后**：顶层 `redirect` 先跑，游客在那里就被送回 `/home`，
   根本走不到路由级 redirect —— 它因此不构成旁路（AD-A17.6）。测试里对旧路径也断言了受控。
3. **AC6 逐条改，不做前缀替换**：`/profile/id-card` 与 `/profile/id-cards/create`、
   `/profile/id-cards/:id` 只差一个字母。测试断言后两条**一字未动**，且全仓没有残留的旧字面量。
4. **AC2 连隐藏卡位都不留**：测试扫页面源码（剥掉注释后），`passport` / `tailsonality` /
   `comingSoon` 一个都不许出现。批次 C 是「新增卡」，不是「解锁占位」。
5. **AC8 没有新写态判定**：入口条仍挂在既有的 `if (!readOnly)` 里。
6. **档案取不到时保守置灰**：一个算不出结果的入口可点，比它暂时灰着更糟。
7. **入口卡 key 不改**（`diaryIdCardButton`）：入口在那一格的语义没变，改 key 会白白弄断既有测试与埋点对照。

**⚠️ 文案缺口（需产品确认，不阻断）**：聚合页里**年龄卡自己的标题**（暂用 EN `Pet Age` / ID `Umur Hewanmu`）
不在 `文案需求清单.md` 的五项之内 —— §1 只定了入口名、§2 只定了置灰提示。取了最直白的说法，待确认。

### File List

**新增**
- `petgo_app/lib/features/profile/presentation/pet_insights_page.dart`（聚合页 + `PetInsightsRoutes` 路径常量）
- `petgo_app/test/profile/pet_insights_test.dart`（18 条）

**改动**
- `petgo_app/lib/core/router/app_router.dart`（聚合页路由、KTP 平移、旧路径重定向；**门控集合一字未改**）
- `petgo_app/lib/features/profile/presentation/widgets/diary_header.dart`（入口卡改综合入口 + 横向布局）
- `petgo_app/lib/features/profile/presentation/growth_archive_page.dart`（两处跳转逐条改）
- `petgo_app/lib/l10n/app_en.arb`、`app_id.arb`（`petInsightsTitle` / `ageCardTitle` / `ageCardUnavailableForSpecies`）
- `petgo_app/test/profile/diary_guest_state_test.dart`、`timeline_five_class_render_test.dart`（入口图标与 KTP 路径随平移更新）

---

## Change Log

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-09-10 | 创建 | bmad-create-epics-and-stories |
