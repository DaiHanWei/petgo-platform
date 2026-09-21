# Epic 3：社区 Feed 与内容互动 — 人工测试流程文档

> 版本：2026-06-10 · 覆盖 Story 3.1–3.8 · L0/L1/L2 三层

---

## 范围与页面/路由清单

| 区域 | 路由 / 页面 | 相关文件 |
|---|---|---|
| **App — 首页 Feed** | `/` (HomeTab → FeedMasonryView) | `home_page.dart`, `feed_view.dart`, `feed_tab_row.dart`, `feed_skeleton.dart`, `masonry_card.dart` |
| **App — 内容详情** | `/content/:id` (push, Tab Bar 隐藏) | `content_detail_page.dart`, `comment_section.dart`, `comment_composer.dart`, `detail_providers.dart`, `like_button.dart` |
| **App — 举报 Sheet** | BottomSheet (模态) | `report_sheet.dart` |
| **App — 迷你主页卡** | BottomSheet (模态) | `mini_profile_sheet.dart` |
| **Admin — 登录** | `GET /admin/login` | `templates/admin/login.html` |
| **Admin — 仪表盘** | `GET /admin/dashboard` | `templates/admin/dashboard.html` |
| **Admin — 种子发布** | `GET/POST /admin/seed-post` | `templates/admin/seed-post.html` |
| **Admin — 举报队列** | `GET /admin/reports` | `templates/admin/reports.html` |
| **API — Feed** | `GET /api/v1/content-posts` | 后端 ContentFeedController |
| **API — 详情** | `GET /api/v1/content-posts/{id}` | 后端 ContentDetailController |
| **API — 评论** | `GET /api/v1/content-posts/{id}/comments` | 后端 CommentQueryController |
| **API — 点赞** | `POST/DELETE /api/v1/content-posts/{id}/like` | 后端 LikeController |
| **API — 删除** | `DELETE /api/v1/content-posts/{id}` | 后端 ContentApiController |
| **API — 举报提交** | `POST /api/v1/content-posts/{id}/reports` | 后端 ReportController |
| **API — 迷你主页** | `GET /api/v1/users/{userId}/mini-profile` | 后端 MiniProfileController |

---

## 3.1 运营后台地基与种子内容发布

### TC-3.1.1 Admin 登录页渲染与账密登录
- **关联**：Story 3.1 · AC1 · B3
- **页面/入口**：`GET /admin/login` · `templates/admin/login.html`
- **前置**：后端已启动；已通过 `ADMIN_BOOTSTRAP_EMAIL`/`ADMIN_BOOTSTRAP_PASSWORD` 注入并完成开户
- **步骤**：
  1. 浏览器访问 `http://localhost:8080/admin/login`
  2. 输入正确 ADMIN 邮箱和密码，点击登录按钮
  3. 检查跳转目标页面
- **预期**：页面正常渲染登录表单；提交后跳转至 `/admin/dashboard`，顶部/侧边显示导航壳
- **层级**：L1

### TC-3.1.2 Admin 登录 — 错误凭证拒绝
- **关联**：Story 3.1 · AC1 · B2
- **页面/入口**：`GET /admin/login` · `templates/admin/login.html`
- **前置**：后端启动
- **步骤**：
  1. 访问 `/admin/login`
  2. 输入错误密码，提交
- **预期**：停留登录页，显示认证失败提示；不跳转到后台
- **层级**：L1

### TC-3.1.3 未登录游客访问 Admin 后台跳转登录页
- **关联**：Story 3.1 · AC1 · B2
- **页面/入口**：`GET /admin/dashboard`
- **前置**：无会话（未登录）
- **步骤**：
  1. 不登录直接访问 `http://localhost:8080/admin/dashboard`
  2. 观察跳转行为
- **预期**：自动跳转至 `/admin/login`，不显示后台内容
- **层级**：L1

### TC-3.1.4 User/Vet 角色 JWT 访问 Admin 返回 403
- **关联**：Story 3.1 · AC1 · B2 · 护栏
- **页面/入口**：`GET /admin/dashboard`
- **前置**：持有 `role=USER` 或 `role=VET` 的有效 JWT
- **步骤**：
  1. 携带 User JWT 发起 `GET /admin/dashboard`
  2. 携带 Vet JWT 发起 `GET /admin/dashboard`
  3. 发起无 Token 的 Bearer 请求访问 `/admin/dashboard`
- **预期**：三种情况均返回 403（越权，非 401）；ProblemDetail 语义正确；ADMIN 端点绝不混入公开 `/api/v1`
- **层级**：L1

### TC-3.1.5 Admin 后台导航壳预留入口位
- **关联**：Story 3.1 · AC1 · B3 · J3
- **页面/入口**：`GET /admin/dashboard` · `templates/admin/layout.html`
- **前置**：ADMIN 已登录
- **步骤**：
  1. 登录后查看侧边导航壳
  2. 检查各导航项的存在性和状态
- **预期**：导航壳可见「种子内容发布」（可点击，本 Story 已实现）、「举报队列」（链接至 `/admin/reports`，3.7 实现后激活）、「兽医账号」「评分查看」（Epic 5，占位可见 disabled 或占位页）；四个入口位均已预留
- **层级**：L1

### TC-3.1.6 种子内容发布 — 三类内容各一条成功入库
- **关联**：Story 3.1 · AC2 · B4 · J2
- **页面/入口**：`GET/POST /admin/seed-post` · `templates/admin/seed-post.html`
- **前置**：ADMIN 已登录；已有公开桶图片 URL 可粘贴
- **步骤**：
  1. 访问 `/admin/seed-post`
  2. 选择内容类型「日常分享（DAILY）」，输入正文，可选填图片 URL，提交
  3. 再次发布「专业知识科普（KNOWLEDGE）」一条
  4. 再次发布「成长日历快乐时刻（GROWTH_MOMENT）」一条
  5. 查询数据库 `content_posts` 表
- **预期**：3 行落库；`type` 分别为 `DAILY`/`KNOWLEDGE`/`GROWTH_MOMENT`（UPPER_SNAKE）；`author_id` 指向运营 ADMIN 账号；表中**无任何 seed/official 区分列**；与普通用户帖字段结构一致
- **层级**：L1

### TC-3.1.7 种子内容发布 — 正文超 1000 字被服务端拒绝
- **关联**：Story 3.1 · AC2 · B4 · J2
- **页面/入口**：`POST /admin/seed-post`
- **前置**：ADMIN 已登录
- **步骤**：
  1. 访问种子发布表单
  2. 输入超过 1000 字符的正文，提交
- **预期**：服务端拒绝（表单内联错误提示或回显）；不写入数据库；停留在发布页
- **层级**：L1

### TC-3.1.8 种子内容发布 — 图片超 9 张被服务端拒绝
- **关联**：Story 3.1 · AC2 · B4 · J2
- **页面/入口**：`POST /admin/seed-post`
- **前置**：ADMIN 已登录
- **步骤**：
  1. 在种子发布表单填入 10 个图片 URL，提交
- **预期**：服务端拒绝；不写入数据库；表单内联错误回显
- **层级**：L1

### TC-3.1.9 种子内容与用户内容混排不打标记（验证结构一致）
- **关联**：Story 3.1 · AC2 · J2
- **页面/入口**：数据库 `content_posts`
- **前置**：已有至少 1 条普通用户帖（通过用户发布流程或手工插入）+ 1 条种子帖
- **步骤**：
  1. 查询 `SELECT * FROM content_posts ORDER BY created_at DESC LIMIT 5`
  2. 对比种子帖与用户帖的列集合
- **预期**：种子帖与用户帖列数相同，均无 `is_seed`/`is_official`/`source_type` 等区分字段；仅 `author_id` 不同
- **层级**：L1

---

## 3.2 首页 Feed 内容流与宠物状态硬过滤

### TC-3.2.1 Feed 首屏渲染 — 骨架屏加载态
- **关联**：Story 3.2 · AC2 · F3 · UX-DR9
- **页面/入口**：`/` (首页 Feed) · `feed_skeleton.dart`
- **前置**：网络正常，App locale = en；用户已登录
- **步骤**：
  1. 打开 App 进入首页，立即观察页面
- **预期**：加载期间显示灰色 shimmer 骨架屏（与瀑布流 2 列布局一致）；无白屏无崩溃；加载完成后骨架消失，真实 Feed 卡片出现
- **层级**：L0（widget test）/ L2（真机视觉）

