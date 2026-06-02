# Story 3.2: 首页 Feed 内容流与宠物状态硬过滤

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **用户**,
I want **在首页看到按时间倒序、与我宠物状态匹配的内容瀑布流**,
so that **我能高效发现感兴趣的宠物内容**。

> 本 Story 是 Epic 3 的**消费侧核心**：把 Epic 2 的发布能力 + Story 3.1 的种子内容暴露为可浏览的首页 Feed。**前后端均重**——后端实现游标分页 Feed 读取 + 宠物状态硬过滤；前端实现 2 列瀑布流（UX-DR4）+ 分类 Tab Row（UX-DR5）+ 无限滚动 + 下拉刷新 + 骨架/空状态。
>
> **做**：① 全平台公开内容时间倒序读取，游标分页 `{items,nextCursor,hasMore}` 20/批；② 宠物状态硬过滤（A/C 三类全显、B 不显成长日历快乐时刻、未登录全显）；③ 状态修改即时刷新；④ 无算法、无关注关系；⑤ Feed 卡片（作者头像+昵称、正文前 2 行、首图，**点赞/评论数不在卡片**）；⑥ 瀑布流 + 分类 Tab Row（全部/日常分享/成长日历/科普，成长日历仅显示有档案帖）；⑦ 骨架屏加载态、空状态引导发布。
> **不做**：详情页（Story 3.3）、点赞/评论交互（3.4/3.5）、举报菜单（3.7）、迷你主页卡（3.8）；内容**发布**（Epic 2 Story 2.3 已做，本 Story 只读）。
> **依赖前序**：Story 1.2（设计 token + Tab Bar 外壳 + 首页容器）、Story 1.5（游客只读首页容器 + 受控门控）、Story 1.6（`pet_status` A/B/C 写入与即时刷新联动）、Story 2.3（`content_posts` 表 + 三类内容）、Story 3.1（种子内容入池——Feed 须能读到种子帖与用户帖混排）。
> **接线点**：Story 1.4 的软性登录浮层「首页浏览至第 3 页」滚动触发，由本 Story 提供的滚动深度信号接线（FR-0B）。

## Acceptance Criteria

> **验证层标注**：**L0**（编译/lint/widget test/Service 单元） · **L1**（需 postgres+redis，验真实游标分页 SQL + 硬过滤查询） · **L2**（真机/外部，模拟器手势/滚动）。
> 本 Story Feed 读取与硬过滤为 **L1**（DB 查询正确性是核心）；瀑布流布局/Tab Row/手势为前端 **L0 widget test + L2 真机**。无外部第三方依赖。

### AC1 — 时间倒序 + 宠物状态硬过滤 + 状态即时刷新

**Given** 已登录用户
**When** 加载首页 Feed
**Then** 展示全平台公开内容按发布时间倒序，按宠物状态硬过滤（A/C 三类全显、B 不显成长日历快乐时刻、未登录全显）（FR-17）
**And** 用户修改宠物状态后 Feed 即时按新状态刷新；排序为纯时间倒序、无算法推荐、无关注关系
> 验证层：**L1**（硬过滤是后端 WHERE 条件——B 状态须 `type != GROWTH_MOMENT`，A/C/游客无类型过滤；须真实建多条不同 type 帖断言返回集；时间倒序 = `created_at DESC` + 稳定游标）。状态切换即时刷新的客户端联动为 **L0**（provider invalidate）+ **L2**（真机观察）。

### AC2 — 游标分页瀑布流 + 无限滚动 + 下拉刷新 + 卡片结构

**Given** Feed 列表
**When** 用户滚动浏览
**Then** 2 列不等高瀑布流（8px 列间距、图片 80–200px 不裁切仅上圆角）（UX-DR4），游标分页每批 20 条、距底 ≤3~5 条自动加载下一批、支持下拉刷新（NFR-3）
**And** Feed 卡片含作者头像+昵称、正文前 2 行、首图（无图则纯文字卡），点赞/评论数不在卡片展示；加载时显示骨架屏（UX-DR9）
> 验证层：游标分页契约（`{items,nextCursor,hasMore}` 20/批、cursor 稳定不漏不重）为 **L1**；瀑布流布局/8px 间距/上圆角 14px/图片不裁切/骨架屏/卡片字段渲染为 **L0 widget test**；无限滚动距底预加载 + 下拉刷新手势为 **L2 真机**。

