# Story 1.5: 关注列表、关注流空态、通知与推送设置（App，A5–A13）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 social-topic · 分支 `feat/1.4.2-social-topic`
> 口径优先级：`决策日志-social-topic.md`（R-2、I5）> PRD §3.1「列表 / 追更了能看到什么 / 主人一侧」、AC6 ~ AC8、AC12、§4、§7 > 架构增量 D-8、D-9、D-10、D-17、D-23、G-1、§1.2 第 12 条、P-4、P-9 ~ P-12 > **UI 定稿 `追更话题/ui-1-4-2-social-topic-integrated-v1.html` 的 A5–A13 每屏下方说明**（数值照说明里写的代码出处取，不从截图量像素；「原有模块照线上」不改样式）。
> 前置：**Story 1.1**（`/users/{token}/pet-follows`、`petFollowingCount`）、**1.2**（`PET_FOLLOWED` / `PET_DIARY_POSTED`、`petName` 字段、推送分类 `PET_DIARY`）、**1.3**（关注流 `reason` 与 `followingUserCount` / `followingPetCount`、推荐接口排除已追更）、**1.4**（`PetFollowButton`、`petFollowStateProvider`、`PetFollowRepository`）。
> 范围层：**只 App**（`petgo_app/`）。

## Story

As a 追更者,
I want 在关注列表管理追更的宠物、关注流空时知道原因、能单独关掉追更提醒,
so that 追更关系清楚可控。

## 接口契约（来自 1.1 ~ 1.3，实现前核对后端实际 JSON）

- `GET /api/v1/users/{token}/pet-follows?cursor=` → `{ items: [{ petId, name, petType, avatarUrl, ownerNickname, ownerToken, iFollow, iFollowOwner }], nextCursor, hasMore, petFollowingCount? }`（`petFollowingCount` 只首页有）；游客可读；403 `blocked-user` / 404 同关注列表。
- `GET /api/v1/users/{userId}/profile` 新增 `petFollowingCount`（「Hewan N」胶囊数据源，G-1）。
- `GET /api/v1/me/following-feed` 每条新增 `reason`（`followed_user` / `followed_pet`），**首页**新增 `followingUserCount`、`followingPetCount`。
- 通知列表新增类型 `PET_FOLLOWED`（`title` 已拼好、`actorName` = 追更者昵称、`actorUserId` / `actorToken`、`petName`、`targetRef` = 追更者 user id、**无** `actorIFollow`）与 `PET_DIARY_POSTED`（`title` 已拼好、`actorName` = `petName` = 宠物名、`targetRef` = 帖子 id、无 actor id；宠物已删时无 `actorName`，`title` 为写入时按收件人语言渲染的整句）。
- `GET /api/v1/me/push-settings` 返回 10 项，`PET_DIARY` 紧跟 `FOLLOWER`。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地 / stag 后端联调 · `[L2]` Android 模拟器视觉 / 真机推送（本地）。

### AC1 — A5 关注列表 Pengguna / Hewan 筛选 `[L0 widget]` `[L2]`（PRD AC8、UI A5）
1. 关注列表页（`follow_list_page.dart`）**只在 Mengikuti Tab** 的 Tab 栏下方、列表上方加两个筛选胶囊「Pengguna {N}」（**默认选中**）/「Hewan {N}」；**无「全部」**；Pengikut Tab 不变。
2. 胶囊样式复用 Discover 分类胶囊（`feed_tab_row.dart` `_Tab`:51-80）：选中 `AppColors.mint` 底白字、未选 `card` 底 + 1.5 `line` 框、圆角 999、内边距 **7 / 13**（:63）、胶囊间距 7（:39）、字号照代码 **12 / w600**（:72，见「待确认」3）；数量与文字间距 4（token `xs`）、数量 `tabularFigures`。`_Tab` 是私有类 —— 抽成可复用的公共组件（只抽外观，Discover 行为不变），不要另画一份。
3. N：Pengguna = 主页 `followingCount`（不变，含官方号）；Hewan = 主页 `petFollowingCount`（G-1）；缺字段（旧后端）→ Hewan 显示不带数字或 0，任选其一并在 Completion Notes 说明。
4. 从我的页四格「Mengikuti」进入默认停在 Pengguna；**我的页 / 他人主页四格的 Mengikuti 数只计用户**（不加追更数，`me_counts_provider` / 主页四格不改）。
5. 切换胶囊不丢已加载数据（两路各自独立分页状态，同 `_FollowListTabView` 的 keep-alive 写法）。