### TC-3.2.2 Feed Tab Row — 四个分类 Tab 双语文案
- **关联**：Story 3.2 · AC3 · F4 · UX-DR5
- **页面/入口**：首页 Feed Tab Row · `feed_tab_row.dart`
- **前置**：App locale = en；切换 locale = id
- **步骤**：
  1. en 模式打开首页，观察 Tab Row 4 个标签文案
  2. 切换系统语言到 Indonesian，重启或热重载
  3. 观察 Tab Row 文案变化
- **预期**：en — "All" / "Daily" / "Moments" / "Tips"；id — "Semua" / "Harian" / "Tumbuh" / "Edukasi"；active Tab 有区域色 2px 下划线
- **层级**：L0（widget test）

### TC-3.2.3 Feed 卡片结构 — 包含作者信息、正文预览、首图、无点赞评论数
- **关联**：Story 3.2 · AC2 · F2 · FR-17
- **页面/入口**：首页 Feed · `masonry_card.dart`
- **前置**：DB 中有含图片的用户帖和无图用户帖各 1 条；App 已登录
- **步骤**：
  1. 进入首页 Feed
  2. 观察含图片卡片的组成元素
  3. 观察无图片卡片的组成元素
- **预期**：
  - 含图卡片：作者头像 + 昵称、正文前 2 行（截断）、首图（上圆角 14px，不裁切）；**无点赞数、无评论数**（FR-17 明确）
  - 无图卡片：纯文字变体，显示作者信息 + 正文前 2 行；**无计数**
- **层级**：L0（widget test）/ L2（真机视觉）

### TC-3.2.4 Feed 瀑布流布局 — 2 列不等高，8px 列间距，16px 屏边距
- **关联**：Story 3.2 · AC2 · F2 · UX-DR4
- **页面/入口**：首页 Feed · `feed_view.dart`, `masonry_card.dart`
- **前置**：DB 中有 ≥10 条内容；App 真机
- **步骤**：
  1. 进入首页 Feed
  2. 视觉检查布局
- **预期**：2 列不等高瀑布流；列间距约 8px；左右各 16px 屏边距；图片区域 80–200px 高度范围；**图片仅上圆角 14px**，不裁切变形
- **层级**：L2（真机视觉）

### TC-3.2.5 Feed 宠物状态硬过滤 — B 态（PLANNING）不显 GROWTH_MOMENT
- **关联**：Story 3.2 · AC1 · B2 · J1 · FR-17
- **页面/入口**：`GET /api/v1/content-posts` 后端 API
- **前置**：DB 中预置若干帖：含 DAILY、KNOWLEDGE、GROWTH_MOMENT 各至少 2 条；准备 4 种请求：PLANNING 用户 JWT / HAS_PET 用户 JWT / ENTHUSIAST 用户 JWT / 无 JWT（游客）
- **步骤**：
  1. 用 `PLANNING` 状态用户 JWT 调 `GET /api/v1/content-posts`
  2. 检查返回的 items 中是否包含 `type=GROWTH_MOMENT` 的帖
  3. 用 `HAS_PET` 用户 JWT 调同一接口
  4. 用 `ENTHUSIAST` 用户 JWT 调同一接口
  5. 不带 JWT 调（游客）
- **预期**：
  - `PLANNING`：items 中**无任何** `type=GROWTH_MOMENT`（后端 WHERE `type <> 'GROWTH_MOMENT'` 权威过滤）
  - `HAS_PET`：三类全显，含 GROWTH_MOMENT
  - `ENTHUSIAST`：三类全显，含 GROWTH_MOMENT
  - 游客（无 JWT）：三类全显（公开接口，无强制登录）
- **层级**：L1

### TC-3.2.6 Feed 宠物状态切换后即时刷新
- **关联**：Story 3.2 · AC1 · B2 · J1
- **页面/入口**：首页 Feed
- **前置**：用户处于 `HAS_PET` 状态，Feed 中有 GROWTH_MOMENT 类型帖可见
- **步骤**：
  1. 用户在首页能看到 GROWTH_MOMENT 类型帖（成长日历快乐时刻）
  2. 进入个人设置，将宠物状态改为 `PLANNING`
  3. 返回首页 Feed
- **预期**：Feed 按新状态即时刷新，GROWTH_MOMENT 类型帖从列表消失；无需重启 App；无 Redis 缓存层（直读 PostgreSQL，修改即时生效）
- **层级**：L1 + L2

### TC-3.2.7 Feed 分类 Tab — 「成长日历（Moments）」仅显示 pet_id 非空帖
- **关联**：Story 3.2 · AC3 · B3 · J3
- **页面/入口**：`GET /api/v1/content-posts?category=GROWTH_MOMENT` · 首页 Feed Tab
- **前置**：DB 中有 GROWTH_MOMENT 帖：含 `pet_id` 非空的 2 条，`pet_id` 为空的 1 条
- **步骤**：
  1. 调 `GET /api/v1/content-posts?category=GROWTH_MOMENT`
  2. 检查返回 items
- **预期**：仅返回 `pet_id IS NOT NULL` 的帖；`pet_id` 为空的 GROWTH_MOMENT 帖不出现
- **层级**：L1

### TC-3.2.8 Feed 分类 Tab — PLANNING 用户在「成长日历」分类得空集
- **关联**：Story 3.2 · AC3 · B2+B3 · J3
- **页面/入口**：`GET /api/v1/content-posts?category=GROWTH_MOMENT` + PLANNING 用户 JWT
- **前置**：DB 中有 GROWTH_MOMENT 类型且 pet_id 非空的帖
- **步骤**：
  1. 用 PLANNING 用户 JWT 调 `GET /api/v1/content-posts?category=GROWTH_MOMENT`
- **预期**：返回 items 为空数组（硬过滤 B + 分类过滤叠加 → 空集）；`hasMore=false`
- **层级**：L1

### TC-3.2.9 Feed 游标分页 — 翻 3 批不漏不重，时间倒序
- **关联**：Story 3.2 · AC2 · B1 · J2
- **页面/入口**：`GET /api/v1/content-posts`（游标分页）
- **前置**：DB 中预置 ≥45 条 PUBLIC 帖，created_at 各不相同
- **步骤**：
  1. `GET /api/v1/content-posts` 取第 1 批，记录 items 的 id 集合 S1 和 nextCursor
  2. `GET /api/v1/content-posts?cursor=<nextCursor>` 取第 2 批，记录 S2 和 nextCursor
  3. 用第 2 批 nextCursor 取第 3 批，记录 S3
  4. 验证 S1 ∪ S2 ∪ S3 中无重复 id，时间倒序，每批 ≤20 条
  5. 取完所有批次后验证 `hasMore=false`
- **预期**：
  - 每批 ≤20 条
  - S1/S2/S3 无交集（无重复 id）
  - items 内 createdAt 单调递减（时间倒序）
  - 最终批次 `hasMore=false`
  - 游标 base64 编码，不可直接推断顺序 id
- **层级**：L1

### TC-3.2.10 Feed 分类 Tab 切换 — cross-fade 动效，不 slide
- **关联**：Story 3.2 · AC3 · F4 · UX-DR5
- **页面/入口**：首页 Feed Tab Row · `feed_view.dart`
- **前置**：真机；多条不同类型内容
- **步骤**：
  1. 点击 Tab Row 从「All」切换到「Daily」，观察内容区切换动效
  2. 再切换到「Moments」
- **预期**：内容区以 **cross-fade（淡入淡出）**过渡，**不出现左右 slide 动效**（避免与底部导航冲突）；切 Tab 重置游标重拉
- **层级**：L2（真机视觉）

### TC-3.2.11 Feed 无限滚动 — 距底预加载下一批
- **关联**：Story 3.2 · AC2 · F3
- **页面/入口**：首页 Feed · `feed_view.dart`
- **前置**：DB 中有 ≥40 条帖；真机
- **步骤**：
  1. 进入首页 Feed，加载第一批
  2. 滚动列表，接近底部（≤3~5 条未显示）
  3. 观察是否自动加载下一批
- **预期**：列表接近底部时自动触发 loadMore，底部出现加载 indicator（转圈）；无需用户手动操作；新内容接续显示
- **层级**：L2（真机手势）