### AC3 — 分类 Tab Row（成长日历仅有档案帖）

**Given** 首页内容分类 Tab Row
**When** 用户切换分类（全部/日常分享/成长日历/科普）
**Then** active 区域色 2px 下划线、内容区 cross-fade 切换（UX-DR5）；成长日历分类仅显示有宠物档案的帖子
> 验证层：分类过滤（type 维度 + 成长日历须 join 有档案）为 **L1**（后端按 `type` 与 `pet_id is not null` 过滤）；Tab Row 视觉（下划线/cross-fade 不 slide）为 **L0 widget test** + **L2 真机**。

### AC4 — Feed 空状态

**Given** Feed 无内容
**When** 列表为空
**Then** 显示空状态「快来晒出你的毛孩子！🐾」+「发布第一条内容」CTA（FR-18、UX-DR8）
> 验证层：**L0**（widget test：空 items → 渲染 empty_state；文案双语取自 .arb，每条 ≤1 emoji）。空 CTA 点击触发发布入口（受 Story 1.5 门控：游客点击触发 FR-0C）。

---

## Tasks / Subtasks

> **三段组织**，前后端均重。建议执行顺序：后端 Feed 查询 → 前端瀑布流/Tab Row → 联调（含硬过滤矩阵 + 滚动/刷新真机）。

### 🟦 后端子任务（petgo-backend / Spring Boot — content 模块）

- [ ] **B1. Feed 读取端点（游标分页）** (AC: 1, 2, 3)
  - [ ] `GET /api/v1/content-posts`（Feed 列表）：查询参 `?cursor=...&category=...`（camelCase）；返回 `{items, nextCursor, hasMore}`，每批 **20 条**。
  - [ ] 排序：`created_at DESC, id DESC`（id 作 tie-breaker 保证游标稳定）；游标编码 `created_at + id`（不可枚举，base64 编码即可，**不暴露顺序 id 语义**给客户端做推算）。
  - [ ] 只读公开内容：`visibility=PUBLIC AND deleted_at IS NULL`；**对游客可见**（此端点是 `/api/v1` 的只读例外，架构 §API 边界，无需 JWT）。
- [ ] **B2. 宠物状态硬过滤** (AC: 1)
  - [ ] 读取调用者 `pet_status`（登录用户从 JWT/`users` 取；游客视作"全显"）：
    - A / C / 游客 → 不按 type 过滤（三类全显）。
    - B → `type != 'GROWTH_MOMENT'`（不显成长日历快乐时刻）。
  - [ ] **护栏**：硬过滤是后端权威 WHERE 条件，**不可仅靠前端隐藏**（B 用户即使构造请求也不应收到 GROWTH_MOMENT）。
  - [ ] 状态修改即时刷新：状态写入（Story 1.6/2.4）后，客户端重拉 Feed 即按新状态返回——后端无状态缓存（Redis 不做通用缓存），天然即时。
- [ ] **B3. 分类过滤（Tab Row 对应）** (AC: 3)
  - [ ] `category` ∈ {ALL, DAILY, GROWTH_MOMENT, KNOWLEDGE}；ALL=不限 type（仍受 B 硬过滤）；其余按 `type` 精确过滤。
  - [ ] 成长日历分类：`type='GROWTH_MOMENT' AND pet_id IS NOT NULL`（仅有宠物档案的帖）。
  - [ ] 分类过滤与 B2 硬过滤叠加（B 用户在「成长日历」分类下应得空集——硬过滤优先）。
