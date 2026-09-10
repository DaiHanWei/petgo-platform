---
baseline_commit: ac3d57b733769b3bbc374e82db59a07082e26522
epic: 5
story: 1
batch: A
verification: [L0, L1, L2]
---

# Story 5.1: 综合入口聚合页与 KTP 平移

Status: ready-for-dev

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
**And** 实现中**不预埋隐藏卡位** —— 批次 B2 是「新增卡」，不是「解锁占位」 `[L0]`

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

- [ ] 新增聚合页路由（落 `/profile/` 前缀）与页面（AC2/AC5）
- [ ] 档案头部入口卡改造为综合入口，改横向布局（AC1）
- [ ] KTP 平移到新路径；旧路径保留重定向（AC3/AC4）
- [ ] 逐个替换站内两处跳转，**不做前缀替换**（AC6）
- [ ] 非猫狗置灰态（AC7）
- [ ] 页头三态分支（AC8）
- [ ] 两语 l10n key（AC1/AC7）
- [ ] 游客态访问新路由的拦截验证（AC5）

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

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

---

## Change Log

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-09-10 | 创建 | bmad-create-epics-and-stories |