### TC-3.2.12 Feed 下拉刷新
- **关联**：Story 3.2 · AC2 · F3 · NFR-3
- **页面/入口**：首页 Feed · `feed_view.dart`
- **前置**：真机；Feed 已加载
- **步骤**：
  1. 在 Feed 列表顶部下拉
  2. 释放后等待刷新完成
- **预期**：出现标准刷新 indicator（区域色）；刷新后从第 1 批重新加载；游标重置；已有帖按最新时间倒序排列
- **层级**：L2（真机手势）

### TC-3.2.13 Feed 空状态 — en/id 双语文案
- **关联**：Story 3.2 · AC4 · F5 · UX-DR8
- **页面/入口**：首页 Feed (空数据) · `empty_state.dart`
- **前置**：DB 无任何 PUBLIC 帖（或清空后）；分别测试 en 和 id locale
- **步骤**：
  1. en locale 下打开首页 Feed
  2. 检查空状态文案
  3. 切换为 id locale，重复
- **预期**：
  - en：「Show off your furry friend! 🐾」+ 「Be the first to share a pet moment」+ CTA「Post your first story」
  - id：「Pamerkan si bulu! 🐾」+ 「Jadilah yang pertama berbagi momen hewan」+ CTA「Posting cerita pertamamu」
  - 每条 ≤1 emoji
- **层级**：L0（widget test）

### TC-3.2.14 Feed 空状态 CTA — 游客点击触发 FR-0C 强登录弹窗
- **关联**：Story 3.2 · AC4 · F5 · FR-0C · Story 1.5
- **页面/入口**：首页 Feed 空状态 · `empty_state.dart`
- **前置**：未登录游客；Feed 为空
- **步骤**：
  1. 游客状态下进入空状态 Feed
  2. 点击「Post your first story」CTA
- **预期**：弹出强登录引导弹窗（FR-0C），而非跳转发布页；不崩溃
- **层级**：L0（widget test）

### TC-3.2.15 Feed 首屏加载失败 — 显示失败态 + 重试入口
- **关联**：Story 3.2 · AC5 · F13
- **页面/入口**：首页 Feed · `home_page.dart`
- **前置**：模拟网络中断或后端返回 5xx；无已加载内容
- **步骤**：
  1. 断网 / mock 返回错误
  2. 打开首页 Feed
  3. 等待失败
  4. 点击重试入口
- **预期**：
  - 显示「Could not load the feed, pull to retry」（en）/「Gagal memuat feed, tarik untuk coba lagi」（id）
  - 出现显式重试按钮（`feedRetry`）
  - 支持下拉刷新重试
  - 不崩溃，不白屏
  - 点击重试后重新请求首屏
- **层级**：L0（widget test）

### TC-3.2.16 Feed 增量加载失败 — 保留已加载内容，底部显示重试
- **关联**：Story 3.2 · AC5 · F13 · `feedLoadMoreError`
- **页面/入口**：首页 Feed · `feed_view.dart`
- **前置**：已加载第 1 批（有数据显示）；第 2 批请求失败（mock 错误）
- **步骤**：
  1. Feed 已显示第 1 批内容（≥10 条）
  2. 滚动到底部触发 loadMore
  3. loadMore 失败
  4. 观察已加载内容是否保留
  5. 点击底部重试提示
- **预期**：
  - 已加载的第 1 批内容**完整保留，不清空**
  - 列表底部出现「Failed to load more, tap to retry」（en）/「Gagal memuat lagi, ketuk untuk coba lagi」（id）
  - 停止自动距底预加载（避免静默重试循环）
  - 点击底部重试后沿用相同 nextCursor 续拉（不回顶，不重拉首屏）
- **层级**：L0（widget test）

### TC-3.2.17 Feed 游客浏览至第 3 页触发软登录浮层（FR-0B）
- **关联**：Story 3.2 · F3 · FR-0B · Story 1.4
- **页面/入口**：首页 Feed · `feed_view.dart`（pagesLoaded 计数）
- **前置**：未登录游客；DB 中有 ≥60 条帖（可翻 3 页）
- **步骤**：
  1. 游客状态打开首页
  2. 持续滚动触发 loadMore，累计翻 3 批（pagesLoaded ≥ 3）
  3. 观察是否出现软登录浮层
- **预期**：
  - 第 3 页（pagesLoaded ≥ 3）首次触发时弹出软登录浮层（`login_soft_sheet.dart`）
  - 同一 session 只弹一次（session 去重）
  - 浮层不阻断滚动，用户可关闭继续浏览
- **层级**：L0（provider 测试）/ L2（真机）

### TC-3.2.18 Feed 卡片 — 注销作者显示「已注销用户」且头像不可点
- **关联**：Story 3.2 · B4 · NFR-8
- **页面/入口**：首页 Feed · `masonry_card.dart`
- **前置**：DB 中有 1 条内容，其 `author_id` 对应的用户已注销（`authorDeleted=true` 或 nickname/avatar 匿名化）
- **步骤**：
  1. 进入首页 Feed
  2. 找到已注销用户的内容卡片
  3. 点击该卡片的作者头像/昵称区域
- **预期**：
  - 卡片显示「已注销用户」（`feedDeletedUser` l10n key）+ 默认占位头像
  - **点击注销作者头像不触发迷你主页卡**（`mini_profile_sheet` 不弹出）
  - 内容文字/图片正常显示
- **层级**：L0（widget test）/ L1

### TC-3.2.19 Feed API 契约一致性 — FeedResponseContractTest
- **关联**：Story 3.2 · C5 · FeedResponseContractTest
- **页面/入口**：后端 L0 契约测试
- **前置**：L0 编译环境
- **步骤**：
  1. 运行 `./mvnw -B test -Dtest=FeedResponseContractTest`
- **预期**：
  - `FeedPageResponse` / `FeedItemResponse` 字段集通过 Jackson 序列化金标测试
  - App `mock_backend.dart` 与 data DTO 字段对齐（C5）
  - **不含 likeCount/commentCount**（FR-17 卡片不显计数）
- **层级**：L0

---

## 3.3 内容详情页

### TC-3.3.1 详情页结构 — 从 Feed 卡片 push 进入
- **关联**：Story 3.3 · AC1 · F1
- **页面/入口**：`/content/:id` · `content_detail_page.dart`
- **前置**：DB 有含 3 张图片的帖；用户已登录
- **步骤**：
  1. 在 Feed 点击内容卡片
  2. 检查详情页各区块
- **预期**：
  - 顶部导航栏：返回按钮 + 「···」菜单
  - 作者信息区：头像 + 昵称（可点击区域已预留）
  - 正文全文
  - 多图 PageView（左右滑动，角标「1/3」）
  - 互动栏：点赞按钮+数（3.4 接入后真实）、评论数
  - 评论区（列表）
  - 底部固定评论输入框
  - Tab Bar **隐藏**（push 路由，二级页行为）
- **层级**：L0（widget test）/ L1

### TC-3.3.2 详情页 — 多图左右滑，角标 x/y 正确
- **关联**：Story 3.3 · AC1 · F2
- **页面/入口**：`/content/:id` · `content_detail_page.dart`
- **前置**：DB 有含 3 张图片的帖；真机
- **步骤**：
  1. 进入该帖详情页
  2. 左右滑动图片 PageView
  3. 点击图片进全屏 lightbox
- **预期**：
  - 滑至第 2 张图时角标显示「2/3」
  - 滑至第 3 张图时角标显示「3/3」
  - 点击图片进入全屏 lightbox 浏览（UX-DR12）
- **层级**：L2（真机手势）

### TC-3.3.3 详情页 — 返回 Feed 保持滚动位置
- **关联**：Story 3.3 · AC2 · F4
- **页面/入口**：首页 Feed → `/content/:id` → 返回
- **前置**：DB 有 ≥30 条帖；真机
- **步骤**：
  1. 进入首页 Feed，滚动至中间位置（约第 15 条可见）
  2. 点击某条帖卡片进入详情页
  3. 在详情页点击返回
  4. 观察 Feed 滚动位置
- **预期**：返回 Feed 后**保持原来滚动位置**，不回顶；PageStorageKey 或 ScrollController 保位生效（go_router push 非 replace，Feed 页 Widget 未被销毁）
- **层级**：L2（真机）