- [ ] **B4. 卡片投影 DTO** (AC: 2)
  - [ ] `FeedItemResponse`(record)：`id`、`authorId`、作者 `nickname`/`avatarUrl`、正文 `bodyPreview`（前 2 行/截断由前端或后端裁；建议后端给全文 + 前端截 2 行）、`firstImageUrl`(可空，无图则纯文字卡)、`type`、`createdAt`(ISO UTC)。**不返回 likeCount/commentCount**（卡片不展示，AC2）。
  - [ ] 作者为已注销用户时（NFR-8）：`nickname`="已注销用户"、`avatarUrl`=默认占位、`authorId` 仍返回但前端点击不触发迷你卡（Story 3.8/7.3 联动）——本 Story 投影层须支持匿名化字段。
- [ ] **B5. 索引与性能** (AC: 1, 2)
  - [ ] `idx_content_posts_created_at`（含 `deleted_at`/`visibility`/`type` 复合视情况）支撑时间倒序 + 过滤；Flyway 追加迁移。

### 🟩 前端子任务（petgo_app / Flutter — features/content + shared/widgets）

- [ ] **F1. Feed 数据层** (AC: 1, 2, 3)
  - [ ] `features/content/data`：`FeedRepository` 调 `GET /content-posts`，dio 注入（游客无 token 也可调，受 auth_interceptor 放行只读）；DTO→domain 映射；游标分页状态管理。
  - [ ] `features/content/presentation`：`feedProvider`（`AsyncValue<FeedState>`，含 items/nextCursor/hasMore/loadingMore），不可变 copyWith。
- [ ] **F2. 瀑布流卡片（UX-DR4）** (AC: 2)
  - [ ] `shared/widgets/masonry_card`：2 列不等高、8px 列间距、16px 屏边距；图片区 80–200px 不裁切仅上圆角 14px；文字区 body-small 标题最多 2 行 + caption meta（作者）；**无点赞评论数**。
  - [ ] 无图帖 → 纯文字卡变体；引用 `core/theme` token，**无硬编码色/字号**。
- [ ] **F3. 无限滚动 + 下拉刷新 + 骨架屏** (AC: 2, 4)
  - [ ] 距底 ≤3~5 卡自动 `loadMore`（用 nextCursor）；标准下拉刷新 + 区域色 indicator；加载态用 Feed 骨架屏（灰 shimmer 同瀑布布局，UX-DR9）。
  - [ ] **接线 FR-0B**：暴露滚动深度信号——浏览至第 3 页时通知 Story 1.4 软性登录浮层（每 session 一次，仅游客态）。
- [ ] **F4. 分类 Tab Row（UX-DR5）** (AC: 3)
  - [ ] 横向 4 tab（全部/日常分享/成长日历/科普）；active 区域色 2px 下划线 + 文字色；切换内容区 **cross-fade**（不 slide，避免与底导航冲突）；切 tab 重置游标重拉。
- [ ] **F5. 空状态（UX-DR8）** (AC: 4)
  - [ ] `shared/widgets/empty_state`：居中 emoji + headline「快来晒出你的毛孩子！🐾」+ subtext + CTA「发布第一条内容」；文案双语 .arb，每条 ≤1 emoji；成长日历分类空状态用对应文案。
  - [ ] CTA 点击触发发布入口；游客点击触发 FR-0C（复用 Story 1.5 门控）。

### 🟨 联调验收子任务（硬过滤矩阵 + 分页 + 真机手势）

- [ ] **J1. 宠物状态硬过滤矩阵** (AC: 1 / **L1**)
  - [ ] 建多条不同 type 帖（含 GROWTH_MOMENT）；分别以 A/B/C/游客 调 Feed：A/C/游客 返回三类；B 返回集**不含任何 GROWTH_MOMENT**。切状态 A→B 后重拉，成长日历帖消失。
- [ ] **J2. 游标分页正确性** (AC: 2 / **L1**)
  - [ ] 插 ≥45 条帖；连续翻 3 批用 nextCursor，断言：每批 ≤20、无重复 id、无遗漏、时间倒序、最后一批 `hasMore=false`。
- [ ] **J3. 分类 + 成长日历有档案约束** (AC: 3 / **L1**)
  - [ ] 「成长日历」分类仅返回 `pet_id` 非空帖；B 用户在该分类下得空集（硬过滤优先）。
