# Story 4.4: 探索 Tab 顶部宠物横滑行

Status: ready-for-dev

## Story

As a 在首页刷内容的用户,
I want 顶上有一行别人家的毛孩子可以横着滑,
so that 我不用专门去 Diary 页就能顺手逛两只。

## Acceptance Criteria

**AC1 — 横滑行** `[L2]`
在 **Story 1.1 已落地的场所入口行之下**，加一行横滑宠物卡（6~10 张）+「查看全部」。
点「查看全部」进 Story 4.3 的全屏集合页。

**AC2 — 顶部区域整体布局（UX-DR1）** `[L2]`
场所入口是**单行导航条**（图标 + 文字 + 箭头），宠物推荐是**单独一行横滑卡片**，两者**分层清晰、不叠成一堆**。
AppBar（品牌标 + 通知铃）、分类 chips、瀑布流、底部 Tab **全部保留原样，一处不改**。

**AC3 — 空池不渲染** `[L2]`
推荐池为空 → 整行**不渲染**（不留空占位、不显示空态）。

**AC4 — 埋点** `[L1]`
从横滑行点宠物卡 → `from` 取 `explore_strip`。

## Tasks / Subtasks

- [ ] **T1 前端：横滑行组件**（AC: 1, 3）
- [ ] **T2 前端：插入首页顶部**（AC: 1, 2）
- [ ] **T3 埋点**（AC: 4）
- [ ] **T4 回归 + 联调**（AC: 2, 3）
  - [ ] 确认通知铃角标、分类切换、游客态「Masuk」按钮全部正常

## Dev Notes

### 插入位置

`petgo_app/lib/features/content/presentation/home_page.dart` 的 `_BerandaTop`（约 242 行起）：
Story 1.1 已在**分类 chips 之上**插入了场所入口行。本 story 的横滑行加在**场所入口行与 chips 之间**。

顺序（自上而下）：AppBar → 场所入口行 → **宠物横滑行** → 分类 chips → 瀑布流。

### ⚠️ 游客态

首页游客也能看。若游客可点宠物卡：
- 访客视图的站内入口（Story 2.3）是**仅登录**的
- 集合页（Story 4.3）的路由是否对游客开放，见该 story 的说明

**本 story 必须明确游客态行为**并写进 Completion Notes：要么整行对游客不渲染，要么点击走既有登录引导。**不要留成未定义行为**。

### AC3 的理由

空池时渲染一个"暂无推荐"的空行，等于在首页最显眼的位置告诉用户"这里什么都没有"。整行不渲染更体面，也不占位。

### 验证层级

L1：AC4 · L2：AC1/2/3

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-batch-b1.md#Story 4.4]
- [Source: _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-batch-b1-places-social.html#B1-屏]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