### AC2 — A6 / A7 宠物行与取消追更 `[L0 widget]` `[L2]`（PRD AC8、UI A6 / A7）
1. Hewan 列表每行：圆形宠物头像 40（无头像按物种 emoji 占位，同 `pet_info_card.dart` 的占位规则）+ 宠物名 15 / w600 + 第二行「{物种} · milik @{主人昵称}」12 号 `muted`（单行省略）+ 右侧按钮；行结构与用户行同尺寸（头像 40、右侧 88×30 按钮、行间 1px `line2` 分隔、行上下 12）。头像、文字、按钮**垂直居中**（UI 第四步专门修过「头像低 6px」，别复现）。
2. 新组件 `features/social/presentation/widgets/followed_pet_row.dart`（`FollowUserRow` 绑定的是用户模型 `FollowListItem`，不能直接复用；照它的布局数值写）。
3. **自己的列表**：常驻「Mengikuti」（`PetFollowButton` 的 `listCompact` 外观 = `FollowButton._listCompact` 同款：高 30、圆角 8、最小宽 88、12 / w600；已追更白底 1.5 灰框、未追更 `violet100` 浅紫底紫字）。点 Mengikuti → **立即取消、该行保留**、按钮变「Ikuti」，可再点恢复；**下拉刷新后**该行消失，胶囊「Hewan 3」随之变 2（同 1.3.4 B2「刚取关」规则）。无二次确认。
4. 点行（除按钮外）→ 宠物访客页 `/pets/{petId}`（A2 态）。主人封号的宠物点进去落 1.4 的 A14，返回后仍可在列表取消（PRD AC11）。
5. 埋点：列表内追更 / 取消 `pet_follow` / `pet_unfollow`，`source = 'follow_list'`，`pet_id`、`owner_id` = 行 `ownerToken`、`is_following_owner` = 行 `iFollowOwner`（R-2）。

### AC3 — A9 别人的关注列表 `[L0 widget]`（UI A9）
1. 别人的 Mengikuti Tab 同样有两个筛选；Hewan 行**不显示任何按钮**（右侧留白不补元素）；点行进访客页。
2. 别人的 Hewan 为空：只显示空态「Belum mengikuti hewan apa pun」，**不显示推荐宠物**。

### AC4 — A8 自己的 Hewan 空态 + 推荐宠物 `[L0 widget]` `[L2]`（PRD「Hewan 为空时」、UI A8）
1. 自己的 Hewan 为空：空态照 `follow_list_page.dart` `_empty()`（:350-364）版式 —— 顶距 120、图标 `Icons.pets` 48 `muted`、标题 18 / w600「Kamu belum mengikuti hewan apa pun」（新文案）、标题 `text-wrap: balance` 的 Flutter 等价（`textAlign: center`，必要时 `TextWidthBasis.longestLine`）；「Hewan 0」胶囊仍为**选中态**。
2. 空态下方 32（token `xxl`）放推荐宠物：标题复用 `petRecommendSectionTitle`「Kenalan sama anabul lain」（`AppTypography.body` 15 w600，下 4）；**不放「Lihat semua ›」**；不显示「Disarankan untukmu」推荐关注用户（那是 Pengguna 为空时的推荐）。
3. 数据：复用 `petRecommendationsProvider`（`profile/data/pet_recommendation_repository.dart`:142，autoDispose、失败不重试）—— 后端（1.3）已排除已追更、改近 14 天点赞。**推荐为空或接口失败 → 整块隐藏**（不留标题、不显示错误态）；加载中不占位。
4. 卡片复用首页横滑行小卡 `_StripPetTile`（`pet_recommendation_strip.dart`:134-216）的外观：圆角 12 方图（最近一张公开照片，无图 `cream2` + `pets` 占位）+ 左下探出 18 小圆头像（2 白边，left −4 / bottom −6）+ 间距 8 + 一行「名字 · N hari」11 / 1.3（`petCardDays`）；卡间距 8、横滑、露出半张。**与首页唯一差别**：卡宽 65 → **88**、卡下方 8 处加「Ikuti」按钮（`PetFollowButton.listCompact`，88×30；热区上下各扩 7 到 44，`tapTargetSize: padded` 或外包 44 高）。`_StripPetTile` 私有 —— 改成可传 `width` 与 `bottom` 插槽的公共组件，首页调用保持 65 宽、无按钮、像素不变。
5. 交互：点按钮 = 追更 / 取消（乐观更新，失败回滚 + 顶部 toast，同 1.4 A3）；点卡片其他区域 = 进访客页，复用 `openRecommendedPet`（`recommended_pet_card.dart`:233），`from` 新增常量 `kPetRecommendFromFollowListEmpty = 'follow_list_empty'`（与现有 `diary_empty` / `explore_strip` 等并列，:15-45）。追更后卡片**不移除**、按钮变 Mengikuti（非常驻位置）；空态与「Hewan 0」在下拉刷新后才更新为列表（同 A7 规则）。
6. 埋点：推荐卡上的追更 `source = 'pet_recommendation'`（PRD §4 新增值，R-2）。