- [ ] **J4. 前端瀑布流/Tab Row/空态** (AC: 2, 3, 4 / L0+**L2**)
  - [ ] widget test：卡片字段渲染、无图变体、骨架屏、空状态文案、Tab 下划线。真机：滚动无限加载、下拉刷新、cross-fade 切换、距底预加载、第 3 页触发软浮层（游客）。

---

## Dev Notes

### 关键架构约定（本 Story 必须落实）

**Feed 读取模型**（架构 §Requirements Overview content / §Format Patterns）：
- **读扩散 Feed**：时间倒序 + 宠物状态硬过滤 + 无限滚动 20/批；**无关注关系、无算法推荐**（V1 不做收藏/@提及/搜索）。
- **游标分页契约**：`{items, nextCursor, hasMore}`，camelCase，时间 ISO-8601 UTC；null 字段省略（Jackson NON_NULL）。游标基于 `created_at + id` 复合，稳定防漏防重。
- Feed/详情**只读对游客可见**（FR-0A/17）——`/api/v1/content-posts` 是 `/api/v1` 默认需 JWT 的**例外**。

**宠物状态硬过滤语义**（FR-17，本 Story 核心）：
| pet_status | Feed 内容 |
|---|---|
| A 有宠物 | 三类全显 |
| C 爱好者 | 三类全显 |
| 未登录（游客） | 三类全显 |
| B 计划养 | **不显成长日历快乐时刻**（type≠GROWTH_MOMENT） |
后端 WHERE 权威过滤，前端不得仅隐藏。

**命名映射链**：DB `snake_case` ↔ camelCase；`content_posts`(type/pet_id/author_id/created_at/deleted_at/visibility)；type 枚举 UPPER_SNAKE {DAILY, KNOWLEDGE, GROWTH_MOMENT}；category 查询参 camelCase。

**注销内容匿名化在 Feed 体现**（NFR-8，架构约定）：作者已注销时 Feed 卡片显示「已注销用户」+ 默认头像，保留内容文字图片；点击该头像不触发迷你卡（Story 3.8）。本 Story 投影层须输出匿名化作者字段。

**前端**（架构 §Frontend）：Riverpod `AsyncValue` 三态（loading 骨架/data/error 顶部 banner）；副作用进 provider 不写 build 内；瀑布流/Tab Row/空态用 `shared/widgets/{masonry_card, empty_state}` + `core/theme` token。

### 强制护栏（架构 §Enforcement —— 违反即返工）

- **禁止擅自引入 MQ / 通用缓存层 / 新中间件**——Feed **不加 Redis 缓存**（Redis 仅 auth 限流/幂等/在线态/队列/角标）；时间倒序直读 PostgreSQL，500 DAU 无需缓存层。状态修改即时刷新正是因为无缓存。
- `ddl-auto=validate`，索引/表变更走 Flyway。
- 宠物状态硬过滤是**后端权威**，不可仅前端隐藏。
- 卡片**不展示点赞/评论数**（FR-17 明确），DTO 不返回这些字段。
- 模块间只经 service/事件；Feed 读 content 自有表，作者信息经 auth/profile service 或投影 join（不在 content repository 直 join 他模块表——若需作者昵称/头像，经 service 获取或在写入时冗余存储，遵守数据边界）。
- 对外暴露用不可枚举标识：游标 token 化，不让客户端推算顺序 id。

### 范围边界（防 scope creep —— 本 Story 明确不做）

- ❌ 不做详情页（Story 3.3）；卡片点击跳详情的**导航**可接占位/留给 3.3。
- ❌ 不做点赞/评论交互与计数（3.4/3.5）——卡片本就不显计数。
- ❌ 不做举报菜单（3.7，长按 context menu 留 3.7）、迷你主页卡（3.8，头像点击留 3.8，本 Story 已注销作者头像不可点是匿名化要求）。
- ❌ 不做内容发布（Epic 2 Story 2.3）。
- ✅ 只做：Feed 只读 + 游标分页 + 硬过滤 + 分类 + 瀑布流 + 无限滚动 + 下拉刷新 + 骨架 + 空态 + FR-0B 滚动接线。

