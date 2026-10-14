---
title: "V1.3.4 增补：Social 页 Following 关注流 + 官方账号默认关注"
status: approved-pending-review
created: 2026-10-14
version: v1.3.4
---

# V1.3.4 增补：Following 关注流 + 官方账号默认关注

## 0. 背景与目的

V1.3.4 已上线「用户相互关注」（FR-140，`PRD-v1.3.4-follow.md`），但关注之后用户在 App 里**没有地方集中看到关注的人发了什么**——PRD §3.1「边界与不做项」原写「不做关注 Feed」。本增补**推翻这一条**（2026-10-14 产品拍板），在 Social 页顶部加「Discover / Following」两个页签：Discover 即现有首页推荐流，Following 是我关注的人发的帖子。

同时为了让新用户的 Following 不是一片空白，**官方账号默认已关注**。

- **随 V1.3.4 一起上线**（2026-10-14 拍板），不另开版本。
- 设计图：`_bmad-output/planning-artifacts/v1.3.4/following-feed-assets/A2-following-feed.png`（有内容）、`A3-following-empty.png`（空状态）。设计图仅定结构与交互，视觉按现有设计系统（品牌紫 #845EC9）实现。

## 1. 目标 / 不做 / 反目标

**目标**
1. 关注关系产生持续回访理由：打开 Social → Following 就能看到关注的人的新帖。
2. 新用户第一次进 Following 也有内容（官方账号默认关注）。

**不做**
- 不改 Discover（现有推荐流）的任何排序、内容与卡片。
- Following 不走推荐算法、不打分、不抖动——纯时间倒序。
- 不做「关注宠物」：关注对象仍是人（宠物以后可能一人多只）。
- 头像外圈不表示「有新帖 / 已读」等状态，纯装饰。
- 不做 Following 内的分类筛选（Semua / Moment / Diary / Knowledge）。

**反目标（做成这样算失败）**
- 已取关官方账号的用户又被系统自动关注回去。
- Following 里出现 Discover 会过滤掉的内容（私密帖、已删帖、拉黑 / 举报隐藏的作者）——过滤规则只升不降。
- 默认关注官方账号给官方号刷出成千上万条「新粉丝」通知。

## 2. 产品规则（已拍板，2026-10-14）

### 2.1 页面结构
- Social 页顶部改为两个页签 **Discover / Following**（大字号页签，选中紫色 + 下划线），右侧保留通知铃（含未读角标）。
- 默认进入 Discover。
- **游客点 Following 页签 → 直接拉起登录**（与游客点关注按钮一致），登录后落到 Following；Following 接口只对登录用户开放（2026-10-14 拍板）。Discover 页签下内容与现状一致（快捷入口卡、分类 chips、瀑布流等原样保留）。

### 2.2 顶部关注头像排
- 显示**我关注的人**：人的头像 + 人的昵称（不是宠物）。
- 排序：按该用户**最近一条公开帖的发布时间**倒序（最近有动态的在前）；没发过公开帖的排在后面（其间按关注时间倒序——builder 定，见 §5）。
- **最多 20 个**；末尾固定一个「More」圆形入口（虚线圈 +「More」文案），点击 → 我的关注列表（`FollowListPage`，Following Tab）。
- 只要关注了至少 1 人就显示「More」（即使不足 20 人）；**一个都没关注时整排不显示**，直接走空状态（§2.4）。
- 头像统一紫色外圈，纯装饰（设计图中 jasmine 的灰圈不做区分）。
- 点头像 → 该用户个人主页。
- 横向可滑动。

### 2.3 帖子流
- 内容：**我关注的人发的公开帖**，按发布时间倒序，游标分页。
- 过滤与 Discover 时间倒序 Tab 完全一致：仅 PUBLIC、已发布、未删除、排除我举报过的帖、排除我拉黑 / 举报隐藏的作者。
- 作者已注销 / 封号的帖不出现。⚠ 代码现状：封号只置 `users.status=DEACTIVATED`、**不下架存量内容**，`findFeed` 也不按作者状态过滤 → Following 的查询须**自行加作者状态 = ACTIVE 且未注销**的过滤；头像排同样排除封号 / 注销的被关注者。**Discover 维持现状，本次不改**（我的决定，2026-10-14）。
- **卡片样式：双列瀑布流，直接复用 Discover 的卡片组件**（2026-10-14 拍板，不按设计图做单列大卡；卡片抬头沿用现有的作者头像 + 昵称，不显示宠物名）。
- 卡片上**不显示关注按钮**（都是已关注的人，沿用 1.3.4「已关注不显示按钮」规则）。
- 点赞、评论、进详情、「…」菜单等交互与 Discover 卡片一致。
- 下拉刷新、上拉加载更多。

### 2.4 空状态（`A3`）
触发：一个人都没关注，或关注的人都没有任何可见帖子（此时头像排也不显示）。
- 图标 + 标题「No posts from friends yet」+ 说明「Follow … and their moments and diaries will show up here.」（文案按「关注人」改写，见 §4）。
- 「Suggested for you」推荐关注列表：复用现有推荐名单（`/me/follow-suggestions`，口径不变：近 30 天获赞倒序、最多 30 个、排除自己 / 已关注 / 拉黑 / 封号 / 兽医），每行头像 + 昵称 + 副标题 + Follow 按钮（三态规则不变）。
  - 副标题：宠物物种 · 年龄（如「Kucing · 2 thn」）；**城市不显示**（无数据）；无宠物则不显示副标题。