### AC5 — A10 / A11 关注流 `[L0 widget]` `[L2]`（PRD AC3 / AC12、UI A10 / A11）
1. A10：界面**无新元素**，流里混入追更宠物的公开 Diary；卡片作者行照常显示「+ Ikuti」（这是关注作者按钮，**不是**追更按钮）；头像排仍只列关注的用户（`followingStripProvider` 不变）。
2. `FeedPage` / `FeedState` / `FollowingFeedController` 读首页 `followingUserCount`、`followingPetCount`（缺键 = null）。
3. 空态分支（`following_tab_view.dart`:61 `if (state.isEmpty)`）：
   - 两个计数**都为 0** → 现有 `FollowingEmptyView`（不变）；
   - 任一 > 0 → A11「暂无新动态」：版式完全照 `following_empty_view.dart`（圆 96 `violet100` + 图标 42 紫 → 上距 24 → 标题 20 / w700 → 上距 10 → 说明 14.5 / 1.45 `ink2` → 16 → `SuggestedFollowList(maxItems: 5, emphasized: true)` → 16 →「Jelajahi Discover ›」），只换：图标 `Icons.schedule`（outlined）、标题「Belum ada postingan baru」、说明「Postingan baru dari orang dan hewan yang kamu ikuti akan muncul di sini.」（新文案）；不挂头像排；
   - 计数缺失（旧后端）→ 回落现有空态。
   - 实现：给 `FollowingEmptyView` 加一个 `variant`（或 `icon` / `title` / `body` 参数），不复制组件。
4. 推荐关注：后端 `findSuggestionIds` 已排除自己、已关注、隐藏 / 拉黑的人；只追更了宠物、没关注其主人时主人仍可能被推荐（两套关系独立，符合 PRD）。
5. `reason` 埋点：`FeedItem` 读 `reason`（缺键 null）。PRD §4 要求「关注流曝光与点击新增属性 `reason`」—— 现有代码**没有**关注流卡片的曝光 / 点击事件（见「待确认」1）。本 story 按推荐做法：在关注流卡片已有的交互事件（`post_like_tapped`，`feed_tab == 'following'` 时）带上 `reason`；曝光 / 点击是否新增事件等主会话拍板，拍板前不新增事件名。

### AC6 — A12 通知中心两条新通知 `[L0 widget]`（PRD AC5 / AC6、D-10、D-23、UI A12）
改 `features/notify/` 与 `core/router/deep_link_routes.dart`：
1. `NotificationItem.serverCopyTypes`（`notification_item.dart`:27-33）加入 `'PET_FOLLOWED'`、`'PET_DIARY_POSTED'`；模型新增 `petName`（缺键 null）。
2. 标题加粗：`_serverTitle`（`notification_center_page.dart`:1097-1112）现只加粗 `actorName`；改为同时加粗 `petName`（两段都找、按出现位置拼 `TextSpan`；`PET_DIARY_POSTED` 的 `actorName == petName` 只加粗一次）。正文行：两类都无正文（`body` 空则不留行，现有逻辑已满足）。
3. 图标 `_iconStyle`（:773 起）：`PET_FOLLOWED` → `Icons.person_add_rounded` / `AppColors.mint` / `AppColors.violet100`（与 `USER_FOLLOWED` 完全相同）；`PET_DIARY_POSTED` → `Icons.pets_rounded` / `AppColors.mint` / `AppColors.violet100`（**不用 `cream2`**：未读条目底色就是 `cream2`，图标块会「消失」）。
4. **无回关按钮**：`_followBack()`（:1117）只对 `USER_FOLLOWED`，保持；`PET_FOLLOWED` 不显示任何按钮。
5. 点击：
   - `PET_FOLLOWED` → 用 `actorUserId` 进追更者主页（照 `USER_FOLLOWED` 的写法 :341-370，经 `openUserProfile`，报 `user_profile_viewed`）；无 `actorUserId` → 落通知中心本身（不跳转）。
   - `PET_DIARY_POSTED` → 帖子详情 `/content/{targetRef}`；点击前走现有「已不存在」探测 `_postGone`（:395 起，现只对 `CONTENT_LIKED` / `CONTENT_COMMENTED`，加入 `PET_DIARY_POSTED`）—— 已删则 toast `detailGoneTitle`、不跳转、已读照标。