### TC-3.3.4 评论区 — 一级评论首批 10 条 + 「查看更多评论」
- **关联**：Story 3.3 · AC3 · B3 · J2
- **页面/入口**：`GET /api/v1/content-posts/{id}/comments` · `comment_section.dart`
- **前置**：某帖有 25 条一级评论（时间正序），某一级有 8 条二级
- **步骤**：
  1. 进入该帖详情页
  2. 观察评论区首次加载
  3. 点击「View more comments」
  4. 在有 8 条二级的一级评论下点「View all 8 replies」
- **预期**：
  - 首次加载 10 条一级评论，按时间**正序**排列（区别于 Feed 倒序）
  - 显示「View more comments」（en）/ 「Lihat komentar lainnya」（id）
  - 点击后加载下 10 条
  - 某一级评论默认显示前 3 条二级 + 「View all 8 replies」
  - 展开后显示全部 8 条二级
- **层级**：L1 + L0（widget test）

### TC-3.3.5 评论区 — 空评论状态
- **关联**：Story 3.3 · AC3 · F3 · 多态完整性
- **页面/入口**：`/content/:id` · `comment_section.dart`
- **前置**：某帖无任何评论
- **步骤**：
  1. 进入无评论帖的详情页
  2. 观察评论区
- **预期**：显示空评论提示「No comments yet」（en）/ 「Belum ada komentar」（id）；不崩溃；底部评论框正常可用
- **层级**：L0（widget test）

### TC-3.3.6 评论框 — 未登录游客点击触发 FR-0C
- **关联**：Story 3.3 · AC3 · F3 · FR-0C
- **页面/入口**：`/content/:id` · `comment_section.dart` / `comment_composer.dart`
- **前置**：未登录游客
- **步骤**：
  1. 游客进入任一帖详情页
  2. 点击底部评论框
- **预期**：不聚焦输入，弹出 FR-0C 强登录引导弹窗；游客无法直接输入评论
- **层级**：L0（widget test）

### TC-3.3.7 详情页「···」菜单 — 他人内容仅显举报，自己内容仅显删除（互斥）
- **关联**：Story 3.3 · AC5 · F1 · AC5 R2 回改
- **页面/入口**：`/content/:id` · `content_detail_page.dart`
- **前置**：分别用「内容作者」身份和「他人」身份进入同一帖详情页
- **步骤**：
  1. 以**内容作者**身份打开该帖详情页，点击「···」菜单
  2. 以**他人**身份打开同一帖详情页，点击「···」菜单
  3. 以**游客**身份打开该帖详情页，点击「···」→「举报」
- **预期**：
  - 作者（`isAuthor=true`）：菜单仅「Delete」（`detailMenuDelete`），**无举报项**
  - 他人（`isAuthor=false`）：菜单仅「Report」（`detailMenuReport`），**无删除项**
  - 两者绝不同时出现
  - 游客点举报 → 触发 FR-0C（不弹举报 sheet）
  - 注销作者帖 → 归入「他人分支」（显举报，无删除）
- **层级**：L0（widget test）

### TC-3.3.8 多态 — 404 不存在内容显示占位页
- **关联**：Story 3.3 · AC4 · F5 · UX-DR18 ④
- **页面/入口**：`/content/{不存在的id}` · `content_detail_page.dart`
- **前置**：使用一个不存在的帖 id（如 `99999999`）
- **步骤**：
  1. 直接导航到 `/content/99999999`
- **预期**：
  - 显示「This content no longer exists」（en）/「Konten ini sudah tidak ada」（id）+ 返回 Feed 按钮
  - **不崩溃，不白屏**
  - 文案统一，不暴露「该 id 是否曾存在」（防枚举）
- **层级**：L0（widget test）/ L1

### TC-3.3.9 多态 — 软删帖（deleted_at 非空）访问返回 404
- **关联**：Story 3.3 · AC4 · B2 · J3
- **页面/入口**：`GET /api/v1/content-posts/{id}`（已软删帖）
- **前置**：DB 中有 `deleted_at IS NOT NULL` 的帖 id
- **步骤**：
  1. 用有效 JWT 调 `GET /api/v1/content-posts/{id}`（该帖已软删）
- **预期**：返回 404 ProblemDetail；统一文案不区分「从未存在」与「曾存在但已删」（防枚举）
- **层级**：L1

### TC-3.3.10 多态 — 已注销作者但内容留存返回 200 且匿名化
- **关联**：Story 3.3 · AC4 · B2 · NFR-8 · J3
- **页面/入口**：`GET /api/v1/content-posts/{id}`（作者已注销但内容匿名保留）
- **前置**：某帖作者账号已注销，内容匿名化保留（`authorDeleted=true`）
- **步骤**：
  1. 调 `GET /api/v1/content-posts/{id}`
- **预期**：
  - 返回 **200**（而非 404，区分「内容被删」与「作者注销」）
  - 响应中 nickname=null（或「已注销用户」）、avatarUrl=null、`authorDeleted=true`
  - 内容正文/图片正常返回
- **层级**：L1

### TC-3.3.11 多态 — 403 越权访问显示无权限页
- **关联**：Story 3.3 · AC4 · F5 · UX-DR18 ⑤
- **页面/入口**：`/content/:id` · `content_detail_page.dart`
- **前置**：模拟后端对某帖返回 403
- **步骤**：
  1. Mock/触发 403 响应
  2. 打开该帖详情页
- **预期**：显示「You don't have access to this content」（en）/「Anda tidak punya akses ke konten ini」（id）+ 返回按钮；不崩溃
- **层级**：L0（widget test）

### TC-3.3.12 详情页 — 网络错误骨架后显示加载失败
- **关联**：Story 3.3 · AC4 · F5 · F13
- **页面/入口**：`/content/:id` · `content_detail_page.dart`
- **前置**：模拟网络错误（非 404/403）
- **步骤**：
  1. Mock 网络错误
  2. 进入任一帖详情页
- **预期**：
  - 短暂骨架屏后显示「Could not load, please try again」（en）/「Gagal memuat, silakan coba lagi」（id）
  - 有重试入口（或返回 Feed 按钮）
  - 不白屏不崩溃
- **层级**：L0（widget test）

### TC-3.3.13 详情页 — 游客只读可访问（无 JWT 可请求详情）
- **关联**：Story 3.3 · B1 · FR-0A · 架构 §API 边界例外
- **页面/入口**：`GET /api/v1/content-posts/{id}`（无 JWT）
- **前置**：不携带任何 Authorization header
- **步骤**：
  1. 无 JWT 调 `GET /api/v1/content-posts/{id}`（存在且 PUBLIC 帖）
- **预期**：返回 200；`liked=false`；`isAuthor=false`；内容正常返回（此端点是公开例外）
- **层级**：L1

---

## 3.4 内容点赞

### TC-3.4.1 点赞 — 登录用户在详情页点赞，likeCount+1
- **关联**：Story 3.4 · AC1 · B2 · J1
- **页面/入口**：`POST /api/v1/content-posts/{id}/like` · `like_button.dart`
- **前置**：DB 有 DAILY 类型帖，用户未点赞该帖
- **步骤**：
  1. 进入帖详情页，记录初始 likeCount（如 5）
  2. 点击点赞按钮（心形）
  3. 观察 likeCount 变化
- **预期**：likeCount 变为 6，按钮变为「已赞」状态；后端 `content_likes` 表新增一行；`liked=true`
- **层级**：L0（widget test 乐观更新）/ L1（真实写库）

### TC-3.4.2 取消点赞 — likeCount-1
- **关联**：Story 3.4 · AC1 · B2 · J1
- **页面/入口**：`DELETE /api/v1/content-posts/{id}/like` · `like_button.dart`
- **前置**：用户已对该帖点赞（`liked=true`）
- **步骤**：
  1. 进入已点赞帖详情页
  2. 再次点击点赞按钮（取消）
- **预期**：likeCount 减 1，按钮回到未赞状态；`content_likes` 表中该行删除；`liked=false`
- **层级**：L0（widget test）/ L1

### TC-3.4.3 点赞幂等 — 重复 POST 不叠加
- **关联**：Story 3.4 · AC1 · B2 · J1 · 护栏
- **页面/入口**：`POST /api/v1/content-posts/{id}/like`（并发/重复）
- **前置**：用户已点赞该帖（likeCount=1）
- **步骤**：
  1. 已赞状态下再次 POST `/like`（手动调用）