### Project Structure Notes

- 后端：`com.petgo.content/{web,service,domain,repository,dto}`——`ContentFeedController`(GET 列表)、`FeedService`、`ContentPostRepository`、`FeedItemResponse`。索引迁移在 `db/migration/`。
- 前端：`lib/features/content/{data,domain,presentation}`（FeedRepository / feedProvider / FeedPage）+ `lib/shared/widgets/{masonry_card, empty_state}`（本 Story 首建这两个共享 widget，Story 3.3+ 复用）。
- 首页容器/Tab Bar 外壳来自 Story 1.2/1.5；本 Story 填充首页 Tab 的 Feed 内容区。

### References

- [Source: architecture.md#Requirements Overview / content] — 读扩散 Feed：时间倒序 + 宠物状态硬过滤 + 无限滚动 20/批；无关注/无算法/无搜索。
- [Source: architecture.md#Format Patterns] — 游标分页 `{items,nextCursor,hasMore}`、camelCase、ISO UTC、NON_NULL。
- [Source: architecture.md#Architectural Boundaries] — Feed/详情只读对游客可见（/api/v1 例外）；数据边界禁跨模块 join 他表。
- [Source: architecture.md#Enforcement Guidelines] — 禁 MQ/缓存层；Redis 收窄；硬过滤后端权威。
- [Source: epics.md#Story 3.2] — 四组原始 AC（硬过滤/瀑布分页/Tab Row/空态）。
- [Source: epics.md#FR-17] — Feed 内容模型全文（卡片点赞评论数不在卡片、20/批）。
- [Source: epics.md#UX-DR4/DR5/DR8/DR9] — 瀑布流卡片 / Tab Row cross-fade / 空状态 / 骨架屏。
- [Source: epics.md#FR-0B] — 软性登录浮层第 3 页滚动触发（本 Story 接线点）。
- [Source: epics.md#NFR-8] — 注销内容匿名化在 Feed 体现。

## Dev Agent Record

### Agent Model Used

Claude（云端 headless dev agent）

### Debug Log References

- 后端：`./mvnw -B test -Dtest=FeedServiceTest,FeedCursorTest` → 绿；`./mvnw -B -DskipTests package` → 绿。
- 前端：`flutter analyze` → No issues；`flutter test` → 94 用例全绿（含本 Story 5 个 Feed 用例 + 既有用例适配）。

### Completion Notes List

**后端（L0 绿）：**
- ✅ AC1/AC2/AC3 — `GET /api/v1/content-posts`（`ContentFeedController`）：游标分页 `{items,nextCursor,hasMore}` 20/批；`created_at DESC, id DESC` 稳定排序；游标 `FeedCursor`（base64-url 编码 `(createdAt,id)`，不暴露顺序 id）。
- ✅ AC1 — 宠物状态硬过滤（后端权威 WHERE）：B → `type <> GROWTH_MOMENT`；A/C/游客全显。登录用户从 JWT 取 `petStatus`（经 `AccountQueryService.petStatusOf`），游客视作全显。无缓存（状态修改即时刷新）。
- ✅ AC3 — 分类过滤（`FeedCategory` ALL/DAILY/GROWTH_MOMENT/KNOWLEDGE）；成长日历分类额外 `pet_id IS NOT NULL`；与 B 硬过滤叠加（B 在成长日历分类得空集）。
- ✅ AC2 — `FeedItemResponse` 投影**不含 likeCount/commentCount**；作者昵称/头像经 `AccountQueryService.findAuthorViews`（**不直 join users 表**）；注销作者匿名化（`authorDeleted=true`，昵称/头像 null）。
- ✅ Feed GET 只读对游客放行（`SecurityConfig` 加 `GET /api/v1/content-posts` permitAll，写仍需 JWT）。
- ✅ V8 复合部分索引 `(created_at DESC, id DESC) WHERE deleted_at IS NULL INCLUDE(type,pet_id)`。
- ✅ 护栏：无 MQ/缓存层；`ddl-auto=validate`；跨模块只经 service。

**前端（L0 绿）：**
- ✅ 数据层 `FeedRepository`/`feedRepositoryProvider`；`feed_item.dart`（FeedItem/FeedPage/FeedCategory）。
- ✅ `FeedController`（AsyncNotifier）：watch `feedCategoryProvider`（切 tab 重拉）+ `homeRefreshProvider`（状态变更即时刷新）；`loadMore` 游标累积；`refresh` 重建首屏；`pagesLoaded` 计数供 FR-0B。
- ✅ `MasonryCard`（作者头像+昵称、正文 2 行、首图上圆角 14px 不裁切、无图纯文字卡、**无点赞评论数**、注销作者占位+不可点）。
- ✅ `FeedMasonryView`（2 列按奇偶分列不等高、8px 列间距、16px 屏边距、距底 600px 预加载、RefreshIndicator 下拉刷新、底部 loadingMore 转圈）。
- ✅ `FeedTabRow`（4 tab、active 区域色 2px 下划线）+ 内容区 `AnimatedSwitcher` cross-fade（不 slide）。
- ✅ `FeedSkeleton`（2 列灰块骨架，UX-DR9）；`EmptyState` 扩展可选 CTA → 空态「Show off your furry friend! 🐾」+「发布第一条内容」（受 Story 1.5 门控，游客触发 FR-0C）。
- ✅ FR-0B 接线：游客浏览至第 3 页（`pagesLoaded>=3`）→ 触发软性登录浮层（`showSoftSheet`，session 去重）。
- ✅ l10n 双语 key（feedTabAll/Daily/Growth(=Moments)/Knowledge、feedEmpty*、feedDeletedUser、feedLoadError），每条 ≤1 emoji。
- 既有 Story 1.5/onboarding/widget 测试已适配（首页 placeholder → 真实 Feed，避免 tab 标签碰撞）。

**待本地验收：**
- ⏳ **L1（Docker postgres+redis）**：J1 硬过滤矩阵（A/B/C/游客 × 含 GROWTH_MOMENT 多帖）、J2 游标分页正确性（≥45 帖翻 3 批不漏不重时间倒序）、J3 成长日历分类 `pet_id` 非空 + B 空集。需真实 SQL 跑通游标 `(createdAt,id)` 比较与硬过滤 WHERE；V8 索引 `flyway migrate`。
- ⏳ **L2（真机/模拟器视觉）**：J4 瀑布流 8px 间距/上圆角 14px/图片不裁切、骨架屏、cross-fade 不 slide、无限滚动距底预加载、下拉刷新手势、第 3 页触发软浮层（游客）。云端 headless 无法渲染。

**Flyway 序号：** V8（接 V7 之后单调分配，决策 E2）。

### File List

**后端新增：** `content/service/FeedCursor.java`、`content/service/FeedService.java`、`content/domain/FeedCategory.java`、`content/dto/FeedItemResponse.java`、`content/dto/FeedPageResponse.java`、`content/web/ContentFeedController.java`、`auth/dto/AuthorView.java`、`db/migration/V8__feed_index.sql`、测试 `content/service/FeedServiceTest.java`、`content/service/FeedCursorTest.java`
**后端修改：** `content/repository/ContentPostRepository.java`（findFeed 查询）、`auth/service/AccountQueryService.java`（petStatusOf/findAuthorViews）、`shared/security/SecurityConfig.java`（GET feed 放行）
**前端新增：** `features/content/domain/feed_item.dart`、`features/content/data/feed_repository.dart`、`features/content/presentation/{feed_controller,feed_tab_row,feed_skeleton,feed_view}.dart`、`shared/widgets/masonry_card.dart`、测试 `test/content/feed_test.dart`、`test/support/fake_feed_repository.dart`
**前端修改：** `features/content/presentation/home_page.dart`（Feed 宿主）、`shared/widgets/empty_state.dart`（可选 CTA）、`l10n/app_en.arb`、`l10n/app_id.arb`、既有测试适配（`test/auth/story_1_5_gating_test.dart`、`test/auth/onboarding_test.dart`）