6. 深链表 `DeepLinkRoutes`（`deep_link_routes.dart`:150-190）：`PET_DIARY_POSTED` → `'/content/$targetRef'`（非数字落通知中心）；`PET_FOLLOWED` → `'/users/$targetRef'` 追更者主页（`targetRef` = 追更者 user id，照 `USER_FOLLOWED`；非数字落通知中心）。两者都在 `switch` 里显式列出，不靠 default。
7. 兜底文案：无 `actorName`（`hasServerCopy == false`）时，`_typeLabel` / `_typeBody` 对 `PET_DIARY_POSTED` 读后端 `title`（`_storedTitle`，与 `PET_SLOT_EXPIRING` 同理由：后端按收件人语言渲染后落库）；`PET_FOLLOWED` 触发人全被过滤时后端直接不下发该条（T-36），兜底走 `notifyTypeSystem` 即可。
8. 埋点 `_notifTypeForAnalytics`（:275-284）显式登记：`'PET_FOLLOWED' => 'pet_followed'`、`'PET_DIARY_POSTED' => 'pet_diary_posted'`。
9. 旧版 App 收到新类型走现有兜底（NFR7，无需处理）。

### AC7 — A13 推送设置 `[L0 widget]` `[L2]`（PRD AC7、UI A13）
1. `PushCategoryCode`（`push_settings.dart`）新增 `petDiary = 'PET_DIARY'`，`all` 顺序插在 `follower` 之后（= 后端声明顺序）。
2. `push_settings_page.dart` `_groups`（:64-82）：INTERAKSI 组「Pengikut baru」行之后加「Hewan yang kamu ikuti」行：图标 `Icons.pets_rounded` 紫 / `AppColors.cream2` 底（推送设置里现有紫色图标都用 `cream2` 底，:67-69）；标题 / 说明为新文案「Hewan yang kamu ikuti」/「Diary baru dari hewan yang kamu ikuti」；**默认开、可关**；切换即保存（沿用现有 PATCH 逻辑）。
3. 「Pengikut baru」说明改两行：第一行现有 `pushSettingsFollowerDesc`「Seseorang mulai mengikutimu」+ 第二行新文案「Seseorang mulai mengikuti hewanmu」（EN「Someone started following your pet」）；两行同样式（12 `muted`）、换行显示，不改行高规则。
4. 行样式照线上（36 圆形图标 + 标题 15 / w500 + 说明 12 `muted` + 开关；行上下 11；分隔左缩进 64，:343-423）；关掉后通知中心照常收到（底部说明已覆盖）。

### AC8 — 文案 `[L0]`（PRD §7、P-10）
`app_id.arb` / `app_en.arb` 同步新增（key 前缀按 P-10 用 `petFollow*` / `followingFeedNoNew*`，建议名如下，可微调但须两份一致）：

| 建议 key | 印尼语 | 英语 |
|---|---|---|
| `petFollowFilterPeople` | Pengguna | People |
| `petFollowFilterPets` | Hewan | Pets |
| `petFollowOwnedBy`（`{name}`） | milik @{name} | owned by @{name} |
| `petFollowListEmptySelf` | Kamu belum mengikuti hewan apa pun | You're not following any pets yet |
| `petFollowListEmpty` | Belum mengikuti hewan apa pun | Not following any pets yet |
| `petFollowPushTitle` | Hewan yang kamu ikuti | Pets you follow |
| `petFollowPushDesc` | Diary baru dari hewan yang kamu ikuti | New Diary from pets you follow |
| `petFollowPushFollowerPetDesc` | Seseorang mulai mengikuti hewanmu | Someone started following your pet |
| `followingFeedNoNewTitle` | Belum ada postingan baru | No new posts yet |
| `followingFeedNoNewBody` | Postingan baru dari orang dan hewan yang kamu ikuti akan muncul di sini. | New posts from people and pets you follow will show up here. |

- 胶囊「Pengguna {N}」的数字与文字分开渲染（不拼进 arb）；第二行「{物种} · milik @{昵称}」中物种用现有 `petTypeCat` / `petTypeDog` / `petTypeOther`。
- `PET_FOLLOWED` / `PET_DIARY_POSTED` 的通知句子由后端拼（I5），**App 不加这两句的 arb**。
- 改 ARB 后**必须** `flutter gen-l10n`；不改生成文件；跑 `test/l10n/` 下的微文案契约测试。