- **预期**：likeCount 仍为 1（不叠加）；返回当前态 200；`content_likes` 唯一约束 `uq_content_likes_post_user` 兜底防双行
- **层级**：L1

### TC-3.4.4 点赞 — 未登录触发 FR-0C
- **关联**：Story 3.4 · AC1 · F1 · FR-0C
- **页面/入口**：`/content/:id` · `like_button.dart`
- **前置**：未登录游客
- **步骤**：
  1. 游客进入详情页
  2. 点击点赞按钮
- **预期**：弹出 FR-0C 强登录引导弹窗；**不发送点赞请求**；不崩溃
- **层级**：L0（widget test）

### TC-3.4.5 点赞乐观更新 — 失败时回滚
- **关联**：Story 3.4 · AC1 · F1
- **页面/入口**：`/content/:id` · `like_button.dart`
- **前置**：网络模拟故障；用户已登录
- **步骤**：
  1. 进入详情页，记录 likeCount = 5
  2. 点击点赞（乐观更新：UI 瞬间变为 6）
  3. 请求失败
- **预期**：乐观更新 UI 立即翻转为 6；请求失败后 UI 回滚回 5，`liked` 状态复原；提示失败 toast 或无声回滚
- **层级**：L0（widget test）

### TC-3.4.6 三类内容均可点赞
- **关联**：Story 3.4 · AC1 · J2
- **页面/入口**：`POST /api/v1/content-posts/{id}/like`
- **前置**：各类型帖各 1 条（DAILY/KNOWLEDGE/GROWTH_MOMENT）
- **步骤**：
  1. 分别对 DAILY / KNOWLEDGE / GROWTH_MOMENT 帖各发一次 POST `/like`
- **预期**：三次均返回成功，likeCount 各+1；无类型限制
- **层级**：L1

### TC-3.4.7 点赞互动事件 — 自赞不产生事件
- **关联**：Story 3.4 · AC2 · B3 · J3 · FR-22B
- **页面/入口**：后端 `LikeService`（`ApplicationEventPublisher`）
- **前置**：用户 A 对自己帖点赞；用户 B 对用户 A 帖点赞（L1 环境 + 事件监听断言）
- **步骤**：
  1. 用户 B 点赞 A 的帖 → 捕获 `ContentLikedEvent`
  2. 用户 A 点赞自己的帖 → 检查事件是否触发
- **预期**：
  - B 赞 A：`ContentLikedEvent` 携带 `postId`/`likerId=B`/`authorId=A`
  - A 赞自己（`likerId==authorId`）：**不发布任何 `ContentLikedEvent`**
- **层级**：L1（`@RecordApplicationEvents` 或测试监听）

### TC-3.4.8 Feed 卡片无点赞按钮/计数
- **关联**：Story 3.4 · AC1 范围澄清 · FR-17
- **页面/入口**：首页 Feed · `masonry_card.dart`
- **前置**：Feed 中有已被多人点赞的帖
- **步骤**：
  1. 浏览首页 Feed，观察卡片
- **预期**：瀑布流卡片**无点赞按钮，无点赞数**（FR-17 明确，点赞只在详情页）
- **层级**：L0（widget test，断言 LikeButton 不在 MasonryCard 树中）

---

## 3.5 内容两级评论

### TC-3.5.1 发表一级评论 — 即时显示，时间正序
- **关联**：Story 3.5 · AC1 · B2 · F1 · J1
- **页面/入口**：`POST /api/v1/content-posts/{postId}/comments` · `comment_composer.dart`
- **前置**：用户已登录；目标帖存在
- **步骤**：
  1. 进入帖详情页
  2. 在底部评论框输入「Hello！」，点击发送
  3. 观察评论区
- **预期**：新评论即时出现在评论区末尾（时间正序，最后发布的在最下方）；`comments` 表新增行，`parent_id=null`（一级）；评论数+1
- **层级**：L0（widget test，乐观插入）/ L1（真实写库）

### TC-3.5.2 回复二级评论 — 挂在正确一级下，不产生三级
- **关联**：Story 3.5 · AC1 · B1 · J1
- **页面/入口**：`POST /api/v1/comments/{parentId}/replies` · `comment_section.dart`
- **前置**：帖有 1 条一级评论 C1，C1 下有 1 条二级回复 C2
- **步骤**：
  1. 点击 C1 的「Reply」（一级回复）→ 底部框切回复态「Reply @作者名」→ 发送
  2. 点击 C2 的「Reply」（回复二级评论）→ 发送
- **预期**：
  - 步骤 1：新二级评论挂在 C1 下，`parent_id=C1.id`
  - 步骤 2：回复二级 C2 时，**归并到 C1**（`parent_id=C1.id`，而非 C2.id）；**不产生三级**；service 层强制归并
- **层级**：L1

### TC-3.5.3 评论超 200 字 — 服务端返回 422
- **关联**：Story 3.5 · AC1 · B2 · J1
- **页面/入口**：`POST /api/v1/content-posts/{postId}/comments`
- **前置**：用户已登录
- **步骤**：
  1. 构造 201 字以上的正文，POST 一级评论
- **预期**：后端返回 422 ProblemDetail；评论不写库；客户端实时字数计数（体验层）在 200 字时已警告
- **层级**：L1

### TC-3.5.4 删除权限矩阵 — 评论作者删自己 / 内容作者删他人 / 无关者 403
- **关联**：Story 3.5 · AC2 · B3 · J2
- **页面/入口**：`DELETE /api/v1/comments/{id}`
- **前置**：帖 P 属于用户 A；用户 B 在 P 下发了一级评论 C1；用户 C 尝试删 C1
- **步骤**：
  1. 用户 B（评论作者）DELETE `/comments/C1.id` → 验期望
  2. 用户 A（内容作者）DELETE `/comments/C1.id`（另一条评论）→ 验期望
  3. 用户 C（无关用户）DELETE `/comments/C1.id` → 验期望
  4. 未登录 DELETE → 验期望
- **预期**：
  - B 删自己评论 → 200/204 成功，软删 `deleted_at` 置位
  - A 删他人在其内容下的评论 → 200/204 成功
  - C 无关删 → 403
  - 未登录 → 401
- **层级**：L1

### TC-3.5.5 删一级评论级联删其所有二级
- **关联**：Story 3.5 · AC2 · B3 · J2
- **页面/入口**：`DELETE /api/v1/comments/{id}`（一级）
- **前置**：一级评论 C1 有 3 条二级 C2a/C2b/C2c
- **步骤**：
  1. 删除一级评论 C1
  2. 查询 `comments WHERE parent_id=C1.id`
- **预期**：C1 软删（`deleted_at` 非空）；**C2a/C2b/C2c 一并软删**（`deleted_at` 非空）；事务内完成；`commentCount` 相应减少
- **层级**：L1

### TC-3.5.6 评论互动事件 — 自评不通知
- **关联**：Story 3.5 · AC2 · B4 · J3
- **页面/入口**：后端 `CommentService`（`ApplicationEventPublisher`）
- **前置**：用户 A 对自己帖评论；用户 B 对 A 帖评论
- **步骤**：
  1. B 评论 A 的帖 → 捕获事件
  2. A 评论自己的帖 → 检查事件
- **预期**：
  - B 评：`ContentCommentedEvent` 发布，携带 `postId`/`commenterId=B`/`contentAuthorId=A`
  - A 自评：由 notify 侧排除（事件携带足够信息供 Epic 6 去重）
- **层级**：L1

### TC-3.5.7 发送失败 — 保留输入内容和回复态
- **关联**：Story 3.5 · AC3 · F13 · R2 回改
- **页面/入口**：`/content/:id` · `comment_composer.dart`
- **前置**：已登录；模拟发送失败（网络错误）；已在评论框输入「keep me」并处于回复 C1 的回复态
- **步骤**：
  1. 输入「keep me」，设置回复目标为某评论
  2. 点击发送 → 请求失败
  3. 观察评论框状态
- **预期**：
  - SnackBar 显示「Failed to send, please retry」（en）/「Gagal mengirim, silakan coba lagi」（id）
  - 评论框文本**「keep me」保留**，不清空
  - **回复态（`@昵称`）保留**，用户可直接重试
  - 仅发送成功后才清空输入框并退出回复态
- **层级**：L0（widget test）