- 底部「Explore Discover ›」→ 切到 Discover 页签。
- 在空状态里关注某人后：按钮变 Following（可立即取关）；**不立刻刷新** Following 内容，下拉刷新或下次进入时才出现（2026-10-14 拍板）。
- 关注了人但这些人都没有可见帖时：**只显示空状态，不显示头像排**（与 A3 一致，2026-10-14 拍板）。

### 2.5 官方账号默认关注
- 「官方账号」= `users.role = ADMIN` 的运营内容账号（目前仅「TailTopia 运营」1 个；若有多个则全部默认关注）。
- **新注册用户**：注册成功即自动关注全部官方账号。
- **存量用户**：上线时一次性补关注（迁移回填）。
- **用户取关官方账号后**：之后一切按普通关注逻辑——不会被系统再自动关注；想看可以自己再关注。系统须记录「该用户已主动取关过该官方号」，回填与新注册钩子都要跳过这些人。（1.3.4 关注功能尚未上生产，生产上不存在「上线前就取关过」的用户，无需从历史推断。）
- 默认关注是系统行为：**不发「新粉丝」通知、不计入用户的关注限流 / 每日 500 上限**；官方号的粉丝数、用户的关注数照常计入。
- 只对普通用户（`role = USER`）生效；兽医、官方号自身不自动关注。

## 3. 技术约束（来自代码摸底，builder 须遵守）

- 后端：Spring Boot 4 / Java 21 / Flyway 时间戳迁移（须晚于 `V20261013_0649`）；`ddl-auto=validate`；对外用户标识用 `public_token`（D-2）；不引入新中间件。
- Feed 参考实现：`content/service/FeedService.chronoFeed`、`ContentPostRepository.findFeed`（过滤条件在 SQL WHERE）、`assemble`（批量补作者 token 与关注状态，禁止逐条查）。
- 关注：`social/service/FollowService`（`follow` 带限流与 `UserFollowedEvent`，自动关注**不能**直接调它，需内部方法：插关注行 + 更新冗余计数，不发事件）；关注 / 取关是物理 DELETE，无历史表 → 需新表记录「已主动取关官方号」。
- 注册钩子：`auth/service/AuthService` 新用户分支发布 `UserSignedUpEvent`；参照 `shared/im/ImAccountRegistrationListener`（AFTER_COMMIT）挂监听。
- 推荐关注：`social/service/FollowSuggestionService`，返回 `FollowListItem` 目前不含宠物物种 / 年龄，需补字段（旧客户端忽略新字段，兼容）。
- App：首页 `features/content/presentation/home_page.dart`（AppBar 品牌标 + `NotificationBell`）、`feed_controller.dart` / `feed_view.dart`；关注状态 `followStateProvider`；推荐行当前内联在 `follow_list_page.dart` `_selfEmpty`，需抽成共享组件复用。
- 新增用户可见文案 en / id 两份 ARB 同步；不硬编码。

## 4. 文案（提议，builder 可按 ARB 惯例微调）

| 位置 | id | en |
|---|---|---|
| 页签 | Jelajah | Discover |
| 页签 | Mengikuti | Following |
| 头像排末尾 | Lainnya | More |
| 空状态标题 | Belum ada postingan dari teman | No posts from friends yet |
| 空状态说明 | Ikuti orang yang kamu suka, momen dan diary mereka akan muncul di sini. | Follow people you like and their moments and diaries will show up here. |
| 推荐区标题 | 沿用现有「DISARANKAN UNTUKMU」key | 沿用 |
| 底部链接 | Jelajahi Discover › | Explore Discover › |

## 5. 留给 builder / 待定

- 产品项已全部拍板（2026-10-14）：双列复用 Discover 卡片；游客拉起登录；空状态关注后不立即刷新；无可见帖时只显示空状态。
- （我的决定）版本与分支：1.3.4 尚未提审，直接在 `dev_1.3.4` 追加；前后端须同批部署（后端先）。
- （我的决定）自动关注行打来源标记，不计入每日 500 上限（`checkDailyLimit` 统计当天新建行）；已拉黑官方号的存量用户回填时跳过。
- （builder 定）头像排中没发过公开帖的人的次序；头像排与帖子流的接口拆分方式；新表与回填迁移的具体结构；埋点（建议沿用 `feed_tab` 维度新增 `following` 取值 + 头像排 / More 点击事件，命名用过去式）。

## 6. 验收要点

1. Social 顶部出现 Discover / Following，Discover 与改动前一致。
2. Following 头像排：按最近发帖倒序、最多 20 人、末尾 More → 我的关注列表；无关注时不显示。
3. Following 帖子流只含关注的人的可见公开帖，时间倒序，分页无重复无遗漏；拉黑 / 举报隐藏 / 私密 / 已删 / 封号内容均不出现；卡片无关注按钮。
4. 空状态显示推荐关注（含物种 · 年龄），Explore Discover 切回 Discover。
5. 新注册用户与存量用户都已关注官方账号，官方号无新粉丝通知；用户取关官方号后，重新登录 / 再次触发回填都不会被自动关注回去。
6. 旧版 App（1.3.3）调用 `/me/follow-suggestions` 等接口不受新字段影响。