### AC9 — 状态与缓存 `[L0]`
1. 新增「我追更的宠物列表」provider（`followedPetListControllerProvider(token)`，分页、`refresh` / `loadMore`、失败不画成空态），与 1.4 的 `petFollowStateProvider` 共用覆盖表；**登记 `resetUserScopedCaches`**。
2. 本页在游客登录 / 换账号时重取（照 `follow_list_page.dart`:71-79 对 `publicProfileProvider` / `followListControllerProvider` 的 invalidate，补上新 provider）。
3. 访客页（1.4）追更成功后失效本列表 provider（1.4 预留的调用点在本 story 接上）。

### AC10 — 还原度与回归 `[L0]` `[L2]`
1. `flutter analyze` 零警告；`flutter test` 全绿（`follow_list_page_test`、`follow_list_entry_test`、`following_tab_view_test`、`following_feed_controller_test`、`notification_center_test`、`notification_center_v134_test`、`deep_link_routes_test`、推送设置相关测试只补不删）。
2. **还原度 ≥ 95%**（L2）：Android 模拟器逐屏对照 A5 / A6 / A7 / A8 / A9 / A10 / A11 / A12 / A13 打分，记入 Completion Notes。
3. L2 真机：两类系统推送到达、点击落点（`PET_DIARY_POSTED` → 帖子；`PET_FOLLOWED` → 追更者主页）；关闭「Hewan yang kamu ikuti」后不再推送但通知中心照收。

## Tasks / Subtasks

- [ ] **T0 开工核对**：分支；1.1 ~ 1.4 已在本分支；后端 JSON 与「接口契约」逐字段核对；Dev Notes 行号未漂移
- [ ] **T1 数据层**：`ApiPaths.userPetFollows(token)`；`FollowedPet` 模型 + `FollowedPetPage`；`PublicProfile.petFollowingCount`；`FeedItem.reason`、`FeedPage` 两个计数；`NotificationItem.petName`；`PushCategoryCode.petDiary`
- [ ] **T2 关注列表**（AC1 ~ AC4、AC9）：筛选胶囊（抽 `_Tab` 为公共组件）；Hewan 分页列表 + `FollowedPetRow`；自己 / 别人两态；A8 空态 + 推荐卡（`_StripPetTile` 公共化 + 宽度 / 底部插槽）
- [ ] **T3 关注流**（AC5）：控制器读计数；`FollowingEmptyView` 加变体；`reason` 进埋点
- [ ] **T4 通知中心 + 深链**（AC6）
- [ ] **T5 推送设置**（AC7）
- [ ] **T6 文案**（AC8）：两份 arb + `flutter gen-l10n`
- [ ] **T7 测试**（见「测试」）
- [ ] **T8** `flutter analyze` + `flutter test`；Completion Notes 标「**L1/L2 待本地验收**」（L2 还原度打分在本地模拟器做）

## Dev Notes

### 现状（改动前必读，2026-11-04 核 `dev_1.4.2` @ c496e3c3）