### TC-3.5.8 删除评论 UI — 仅对有权限者可见
- **关联**：Story 3.5 · AC2 · F3
- **页面/入口**：`/content/:id` · `comment_section.dart`
- **前置**：分别以评论作者、内容作者、无关用户身份查看同一条评论
- **步骤**：
  1. 以**评论作者**身份长按或点击评论「···」
  2. 以**内容作者**身份同样操作
  3. 以**无关用户**身份同样操作
- **预期**：
  - 评论作者：显示删除入口
  - 内容作者：显示删除入口
  - 无关用户：**不显示删除入口**（后端权威校验，前端隐藏仅为体验）
- **层级**：L0（widget test）

---

## 3.6 内容删除（无编辑）

### TC-3.6.1 作者删除 — 二次确认弹窗文案
- **关联**：Story 3.6 · AC1 · F2
- **页面/入口**：`/content/:id` · `content_detail_page.dart`（`···` 菜单 → 删除）
- **前置**：当前用户是内容作者（`isAuthor=true`）
- **步骤**：
  1. 进入自己的帖详情页
  2. 点击「···」→「Delete」
  3. 观察弹窗内容
- **预期**：弹出确认 dialog，显示「Once deleted, this content is permanently removed and cannot be recovered.」（en）/「Setelah dihapus, konten ini hilang permanen dan tidak dapat dipulihkan.」（id）；有「确认」和「取消」按钮
- **层级**：L0（widget test）

### TC-3.6.2 作者确认删除 — 软删 + 返回 Feed，帖消失
- **关联**：Story 3.6 · AC1 · B1 · J1 · J2
- **页面/入口**：`DELETE /api/v1/content-posts/{id}` · `content_detail_page.dart`
- **前置**：用户已登录且是帖作者；DB 中帖存在
- **步骤**：
  1. 在自己帖详情页确认删除
  2. 等待请求完成
  3. 观察页面跳转
  4. 查询 `content_posts WHERE id=?`
- **预期**：
  - 操作成功后 pop 返回 Feed，`feedProvider` 刷新
  - Feed 中该帖消失
  - 数据库中 `deleted_at` 置位（软删，不物理删）
- **层级**：L1 + L2

### TC-3.6.3 删帖后四处同步移除
- **关联**：Story 3.6 · AC1 · B3 · J2
- **页面/入口**：后端软删 + Feed/详情/成长档案时间线
- **前置**：某 GROWTH_MOMENT 帖被作者删除（`deleted_at` 置位）
- **步骤**：
  1. Feed API：调 `GET /api/v1/content-posts` — 检查该帖是否在返回集中
  2. 详情 API：调 `GET /api/v1/content-posts/{id}` — 检查响应码
  3. 成长档案时间线 API：调成长档案相关接口 — 检查该帖是否可见
  4. 评论：查询 `comments WHERE post_id=?` 的 `deleted_at`
  5. 点赞：查询 `content_likes WHERE post_id=?`
- **预期**：
  - Feed：不返回该帖（`WHERE deleted_at IS NULL`）
  - 详情：404（`deleted_at IS NOT NULL` → 统一防枚举文案）
  - 成长档案时间线：不显示该条
  - 评论：`deleted_at` 均置位（级联软删）
  - 点赞：`content_likes` 中该帖行已物理清除
- **层级**：L1

### TC-3.6.4 非作者删帖 — 403 拒绝
- **关联**：Story 3.6 · AC1 · B1 · J1
- **页面/入口**：`DELETE /api/v1/content-posts/{id}`
- **前置**：用户 B 尝试删除用户 A 的帖
- **步骤**：
  1. 用 B 的 JWT 发 `DELETE /api/v1/content-posts/{A的帖id}`
- **预期**：返回 403 ProblemDetail；帖不被软删（`deleted_at` 仍 null）
- **层级**：L1

### TC-3.6.5 未登录删帖 — 401 拒绝
- **关联**：Story 3.6 · AC1 · B1
- **页面/入口**：`DELETE /api/v1/content-posts/{id}`（无 JWT）
- **步骤**：
  1. 不携带 JWT 发 DELETE 请求
- **预期**：返回 401；帖不被删除
- **层级**：L1

### TC-3.6.6 重复删已删帖 — 幂等（不暴露曾否存在）
- **关联**：Story 3.6 · AC1 · B1
- **页面/入口**：`DELETE /api/v1/content-posts/{id}`（已软删）
- **前置**：帖已软删（`deleted_at IS NOT NULL`）
- **步骤**：
  1. 以作者身份再次 DELETE 同一帖
- **预期**：幂等处理，不抛异常；不暴露「是否曾存在」（200 或统一 404，策略与 softDelete 实现一致）
- **层级**：L1

### TC-3.6.7 他人详情页无「Delete」菜单项
- **关联**：Story 3.6 · AC1 · F1 · Story 3.3 · AC5
- **页面/入口**：`/content/:id`（他人帖）
- **前置**：当前用户非帖作者（`isAuthor=false`）
- **步骤**：
  1. 进入他人帖详情页，点击「···」
- **预期**：菜单中**无「Delete」项**；只有「Report」（举报）项
- **层级**：L0（widget test）

---

## 3.7 内容举报与运营人工审核队列

### TC-3.7.1 用户举报 — 详情页「···」菜单弹举报 Sheet
- **关联**：Story 3.7 · AC1 · F1
- **页面/入口**：`/content/:id` → `report_sheet.dart`
- **前置**：已登录用户；他人帖（`isAuthor=false`）
- **步骤**：
  1. 进入他人帖详情页，点击「···」→「Report」
  2. 观察弹出的举报 Sheet
- **预期**：从底部弹出举报 Sheet，标题「Report this content」（en）/「Laporkan konten ini」（id）；显示 5 个举报类型单选按钮
- **层级**：L0（widget test）

### TC-3.7.2 举报 Sheet — 5 类型双语单选
- **关联**：Story 3.7 · AC1 · F1
- **页面/入口**：`report_sheet.dart`
- **前置**：举报 Sheet 已弹出；分别测 en/id
- **步骤**：
  1. 检查 5 个举报类型选项的文案（en）
  2. 切换 id 再检查
- **预期**：
  - en：「Illegal or against the rules」/「False information」/「Inappropriate content」/「Harassment」/「Other」
  - id：「Ilegal atau melanggar aturan」/「Informasi palsu」/「Konten tidak pantas」/「Pelecehan」/「Lainnya」
  - 单选（RadioGroup）：每次只能选一个；有「Submit」（en）/「Kirim」（id）按钮
- **层级**：L0（widget test）

### TC-3.7.3 举报提交 — 写入工单 PENDING，内容**不自动下架**
- **关联**：Story 3.7 · AC1 · B2 · J1
- **页面/入口**：`POST /api/v1/content-posts/{postId}/reports` · `report_sheet.dart`
- **前置**：已登录用户；目标帖正常展示中
- **步骤**：
  1. 选择举报类型「Inappropriate content」，点「Submit」
  2. 查看 Toast 反馈
  3. 查询 `content_reports WHERE post_id=?`
  4. 检查该帖在 Feed/详情是否仍正常可见
- **预期**：
  - Toast 显示「Thanks, we've received your report and will review it soon 🐾」（en）/「Terima kasih, laporanmu sudah kami terima dan akan segera ditinjau 🐾」（id）；每条 ≤1 emoji
  - 数据库中 `content_reports` 新增行：`reason_type=INAPPROPRIATE`，`status=PENDING`
  - **被举报帖仍正常出现在 Feed 和详情**（无自动下架）
- **层级**：L0（widget test 反馈文案）/ L1（写库 + 不下架验证）

### TC-3.7.4 举报幂等 — 同用户重复举报同帖不叠加
- **关联**：Story 3.7 · AC1 · B2 · 唯一约束
- **页面/入口**：`POST /api/v1/content-posts/{postId}/reports`
- **前置**：用户已对某帖举报过（`content_reports` 已有该 `(post_id, reporter_id)` 行）
- **步骤**：
  1. 同用户对同帖再次 POST 举报
- **预期**：幂等处理（唯一约束 `(post_id,reporter_id)`）；不新增行；返回 200；不重复计数
- **层级**：L1

### TC-3.7.5 举报需登录 — 未登录游客触发 FR-0C
- **关联**：Story 3.7 · AC1 · F1 · FR-0C
- **页面/入口**：`/content/:id` · `report_sheet.dart`
- **前置**：未登录游客
- **步骤**：
  1. 游客进入他人帖详情页
  2. 点击「···」→「Report」
