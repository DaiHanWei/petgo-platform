# Story 4.2: Diary「声明未养宠 / 计划养宠」态也看到推荐

Status: ready-for-dev

> **本 story 是对 PRD 原文的扩展**（B1-D2，2026-09-11 拍板）：PRD §2.4 只写了「未建档态」，
> 但代码里 Diary 页对登录用户其实有**两种**无档案状态。第二种人群恰恰是最该被引去逛别人宠物的。

## Story

As a 还没养宠物、只是想先看看的用户,
I want 在 Diary 页也能刷到别人家的毛孩子,
so that 我有理由留在这个 App 里，而不是看到一句"你还没有宠物"就退出去。

## Acceptance Criteria

**AC1 — 第二态接入** `[L2]`
登录且「声明未养宠 / 计划养宠」的用户打开 Diary 页，同样看到推荐宠物集合，渲染与交互与 Story 4.1 一致。
该屏原有的引导内容**原样保留、一个不删**。

**AC2 — 游客态不动** `[L2]`
未登录游客打开 Diary 页 **维持现状不动**（仍是演示数据），本批次不碰。

**AC3 — 埋点可区分** `[L1]`
从本屏点宠物卡时，`from` 取值能与 Story 4.1 的未建档态**区分开**，便于分别看两批人的转化。

## Tasks / Subtasks

- [ ] **T1 前端：nonOwner 态接入**（AC: 1）
- [ ] **T2 埋点**（AC: 3）
- [ ] **T3 回归**（AC: 2）
  - [ ] 确认 `DiaryGuestPage` 无改动

## Dev Notes

### 确切的代码位置

`petgo_app/lib/features/profile/presentation/growth_archive_page.dart`：

```
DiaryUserState.guest            → DiaryGuestPage        ← 本 story 不动
DiaryUserState.nonOwner         → _NonOwnerView         ← 本 story 改这个
DiaryUserState.visitor          → VisitorArchiveView    ← 不动
DiaryUserState.ownerWithoutProfile → _EmptyProfileView  ← Story 4.1 已改
DiaryUserState.ownerWithProfile → _ArchiveBody          ← 不动
```

`nonOwner` 对应 `AppUserState.planning` 与 `AppUserState.enthusiast` 两种。

### 为什么值得单独一条 story

工作量确实小（复用 4.1 的组件与接口），但它是**独立可验收的用户成果**，而且覆盖的是一批完全不同的人（从没养过宠物的）。和 4.1 合并会让那条 story 的验收横跨两个屏、两类用户。

### 验证层级

L1：AC3 · L2：AC1/2

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-batch-b1.md#B1-D2]
- [Source: petgo_app/lib/features/profile/presentation/growth_archive_page.dart]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