路径前缀 `A/` = `petgo_app/lib/`。

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `A/features/social/presentation/follow_list_page.dart` | 路由 `/users/:userId/follows?tab=`；`publicProfileProvider(userId)` 取 token / self / 昵称；两 Tab `TabBarView`；`_FollowListTabView`（keep-alive、首页到达报 `follow_list_viewed`、下拉刷新连推荐一起拉）；我自己的 Mengikuti = 常驻（`_persistent`:217）；`_selfEmpty`（:316，Pengguna 为空的提示条 + 推荐关注）；`_empty`（:350-364，顶距 120 / 图标 48 / 标题 `AppTypography.title`）；`_ListError`（:368） | Mengikuti Tab 内加筛选与 Hewan 列表、A8 / A9 | Pengikut Tab、Pengguna 列表全部行为；「绝不把请求错误画成空态」 |
| `A/features/social/presentation/widgets/follow_user_row.dart` | 用户行（`FollowListItem`，头像 40、昵称 15 / w600、`subtitle` 12、按钮） | 只参照数值，新写 `FollowedPetRow` | — |
| `A/features/social/presentation/widgets/follow_button.dart` | `_listCompact`（:173-215）外观 | 1.4 已抽外观函数，本 story 复用 | — |
| `A/features/content/presentation/feed_tab_row.dart` | `_Tab`（:51-80）私有：内边距 13 / 7、圆角 999、12 / w600、间距 7 | 抽公共胶囊组件（可带数字） | Discover 外观 |
| `A/features/profile/presentation/widgets/pet_recommendation_strip.dart` | `_StripPetTile`（:134-216）私有，宽 `PetRecommendationStrip.cardWidth`（65） | 公共化，加 `width`、`bottom` 插槽 | 首页 65 宽、无按钮、key `recommendedPet_{petId}` |
| `A/features/profile/presentation/widgets/recommended_pet_card.dart` | `from` 常量（:15-45）、`openRecommendedPet`（:233，同一落点 / 埋点 / 登录门控） | 加 `kPetRecommendFromFollowListEmpty` | 既有取值 |
| `A/features/profile/data/pet_recommendation_repository.dart` | `petRecommendationsProvider`（:142，autoDispose） | 复用 | — |
| `A/features/content/presentation/following_tab_view.dart` | `state.isEmpty` → `FollowingEmptyView`（:61）；有帖 → `FeedMasonryView(header: FollowingStrip, feedTab: 'following', …)` | 空态分两种 | 头像排只列用户 |
| `A/features/content/presentation/widgets/following_empty_view.dart` | 版式（:20-67）：96 圆 / 图标 42 / 24 / 20 w700 / 10 / 14.5·1.45 / 16 / `SuggestedFollowList(maxItems:5, emphasized:true)` / 16 / Jelajahi Discover | 加变体参数 | 现有空态逐像素不变 |
| `A/features/content/presentation/following_feed_controller.dart` / `A/features/content/domain/feed_item.dart` | `FeedPage.fromJson`（:301-315）读 items / nextCursor / hasMore / rankMode；`FeedItem.fromJson`（:249 起） | 读两个计数、`reason` | 关注快照 `followEpoch` 写法 |
| `A/features/notify/domain/notification_item.dart` | `serverCopyTypes`（:27-33）、`hasServerCopy`（:72） | 加两类型、`petName` | — |
| `A/features/notify/presentation/notification_center_page.dart` | `_notifTypeForAnalytics`（:275）；`_onTap`（:297 起；`USER_FOLLOWED` 用 `actorUserId` :341-370）；`_postGone`（:395 起）；`_iconStyle`（:773）；`_typeLabel` / `_typeBody`（:939 / :983）；`_serverTitle`（:1097）；`_followBack`（:1117） | 见 AC6 | `USER_FOLLOWED` 回关、收礼跳转等既有行为 |
| `A/core/router/deep_link_routes.dart` | `switch (type)`（:151-190），`USER_FOLLOWED` / `PET_GIFT_RECEIVED` 非数字落通知中心 | 加两 case | default 兜底 |
| `A/features/notify/domain/push_settings.dart` | `PushCategoryCode.all` 9 项、顺序 = 后端声明顺序 | 加 `petDiary` | `alwaysLocked` |
| `A/features/notify/presentation/push_settings_page.dart` | `_groups`（:64-82）、`_copy`（:84-94）、`_row`（:338） | 加行、改 FOLLOWER 说明两行 | 其余分组与样式 |
| `A/features/user_profile/data/public_profile_repository.dart` | `PublicProfile.followingCount`（:86、:144） | 加 `petFollowingCount` | — |
| `A/app.dart` | `resetUserScopedCaches`（:362） | 登记新列表 provider | — |
| `petgo_app/test/analytics/v112_events_test.dart` | `pet_` 前缀已在表；1.4 已扩 `_follow` / `_unfollow` 词尾 | — | — |

### UI 关键数值（A5–A13 说明原文摘要）

- A5：Tab 14 / w600（`follow_list_page.dart`:146）；胶囊 7 / 13、间距 7（`feed_tab_row.dart`:39,63）；数量与文字间距 4、tabular-nums；底色 `AppColors.base`。
- A6 / A7：行 = 头像 40 + 名字 15 / w600 + 第二行 12 `muted` + 右侧 88×30 按钮；无头像 emoji 20 为稿件占位；刚取消的「Ikuti」`violet100` 浅紫底紫字、与「Mengikuti」同列对齐同宽 88。
- A8：空态顶距 120（`follow_list_page.dart`:357）；空态到推荐标题 32（`xxl`）；方图 88 / 圆角 12（`AppRounded.md`）；小头像 18 / 探出 −4·−6；按钮 88×30（`follow_button.dart`:172-196）、上方间距 8、热区 44。
- A11：圆 96、图标 42、标题 20 / w700、上距 24、说明 14.5 / 1.45（`following_empty_view.dart`:44）、上距 10；推荐列表照 `suggested_follow_list.dart`（标题「Disarankan untukmu」17 / w700 上 16 下 4；行上下 14、行间无分隔线；按钮实心高 36、圆角 18、最小宽 78、左右 16、13 / w700；对方已关注我显示「Ikuti Balik」；加载中骨架 320、失败或空整段隐藏）。
- A12：标题 13.5（`notification_center_page.dart`:1110,1288）、图标块圆角 11（:1161）、条目圆角 14（:1242）、标题到正文 3、到时间 6（:1214,1226）。
- A13：页面左右 20、组间距 22（`push_settings_page.dart`:211,215）；行上下 11（:343）；图标 19 / 圆 36（:347-350）；分隔左缩进 64（:423）。