- **预期**：弹出 FR-0C 强登录引导弹窗；**不弹举报 Sheet**；不崩溃
- **层级**：L0（widget test）

### TC-3.7.6 Feed 卡片长按 — 弹举报 context menu
- **关联**：Story 3.7 · AC1 · F1 · Story 3.2
- **页面/入口**：首页 Feed · `masonry_card.dart` / `home_page.dart`
- **前置**：已登录用户；他人发布的帖卡片
- **步骤**：
  1. 在首页 Feed 长按他人帖卡片
  2. 观察 context menu
- **预期**：弹出包含「举报」选项的 context menu；点击后进入举报流程（弹举报 Sheet）；未登录长按触发 FR-0C
- **层级**：L0（widget test）/ L2（真机手势）

### TC-3.7.7 Admin 举报队列 — ADMIN 可访问，非 ADMIN 403
- **关联**：Story 3.7 · AC2 · B3 · J2
- **页面/入口**：`GET /admin/reports`
- **前置**：准备 ADMIN 会话和 User/Vet 角色访问
- **步骤**：
  1. ADMIN 登录后访问 `/admin/reports`
  2. User/Vet JWT 访问 `/admin/reports`
  3. 未登录访问 `/admin/reports`
- **预期**：
  - ADMIN：200，显示举报队列列表（PENDING 状态工单，按时间倒序）
  - User/Vet：403
  - 未登录：跳转 `/admin/login`
- **层级**：L1

### TC-3.7.8 Admin 人工下架 — 内容三处同步移除 + 工单 RESOLVED
- **关联**：Story 3.7 · AC2 · AC3 ② · B3 · J2
- **页面/入口**：`POST /admin/reports/{id}/takedown` · `templates/admin/reports.html`
- **前置**：ADMIN 已登录；DB 中有 PENDING 工单，对应帖在 Feed 中可见；该帖是 GROWTH_MOMENT 类型，已进成长档案时间线
- **步骤**：
  1. 在举报队列找到 PENDING 工单，点击「查看详情」
  2. 点击「下架」按钮
  3. 验证工单状态
  4. 验证内容各处可见性
- **预期**：
  - 工单状态改为 `RESOLVED`，记录 `handled_by`（ADMIN id）和 `handled_at`
  - 帖 `deleted_at` 置位（`reason=ADMIN_TAKEDOWN`）
  - Feed 不返回该帖
  - 「我的发布」列表不返回该帖（Story 7.1）
  - 成长档案时间线不显示该帖
  - 详情 API 返回 404（统一防枚举文案）
  - 内容作者收到 `CONTENT_REMOVED` 通知（Epic 6 notify 落库，可查 `notifications` 表）
- **层级**：L1

### TC-3.7.9 Admin 驳回工单 — 静默保留，零通知
- **关联**：Story 3.7 · AC3 ③ · B3
- **页面/入口**：`POST /admin/reports/{id}/dismiss` · `templates/admin/reports.html`
- **前置**：ADMIN 已登录；DB 有 PENDING 工单
- **步骤**：
  1. 在举报队列找到工单，点击「驳回」按钮
  2. 查询工单状态
  3. 查询 `notifications` 表
  4. 验证帖在 Feed 是否仍可见
- **预期**：
  - 工单置 `DISMISSED`，记 `handled_by`/`handled_at`
  - **帖仍在 Feed 正常可见**（静默保留）
  - `notifications` 表**无新增行**（作者/举报人均不通知）
  - 举报人侧零结果通知（AC3 ①）
- **层级**：L1

### TC-3.7.10 运营下架通知作者 — 不包含举报人信息，无申诉入口
- **关联**：Story 3.7 · AC3 ② · B4 · `ContentNotifyListener`
- **页面/入口**：后端 notify 模块 + App 通知中心（Epic 6）
- **前置**：ADMIN 执行下架操作
- **步骤**：
  1. Admin 执行下架（TC-3.7.8）
  2. 查询 `notifications` 表中该作者的通知行
  3. 检查通知内容字段
- **预期**：
  - 通知存在，`type=CONTENT_REMOVED`
  - 通知消息体**不包含举报人 ID/名称**（「不说明举报人」）
  - 通知文案类似「你发布的内容因违反社区规范已被移除」
  - **无申诉 URL/入口**（V1 无申诉）
- **层级**：L1

### TC-3.7.11 V1 无评论举报 / 无用户举报端点
- **关联**：Story 3.7 · B2 · 护栏
- **页面/入口**：API
- **前置**：已登录用户
- **步骤**：
  1. 尝试 `POST /api/v1/comments/{id}/reports`（若接口不存在）
  2. 尝试 `POST /api/v1/users/{id}/reports`
- **预期**：404（接口不存在）；V1 仅内容举报，无评论/用户举报端点
- **层级**：L1

---

## 3.8 他人迷你主页预览卡

### TC-3.8.1 点 Feed 卡片作者头像 — 弹迷你主页 bottom sheet
- **关联**：Story 3.8 · AC1 · F2 · FR-26
- **页面/入口**：首页 Feed · `masonry_card.dart` + `mini_profile_sheet.dart`
- **前置**：已登录用户（或游客，头像可见）；Feed 有他人帖
- **步骤**：
  1. 在 Feed 点击他人帖卡片的作者头像/昵称区域
  2. 观察弹出行为
- **预期**：从底部弹出迷你主页 bottom sheet（自底 300ms spring 动效，UX-DR11）；**不跳转新页**
- **层级**：L0（widget test）/ L2（真机视觉）

### TC-3.8.2 迷你主页 Sheet 内容结构 — 头像、昵称、发布数、「筹备中」文案
- **关联**：Story 3.8 · AC1 · F1 · FR-26
- **页面/入口**：`mini_profile_sheet.dart`
- **前置**：目标用户有 3 条已发布帖（含 1 条已软删 → postCount=2）
- **步骤**：
  1. 触发迷你主页 Sheet
  2. 检查 Sheet 内容
- **预期**（en）：
  - 头像 + 昵称
  - 「2 posts shared」（postCount=2，已软删不计）
  - 「Their personal page is being lovingly prepared, with more delights on the way ✨」
  - 关闭按钮
  - **无「关注」按钮、无「查看主页」按钮**
- **层级**：L0（widget test）/ L1

### TC-3.8.3 迷你主页文案 — id locale，无禁用技术性词
- **关联**：Story 3.8 · AC1 · F1 · UX-DR14
- **页面/入口**：`mini_profile_sheet.dart`
- **前置**：App locale = id
- **步骤**：
  1. id locale 下触发迷你主页 Sheet
  2. 检查文案
  3. widget test：断言不含禁用词「coming soon」/「not available」/「功能开发中」/「敬请期待」/「暂不支持」
- **预期**：
  - 「{count} postingan dibagikan」（id）
  - 「Halaman pribadi mereka sedang disiapkan dengan penuh perhatian, lebih banyak kejutan menanti ✨」
  - **不含任何技术性禁用词**（widget test 断言）
- **层级**：L0（widget test，含禁用词断言）

### TC-3.8.4 Sheet 拖拽/点背景关闭（UX-DR12）
- **关联**：Story 3.8 · AC1 · F1 · UX-DR12
- **页面/入口**：`mini_profile_sheet.dart`
- **前置**：迷你主页 Sheet 已弹出；真机
- **步骤**：
  1. 向下拖拽 Sheet
  2. 或点击 Sheet 背景区域
- **预期**：Sheet 收起关闭；无异常；拖拽手势流畅
- **层级**：L2（真机手势）

### TC-3.8.5 已注销作者头像 — 不触发迷你主页 Sheet
- **关联**：Story 3.8 · AC2 · F2 · NFR-8
- **页面/入口**：首页 Feed · `masonry_card.dart` + 详情页 · `content_detail_page.dart`
- **前置**：Feed/详情中有注销作者的帖（`authorDeleted=true`，昵称=「已注销用户」，默认占位头像）
- **步骤**：
  1. 在 Feed 点击注销作者帖的头像/昵称区域
  2. 在详情页点击注销作者的头像区域
- **预期**：
  - **不弹出迷你主页 Sheet**（`showMiniProfile` 不调用）
  - 无崩溃，无异常
  - 内容正文/图片仍正常显示
- **层级**：L0（widget test，authorDeleted 时 onAuthorTap 为 null 或不执行）

