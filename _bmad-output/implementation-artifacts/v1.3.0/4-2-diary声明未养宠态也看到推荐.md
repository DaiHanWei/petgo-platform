# Story 4.2: Diary「声明未养宠 / 计划养宠」态也看到推荐

Status: review

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

- [x] **T1 前端：nonOwner 态接入**（AC: 1）
- [x] **T2 埋点**（AC: 3）
- [x] **T3 回归**（AC: 2）
  - [x] 确认 `DiaryGuestPage` 无改动（**机械判据**：源码里不许出现推荐位字样，见下）

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

Claude Opus（云端 headless session，L0 only）

### Debug Log References

- 前端：`flutter analyze` → No issues found；`flutter test` → 1848 passed（新增本 story 9 条）
- **纯前端**：后端一行未动、无迁移（`flyway-guard` 仍 OK，178 个迁移不变）

### Completion Notes List

**实现形态**

- **AC1**：`_NonOwnerView` 从 `StatelessWidget` 改 `ConsumerWidget`，在既有引导之下追加
  `PetRecommendationGrid`。组件 / 接口 / 卡片渲染**全部复用 Story 4.1**，本 story 零新增接口、零后端改动。
  两种 petStatus（`PLANNING` / `ENTHUSIAST`）各一条用例 —— 它们都折叠到 `DiaryUserState.nonOwner`
  这一个分支（AD-15 单一状态判定），但 AC1 说的是「两种人」，所以用例按人写。
- 🛡 **版面只在真有卡时才换**（与 4.1 同一条纪律）：池子为空 / 取不到 / 还在加载 → 整块不渲染
  **且这一屏与改动前逐像素相同**（仍是 `Center` 居中）。新站池子几乎必然为空，那是常态不是边角态。
  真有卡时才换成 `SingleChildScrollView` —— 不换的话网格会把「Ubah status」挤出可视区。
- **AC2 游客态不动**：`DiaryGuestPage` 一行未改。除了「未登录 → 仍渲染 `DiaryGuestPage` 且无推荐区」
  这条 widget 用例，另加一条**源码级**守卫：`diary_guest_page.dart` 里不许出现
  `PetRecommendationGrid` / `RecommendedPet` / `pet_card_tapped` —— 只断言 UI 看不见的话，
  日后有人给游客态加一段推荐照样能过。
- **AC3 埋点**：新常量 `kPetRecommendFromDiaryNonOwner = 'diary_non_owner'`，与 4.1 的
  `diary_empty` **是两个值**（一条用例直接断言两者不等）。事件名与属性形状不变
  （仍是 `pet_card_tapped`，属性只有 `from`）—— 本 story 不往里加任何新属性。

**code-review**

- 本 story 的改动（`_NonOwnerView` + 一个常量 + 新测试文件）**无 CONFIRMED finding**。
- 同一轮复审对 **Story 4.1** 提了 4 条，已在上一个提交（`feat(v1.3.0): batch-b1 的 4-1 …（code-review 复审修复）`）
  修掉 3 条、把第 4 条（点赞聚合成本）升级为 4.1 的 L1 决策项 —— 详见 4.1 的 Completion Notes。
  其中「推荐池 provider 必须 autoDispose + 拉黑收尾要 invalidate」那条**对本 story 同样生效**
  （两屏共用同一个 provider）。

**⚠️ L1/L2 待本地验收**

- **L2**：AC1 —— 状态 B / C 两种用户在真机/模拟器上的实际观感（推荐区追加在引导之下、
  原有两个操作没被挤走、2 列网格铺满）；AC2 —— 游客态肉眼回归（仍是演示数据那一屏）。
- **L1**：AC3 埋点落库 —— `pet_card_tapped(from=diary_non_owner)` 要真的进埋点平台，
  且能与 `diary_empty` 分开看两批人的转化。
- ⚠️ 依赖 4.1 的 L1 项：推荐接口本身的执行计划（AC2 的 `EXPLAIN`）——**同一个接口**，
  那条扛不住的话本屏一起受影响。

### File List

- `petgo_app/lib/features/profile/presentation/growth_archive_page.dart`（改：`_NonOwnerView` 接入推荐 + 版面分支）
- `petgo_app/lib/features/profile/presentation/widgets/recommended_pet_card.dart`（改：新增 `kPetRecommendFromDiaryNonOwner`）
- `petgo_app/test/profile/diary_non_owner_recommendation_test.dart`（新增，9 条）