### 拍板点（按建议实现，Completion Notes 复述）

- **① 宠物行新写组件、胶囊与推荐小卡抽公共组件**：避免复制样式；首页 / Discover 现有调用像素不变（各加一个回归 widget 测试）。
- **② 「Hewan N」取主页 `petFollowingCount`**：进页即有，无需等列表首页；列表首页的同名字段只作校准（不一致时以列表为准刷新胶囊）。
- **③ `PET_FOLLOWED` 系统推送直达追更者主页**：1.2 已按拍板把 `targetRef` 定为追更者 user id。
- **④ `reason` 先挂在已有交互事件上**：不擅自新增曝光 / 点击事件名（见「待确认」1）。

### 实现提示

- Hewan 列表与 Pengguna 列表在同一个 Tab 内切换：建议 Mengikuti Tab 内部是 `Column[筛选行, Expanded(IndexedStack 或两个 keep-alive 子视图)]`，保证切换不丢滚动位置与已加载页。
- `FollowedPetRow` 的按钮：`persistent: ownerIsSelf`；别人的列表不渲染按钮（AC3）。
- 推荐卡追更成功后**不**从卡片列表移除（非常驻），但 `petFollowStateProvider` 会让同一只宠物在其他位置（访客页）同步显示 Mengikuti。
- `_serverTitle` 加粗两段：先收集 `[(actorName), (petName)]` 中非空、在标题中能找到的片段，按 index 排序后切分拼 `TextSpan`；找不到的片段忽略（防后端文案语序变化）。
- **不要整文件 `dart format`**；改 ARB 后 `flutter gen-l10n`。

### 架构约束

- D-8 / G-1：列表接口、`petFollowingCount`；D-9：关注流两种空态靠首页两个计数区分、`reason`；D-10 / D-23：两类型进 `serverCopyTypes`、`PET_FOLLOWED` 点击进追更者主页且无回关、`PET_DIARY_POSTED` 进帖子详情走「已不存在」探测、`PushCategoryCode` 新分类放 INTERAKSI 组「Pengikut baru」之后、「Pengikut baru」说明两行；D-17：推荐接口已排除已追更。
- §1.2 第 12 条：新增通知 type 在 App 至少改深链映射、`_typeLabel / _typeBody`、`_iconStyle`、`_notifTypeForAnalytics` —— 本 story 全部覆盖。
- P-4：追更相关新代码在 `features/social`；P-9：事件名字面量；P-10：新 key 前缀；P-12：widget 测试覆盖稿中每种状态。
- 护栏：App 不渲染后端显示串 —— **例外**仅限 `serverCopyTypes` 内的后端拼好文案条目与既有 `_storedTitle` 类型（本 story 两类型属于 I5 明确批准的例外）；用户可见字符串不硬编码；不放开 `textScaler`。

### 测试

**L0（云端可跑）**
- `test/social/follow_list_pet_filter_test.dart`（新）：Mengikuti Tab 有两个胶囊、默认 Pengguna、无「全部」；Pengikut Tab 无胶囊；胶囊数字来自主页 `followingCount` / `petFollowingCount`；切 Hewan 后再切回 Pengguna 不重新请求。
- `test/social/followed_pet_list_test.dart`（新）：自己的列表行（头像 / 名 / 「Kucing · milik @X」/ Mengikuti）；点 Mengikuti → 行保留且按钮 Ikuti、无确认；下拉刷新后该行消失、胶囊数 −1；点行跳 `/pets/{id}`；别人的列表无按钮；加载中骨架；失败 → 错误态 + 重试（**不是空态**）；翻页加载更多与失败重试；埋点 `pet_follow` / `pet_unfollow` `source=follow_list`。
- `test/social/followed_pet_empty_test.dart`（新）：自己空 → 空态文案 + 推荐区（标题、卡宽 88、Ikuti 按钮）；推荐空 / 失败 → 整块隐藏；别人空 → 只空态、无推荐；推荐卡点按钮 = 追更（`source=pet_recommendation`）且卡不移除；点卡片 = `openRecommendedPet(from: 'follow_list_empty')`。
- `test/profile/pet_recommendation_strip_test.dart`（补）：首页小卡仍 65 宽、无按钮、key 不变。
- `test/content/following_tab_view_test.dart`（补）：计数都 0 → 现有空态（`followingEmptyTitle`）；`followingPetCount=1` 且无帖 → A11 文案 + 图标 `schedule` + 推荐关注 + Jelajahi Discover；计数缺失 → 现有空态；有帖 → 列表（无新元素）。
- `test/content/following_feed_controller_test.dart`（补）：首页解析两个计数、`reason`；加载更多不覆盖首页计数。
- `test/notify/pet_follow_notification_test.dart`（新）：`PET_FOLLOWED` 标题加粗昵称与宠物名、图标 `person_add_rounded`/`violet100`、**无回关按钮**、点击进 `openUserProfile(actorUserId)`；`PET_DIARY_POSTED` 加粗宠物名、图标 `pets_rounded`/`violet100`、点击进 `/content/{id}`、帖子已删 → toast 不跳；无 `actorName` → 读后端 `title` 兜底；`_notifTypeForAnalytics` 两个取值。
- `test/notify/deep_link_routes_test.dart`（补）：`PET_DIARY_POSTED` 数字 / 非数字；`PET_FOLLOWED` 数字 → 追更者主页 / 非数字 → 通知中心。
- 推送设置测试（补，现有文件名以仓库为准）：10 行、新行在「Pengikut baru」之后、图标 / 文案、默认开、切换发 PATCH `{PET_DIARY:false}`；「Pengikut baru」两行说明。
- `PushSettings.fromJson`（补）：认 `PET_DIARY`、缺失视为开启。
- 跑：`flutter gen-l10n && flutter analyze && flutter test`。

