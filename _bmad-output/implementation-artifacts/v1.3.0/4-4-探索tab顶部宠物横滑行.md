# Story 4.4: 探索 Tab 顶部宠物横滑行

Status: review

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

- [x] **T1 前端：横滑行组件**（AC: 1, 3）
- [x] **T2 前端：插入首页顶部**（AC: 1, 2）
- [x] **T3 埋点**（AC: 4）
- [ ] **T4 回归 + 联调**（AC: 2, 3）—— **L1/L2 待本地验收**
  - [ ] 确认通知铃角标、分类切换、游客态「Masuk」按钮全部正常（**L2：需真机/模拟器**；
        L0 侧已钉住 AppBar 品牌标 / 场所入口行 / 分类 chips 三者在四种情形下都还在）

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

Claude Opus（云端 headless session，L0 only）

### Debug Log References

- 前端：`flutter analyze` → No issues found；`flutter test` → 1886 passed（本 story 新增 15 条）
- **纯前端**：后端一行未动、无迁移（复用 Story 4.1 的接口与 4.3 的集合页路由）

### Completion Notes List

**🔴 游客态：整行不渲染，且一个请求都不发（本 story 定的，Dev Notes 要求写在这里）**

Dev Notes 说「必须明确游客态行为，不要留成未定义行为」。选的是**不渲染**，
而这不是产品偏好、是硬约束：推荐接口挂在 `/api/v1/me` 下、**仅登录可用**，
游客发过去拿 401，而 **401 会弹全局强登录窗** —— 等于游客一打开首页就被登录弹窗糊住脸
（V1.1.6 Story 2.4 为此专门立过「未登录时绝不订阅 `/me` 的 provider」）。
⚠️ 因此判空必须写在 `ref.watch(petRecommendationsProvider)` **之前**：
先 watch 再判空，请求已经发出去了。有一条用例专门钉「游客 `calls == 0`」，
不是只钉「游客看不见」。

🛡 Story 4.3 的集合页路由本身**对游客开放**（那是当时为本 story 留的余地），
但本版游客看不到这一行、也走不到那个入口。将来若产品要给游客看推荐，
需要的是**一个对游客放行的推荐接口**，而不是把这里的判空删掉。

**实现形态**

- **AC1/AC2**：`PetRecommendationStrip` 插在 `_BerandaTop` 的**场所入口行之下、分类 chips 之上**，
  顺序 AppBar → 场所入口行 → 宠物横滑行 → chips → 瀑布流（有用例按 y 坐标钉住）。
  场所入口仍是单行导航条、这里是一行横滑卡片，**分层清晰**（UX-DR1）。
  卡片是 Story 4.1 那个 `RecommendedPetCard` **本体**，宽 150（露出半张 = 可滑的视觉暗示）。
  「查看全部」落 Story 4.3 的集合页。
- **AC3**：游客 / 池子为空 / 取不到 / 加载中 → **整行不渲染**，不留空占位、不摆错误态。
  首页最显眼的位置摆一句「暂无推荐」等于告诉用户「这儿什么都没有」。
- **AC4**：`from` 取 `explore_strip`，与另三处（`diary_empty` / `diary_non_owner` / `explore_grid`）
  两两不同（有用例钉着）。事件名与属性形状不变。

**code-review（`/code-review high`）CONFIRMED findings 已修**

1. 下拉刷新只 invalidate 顶置与 feed，**漏了推荐行**。而这条 provider 关掉了自动重试、
   又被**常驻的首页**钉着不回收 → **断网启动**那一次失败会让整行永久消失，
   下拉刷新救不回来、只能杀进程。现在下拉刷新一并 invalidate（有用例真做了一次下拉）。
2. 常驻首页无条件 watch 使 **autoDispose 形同失效**（Story 4.1 那条保障作废）：
   从场所详情/评论区拉黑某人（那条路径**不走** `author_moderation_callbacks`）之后，
   他家的宠物卡会在横滑行与 Diary 网格里一直留到重启，点进去撞 403/404。
   现在把「拉黑成功 → 推荐位重算」做成一个出口 `invalidatePetRecommendations`，
   挂在**每一处真正调 `block()` 的地方**（迷你卡 / 公开主页 / 举报后的隐藏收尾），
   并有一条源码级守卫钉住这三处都调了它。
3. 「查看全部」热区实测 131.8 × **22.0**，违反 UX-DR16（≥44×44）——
   同屏的场所入口行与 4.1 的同名入口都特意做到 ≈48。改成 `ConstrainedBox(min 44×44)`；
   ⚠️ 新增的用例量的是**实际尺寸**，因为按 key 点击的用例钉不出这条（找得到就点得到）。
4. story 文件与 sprint-status 未更新 —— 本次一并补上（含上面那段游客态决策）。

另修两处注释漂移：`place_entry_row.dart` 里「Epic 4 的横滑行**将**加在本行之下」已成事实；
`home_page.dart` 的新 import 改到与既有写法一致的位置。

**⚠️ L1/L2 待本地验收**

- **L2**：AC1/AC2/AC3 的视觉 —— 横滑行与场所入口行的分层观感（UI 稿 B1）、卡片宽度与露出半张的
  手感、空池时整行消失不留缝；以及 T4 的回归清单（通知铃角标、分类切换、游客态「Masuk」）。
- **L1**：AC4 埋点 `pet_card_tapped(from=explore_strip)` 落库。
- 🔴 **L1（性能，与 4-1 的 EXPLAIN 合并做）**：本 story 把那条**不可缓存的实时聚合**
  从「Diary 未建档用户偶发打开」变成「**每个登录用户每次启动首页**」——
  负载画像变了，必须在 staging 上按这个新口径重看执行计划。
  ⚠️ 当前横滑行与 Diary 两处**共用同一个 provider**，所以一次启动只有一个请求（不是每屏一个）；
  取的是 `DEFAULT_LIMIT=10`，在 AC1 的「6~10 张」范围内。
  🔸 若 staging 实测扛不住，**先考虑给横滑行显式传 `limit=6`**（后端 `MIN_CANDIDATE_POOL`
  的注释本就是按「4-4 只要 6 张」写的），再考虑 AC2 允许的那条 `@Scheduled` 落表降级 ——
  两者都属产品/架构决策，云端未自行做。

### File List

- `petgo_app/lib/features/profile/presentation/widgets/pet_recommendation_strip.dart`（新增：横滑行）
- `petgo_app/lib/features/content/presentation/home_page.dart`（改：插入横滑行 + 下拉刷新一并 invalidate）
- `petgo_app/lib/features/profile/presentation/widgets/recommended_pet_card.dart`（改：`kPetRecommendFromExploreStrip`）
- `petgo_app/lib/features/profile/data/pet_recommendation_repository.dart`（改：`invalidatePetRecommendations` 单一出口）
- `petgo_app/lib/shared/widgets/mini_profile_sheet.dart`（改：拉黑成功 → 推荐位重算）
- `petgo_app/lib/features/user_profile/presentation/public_profile_page.dart`（改：同上）
- `petgo_app/lib/features/content/presentation/author_moderation_callbacks.dart`（改：改用同一个出口）
- `petgo_app/lib/features/place/presentation/place_entry_row.dart`（改：注释漂移）
- `petgo_app/test/profile/pet_recommendation_strip_test.dart`（新增，15 条）