### TC-3.8.6 迷你主页 API — 只读游客可访问，发布数正确
- **关联**：Story 3.8 · AC1 · B1 · J1
- **页面/入口**：`GET /api/v1/users/{userId}/mini-profile`（无 JWT）
- **前置**：目标用户有 3 条 PUBLIC 未删帖 + 1 条 PUBLIC 软删帖 + 1 条 DRAFT 帖
- **步骤**：
  1. 无 JWT 调 `GET /api/v1/users/{userId}/mini-profile`
- **预期**：
  - 返回 200
  - `postCount=3`（仅 PUBLIC + 未软删，`COUNT WHERE deleted_at IS NULL AND status=PUBLISHED`）
  - 无 `followerCount`、无主页帖列表字段（防误用）
  - `isDeactivated=false`
- **层级**：L1

### TC-3.8.7 迷你主页 API — 已注销用户 isDeactivated=true
- **关联**：Story 3.8 · AC2 · B2 · J2
- **页面/入口**：`GET /api/v1/users/{userId}/mini-profile`（注销用户 id）
- **前置**：某用户已注销（Story 7.3 匿名化后）
- **步骤**：
  1. 调 `GET /api/v1/users/{userId}/mini-profile`（已注销用户）
- **预期**：
  - 返回 200（或 404，按实现策略）
  - `isDeactivated=true`；`nickname`=null；`avatarUrl`=null；**不查发布数**（不暴露任何身份信息，NFR-8）
- **层级**：L1

### TC-3.8.8 详情页作者信息区点击 — 触发迷你主页 Sheet
- **关联**：Story 3.8 · AC1 · F2 · Story 3.3
- **页面/入口**：`/content/:id` · `content_detail_page.dart`
- **前置**：已登录或游客；内容作者为他人且非注销
- **步骤**：
  1. 进入他人帖详情页
  2. 点击详情页作者信息区（头像/昵称）
- **预期**：弹出迷你主页 bottom sheet，显示该作者信息
- **层级**：L0（widget test）/ L2（真机）

---

## 跨 Story 横切测试

### TC-3.X.1 宠物状态硬过滤 — 完整矩阵（A/B/C/游客 × 三类型 × 三分类 Tab）
- **关联**：Story 3.2 · AC1/AC3 · CROSS-STORY（宠物状态硬过滤）
- **页面/入口**：`GET /api/v1/content-posts?category={ALL|DAILY|GROWTH_MOMENT|KNOWLEDGE}`
- **前置**：DB 预置：DAILY × 3、KNOWLEDGE × 3、GROWTH_MOMENT（pet_id 非空）× 3；4 种用户身份
- **步骤**：枚举 4×4=16 组合（4 身份 × 4 分类），检查返回集
- **预期**（简略）：

| 分类\身份 | HAS_PET | PLANNING | ENTHUSIAST | 游客 |
|---|---|---|---|---|
| ALL | D+K+G | D+K | D+K+G | D+K+G |
| DAILY | D | D | D | D |
| MOMENTS | G(非空) | 空集 | G(非空) | G(非空) |
| TIPS | K | K | K | K |

- **层级**：L1

### TC-3.X.2 注销作者匿名化 — Feed + 详情 + 评论 + 迷你卡全路径一致
- **关联**：Story 3.2/3.3/3.5/3.8 · NFR-8
- **页面/入口**：多处
- **前置**：用户 A 发帖、评论；A 执行注销（Story 7.3 流程）
- **步骤**：
  1. Feed 卡片 → 检查昵称/头像/可点击性
  2. 详情页 → 检查作者区昵称/头像/点击行为/HTTP 状态
  3. 评论区 → 检查 A 的评论显示
  4. 迷你主页 API → 检查响应
- **预期**：
  - 所有路径昵称显示「已注销用户」，头像为默认占位
  - 头像点击**不触发**迷你主页 Sheet
  - 详情页 HTTP 200（内容留存匿名化，非 404）
  - 迷你主页 API `isDeactivated=true`
- **层级**：L1

### TC-3.X.3 软删内容全路径一致 — 举报下架后 Feed/详情/时间线/我的发布同步
- **关联**：Story 3.6/3.7 · C3 · F10
- **页面/入口**：Admin 下架 → 多路径检验
- **前置**：帖已被运营下架（AC3 ②）
- **步骤**：
  1. Feed API — 不返回该帖
  2. 详情 API — 返回 404（统一防枚举文案）
  3. 成长档案时间线 — 不返回该帖（若是 GROWTH_MOMENT）
  4. 「我的发布」API（Story 7.1）— 不返回该帖
- **预期**：四处均不可见；详情 404 文案与作者自删404文案相同（均「内容已不存在」，不区分）
- **层级**：L1

### TC-3.X.4 双语 i18n — App 不渲染中文
- **关联**：全 Epic 3 前端 · 项目记忆「双语模型+i18n遗留债」
- **页面/入口**：所有 App 页面
- **前置**：en locale 和 id locale 分别测试
- **步骤**：
  1. 遍历 Epic 3 所有 App 页面（Feed/详情/评论/点赞/举报 Sheet/迷你主页 Sheet）
  2. 检查所有用户可见文案来源
- **预期**：
  - 所有用户可见文案来自 `.arb` 文件（en/id 双语），**不渲染中文硬编码**
  - 后端返回的 `type`（DAILY/KNOWLEDGE/GROWTH_MOMENT）等 code 值前端按 l10n key 本地化显示，**不直接展示英文 code**
  - 每条 l10n 值 ≤1 emoji
- **层级**：L0（代码 review + widget test）

### TC-3.X.5 发布时三方自动审核（F10）— 关键词拦截停留编辑页
- **关联**：Story 2.3 + Story 3.7 · CROSS-STORY F10
- **页面/入口**：发布流程（Story 2.3 范围，Epic 3 测试中验证入口一致）
- **前置**：`ContentModerationService` stub 已配置关键词过滤规则
- **步骤**：
  1. 在发布页正文中输入被关键词过滤命中的内容，点发布
  2. 观察是否发布成功
- **预期**：
  - 任一拦截（关键词/图像 stub）→ 发布失败，**停留编辑页**，显示发布失败提示
  - 不进举报队列，不进人工审核
  - 修改内容后可重新提交
  - 举报模块（3.7）**仅处理已发布内容**（F10 范围对齐）
- **层级**：L1

---

## 本章遗留/盲区

1. **`comments` 表创建归属二义性**：Story 3.3 的 Dev Notes 声称「V9 本 Story 落 comments 表读路径」，但 CROSS-STORY 决策表 §表归属总表 写的是「`comments` 创建 story = 3.5」。若 3.3 已建表，3.5 应仅追加写/删路径。实际 QA 验收前需核查 V9 迁移中 `comments` 表的实际 DDL 是否已含完整约束（`parent_id`/`body` 长度/索引）。若 3.3 仅建了读路径的最小 schema，3.5 扩展时可能产生 `ddl-auto=validate` 失败风险。
2. **`commentCount` 口径未明确覆盖**：Story 3.3 详情 API 返回的 `commentCount`、3.5 删除后的计数更新、3.6 级联清评论后的计数，三处口径是否一致（含一级+二级 vs 仅一级）未有专项 TC 验证全路径。建议补一个跨路径的 commentCount 一致性 TC。
3. **Feed 卡片长按举报与自己帖的交互**：Story 3.7 实现了 Feed 卡片长按举报，但未明确「长按自己的帖」时是否显举报项（逻辑上应与「···」菜单一致：自己的帖不应有举报）。当前 test 覆盖主要测他人帖长按，自己帖长按的 context menu 行为需确认。
4. **迷你主页 Sheet 的游客可见性**：FR-26 无登录要求，`GET /api/v1/users/{userId}/mini-profile` 已配置游客可访问。但当游客在 Feed 点击他人头像时，App 是否直接调接口弹 Sheet 还是先触发 FR-0C？当前 TC 覆盖已登录触发，游客触发路径未明确测试，存在行为歧义。
5. **成长档案「当天详情页」来的 /content/:id 返回时保位**：AC5 第二条明确「当天详情页条目点击 → push /content/:id，返回回当天详情页保位」。该路径（成长档案 → 内容详情 → 返回 → 成长档案保位）未在本章设立独立 TC（需联合 Story 2.4「当天详情页」范围测试）。