**L1（本地，连后端）**：真实列表翻页、取消后刷新消失；只追更未关注的空关注流显示 A11；通知中心两类条目真实数据；推送开关 PATCH 生效。

**L2（本地 Android 模拟器 / 真机）**：A5 ~ A13 逐屏还原度 **≥ 95%**；真机推送两类到达与点击落点。

> 云端只能跑 L0：Completion Notes 必须写「**L1/L2 待本地验收**」，story 不得标 done。

### Previous Story Intelligence（Story 1.1 ~ 1.4）

- 1.1：列表行 `ownerToken` 封号也给（可取消）；主人 DEACTIVATED 的宠物仍在列表；`petFollowingCount` 首页才有。
- 1.2：`PET_FOLLOWED` 的 `targetRef` 是追更者 user id（宠物 id 在后端 `variant`，不下发）；`PET_DIARY_POSTED` 宠物已删时后端返回不带 `actorName` 的原样条目。
- 1.3：`followingUserCount` 含系统自动关注的官方号 → 「都为 0」很少见，测试用 mock 数据覆盖两种分支即可。
- 1.4：`PetFollowButton`（两种外观）、`petFollowStateProvider`（已登记 `resetUserScopedCaches`）、`PetFollowRepository` 已就绪；词尾白名单已扩；访客页追更成功后预留了「失效我的 Hewan 列表」调用点 —— 本 story 接上。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-social-topic.md#Story 1.5]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html#A5 ~ A13 每屏说明；研发执行说明「已定的设计决定」「实现提示」]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md#3.1 列表 / 追更了能看到什么 / 主人一侧；AC5 ~ AC8、AC12；§4 FR-153；§7]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md#§1.2 第 12 条、D-8、D-9、D-10、D-17、D-23、G-1、P-4、P-9 ~ P-12、§5.2]
- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-social-topic.md#R-2、I5]
- [Source: petgo_app/CLAUDE.md#i18n、测试、键盘避让、易踩点]

## 待确认 / 发现的规划冲突

1. **PRD §4「关注流曝光与点击新增属性 `reason`」没有可挂的现有事件**：App 现在没有关注流（或 Discover）卡片的曝光 / 点击埋点（只有顶置坑位的 `social_pinned_slot_viewed/_tapped`、点赞 `post_like_tapped` 等）。本 story 只把 `reason` 加到关注流里已有的交互事件；是否新增 `following_post_viewed` / `following_post_tapped`（`following_` 前缀已在守卫表）请主会话拍板。
2. ~~`PET_FOLLOWED` 系统推送点击落点~~ **已拍板（2026-11-04）**：`targetRef` = 追更者 user id，推送与列表点击都进追更者主页。
3. **UI A5 写胶囊字号 13，代码是 12**：UI 说明「胶囊 13 / 间距 7（`feed_tab_row.dart`:39,63）」，但 `feed_tab_row.dart`:72 实际 `fontSize: 12`（:39 是间距 7、:63 是内边距）。按研发执行说明「数值照代码出处取」用 12。
4. **PRD §7 没有 `PET_FOLLOWED` / `PET_DIARY_POSTED` 的 App 文案**：由后端拼（决策日志 I5 已明确），App 不加 arb；但两类型在 App 侧需要「后端无 `actorName` 时」的兜底 —— 本 story 用后端落库的 `title`（AC6.7）。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created

### File List
