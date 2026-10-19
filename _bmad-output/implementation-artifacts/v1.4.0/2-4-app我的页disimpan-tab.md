# Story 2.4: App「我的」页 Disimpan Tab

Status: review

> 版本 v1.4.0 · 主题 push-collect · 分支 `feat/1.4.0-push-collect`
> 口径优先级：`decision-log.md` ④ 节 > `推送开关与收藏/1-4-0推送开关与收藏prd.md`（§3.2、§4、§6.5 ⑤⑥⑦）> `architecture-v1.4.0-push-collect-delta.md`（AD-11、AD-16、AD-17、P-5 ~ P-8）> UI 稿 `推送开关与收藏/ui-1-4-0-push-collect-integrated-v1.html` E1 ~ E9（均在 `_bmad-output/planning-artifacts/v1.4.0/`）。云端规则见同目录 `cloud-rules-push-collect.md`。
> 前置：**Story 2.1**（`DELETE /api/v1/content-posts/{id}/collect`、`DELETE /api/v1/places/{token}/collect`，取消是否发 `ContentUncollectedEvent` 由后端按 P-7 判断）、**Story 2.2**（`GET /api/v1/me/collections/posts|places?cursor=`）、**Story 2.3**（`features/collect/data/` 收藏仓库、详情页收藏按钮、Toast「Lihat」→ `context.go('/me?saved=post|place')`、共用 arb 键）。
> 范围层：**仅 App**。UI 稿 E1 ~ E9。**无迁移、无后端改动**。
> 🔴 与 ①②③ 并行：「我的」页**只改 Postinganku 小节**；② 的 Hewanku 改版（UI 稿 E 组底稿里的「Kelola」、多宠物头像排）、① 的等级胶囊在本分支**不存在**，不得引用、不得自造；合并在 3.1 对齐分支做。

## Story

As a 用户,
I want 在「我的」页切到 Disimpan 看我收藏的帖子和场所,
so that 想再看时能找到。

## Acceptance Criteria

**AC1 — Tab 行替换 Postinganku 小节标题（E1）** `[L0]` `[L2]`（AD-16、PRD AC8a、§6.5 ⑤）
1. `me_page.dart` 把 `Padding(bottom: sm) + _SectionTitle(l10n.meMyPostsTitle)`（:97-101）换成 Tab 行：左「Postinganku」（`meMyPostsTitle`，**不再大写**）、右「Disimpan」（`collectSavedToast`，2.3 建，Toast 与 Tab 同字面量共用）+ 13 号锁图标（`Icons.lock_outline_rounded`）。样式（UI 稿 `.mtabs`）：两项间距 22；每项上下内边距 8 / 9；字号 15、w600；未选中 `AppColors.muted`、选中 `AppColors.ink` + 2.5px `AppColors.mint` 下划线；锁图标颜色随文字；整行底部 1px `AppColors.line2` 分隔线（下划线压在分隔线上）。Key：`meTabMyPosts` / `meTabSaved`。
2. **默认停在 Postinganku**，其下内容即原 `_MyPostsList`（:498-544），**一字不改**（含加载中占位、失败静默 `SizedBox(height: 1)`（:509）、空态 `meNoPosts`、2 列网格）；Tab 行与网格间距 `AppSpacing.md`（12，UI 稿 `.grid margin-top:12`）。
3. Tab 行之上的模块（名片、四格、Edit Profil、Hewanku、Aktivitas）位置与样式不变；`_SectionTitle` 类保留（Hewanku / Aktivitas 仍在用）。
4. 他人主页 `public_profile_page.dart` **不改**，不出现 Disimpan（AC8a）。

**AC2 — Disimpan 内容：分段 + 隐私横条 + 列表（E2 / E6）** `[L0]` `[L2]`（AD-11、AD-16、PRD AC7 / AC8 / AC8a）
1. 切到 Disimpan 后依次为：
   - 分段胶囊「Postingan」（复用 `profilePostsTitle`）/「Tempat」（复用 `placeDetailTitle`），样式照 `FeedTabRow` 的 `_Tab`（`feed_tab_row.dart`:47-80：内边距 13 / 7、圆角 999、1.5px 描边、12 号 w600；选中 `mint` 底白字、未选中白底 `line` 描边 `textSecondary` 字）；两枚间距 8；距 Tab 行 12。默认 Postingan。Key：`savedSegPost` / `savedSegPlace`。`FeedTabRow` 绑定 `FeedCategory` 枚举不能直接复用 → 在本功能内写一个同规格的小组件，**不改** `FeedTabRow`。
   - 隐私横条「🔒 Hanya kamu yang bisa melihat」（`collectPrivacyHint`）：`AppColors.cream2` 底、`AppColors.mint700` 字 12 号 w500、锁图标 13、圆角 10、内边距 10 / 7、上 12 下 4。Key：`savedPrivacyHint`。**加载中 / 有数据 / 空态时都显示，失败态不显示**（UI 稿 E9 无横条）。
   - 列表：按接口顺序原样渲染（后端已按收藏时间倒序，**客户端不重排**）；每行底部 1px `line2` 分隔。
2. **帖子行 `CollectedPostRow`（E2）**：整行可点（`InkWell`，Key `collectedPostRow_{postId}`）；上下内边距 12；左缩略图 64 × 64（圆角 12、1px `lineViolet` 前景描边，`coverUrl` 为空或加载失败 → `PostCoverPlaceholder(type)`）；间距 12；右侧垂直居中：正文摘要（`excerpt`——帖子表没有标题列，2.2 只下发摘要；为空时显示类型名；14 号 w600、`ink`、行高 1.35、最多 2 行省略）→ 间距 3 → 作者行（`InitialAvatar` 半径 9 + 间距 6 + 昵称 12 号 `ink2` 单行省略 +「 · 」+ 类型标）。类型标文案与配色**取 `PostGridTile` 的 badge 映射**（`post_grid_tile.dart`:44-53：`GROWTH_MOMENT` → Diary 绿、`DAILY` → Momen 紫、`KNOWLEDGE` → Edukasi & Tips 黄），胶囊 9 号 w700、圆角 5、内边距 6 / 2（同 `PostGridTile` :107-118）。
3. **场所行 `CollectedPlaceRow`（E6）**：整行可点（Key `collectedPlaceRow_{placeToken}`）；行规格同帖子行（缩略图 64、圆角 12、`lineViolet` 描边；无图 / 失败占位照 `_Thumb` 的 `cream2` 底 + `Icons.place_outlined` 22 `textTertiary`，`place_list_page.dart`:707-737）；右侧：名称（14 w600 `ink`，最多 2 行）→ 类型（`PlaceType.fromApi(type)?.label(l10n)`，未知类型整行省掉，不兜底「其他」，同 `place_summary.dart`:30 的约定）→ 地址（`addressText`，12 号 `muted`，最多 2 行；为空省掉）。**不显示**距离、标签、计数（UI 稿 E6）。
4. 正常条目点击：帖子 `context.push('/content/$postId')`（`app_router.dart`:1057）；场所 `context.push(PlaceDetailPage.routeFor(placeToken))`（`place_detail_page.dart`:117，不带 `from`，见拍板点③）。
5. Given 刚在帖子详情收藏一条帖子，When 回到「我的 → Disimpan → Postingan」，Then 它在最上方（AC7）；取消收藏后再回来，列表中已没有它。场所同理（AC8）。
6. 分页：每页 20（后端定），`nextCursor` 非空即有下一页；列表底部「Muat lagi」（`collectLoadMore`，Key `savedLoadMore`）点击追加；加载中 20px 小圈；加载更多失败 → 保留已加载、底部「Coba lagi」（`commonRetry`，Key `savedLoadMoreRetry`），**不整块报错、不回顶**（照 `pawcoin_page.dart` `_LoadMoreFooter` :199-240）。「我的」页本身是 `ListView` 无下拉刷新，本 story **不加** `RefreshIndicator`（见拍板点②）。

**AC3 — 不可见条目与移除（E3 / E4 / E7）** `[L0]` `[L2]`（AD-11、AD-16、PRD AC9 / AC9a / AC11c、§6.5 ⑤）
1. 判定：接口行 `available == false` → 不可见；**防御**：`available == true` 但帖子行 `post == null` 也按不可见渲染（不崩、不露空白）。
2. **不可见帖子（E3）**：**不显示原标题、作者、封面、类型标**（接口本就不给）。缩略图位：白底 + 1.5px `lineViolet` 虚线圆角 12（复用 `DashedRRectPainter`，`dashed_rect.dart`:5）+ 居中 `Icons.visibility_off_outlined` 22 `AppColors.dashedViolet`；文字：「Konten tidak tersedia lagi」（`collectUnavailablePost`，14 号 w500 `muted`）→ 间距 3 →「Ketuk untuk menghapus」（`collectTapToRemove`，12 号 w600 `mint`，前置 `Icons.bookmark_remove_outlined` 14 `mint`、间距 4）。位置保留在原收藏时间处。
3. **不可见场所（E7）**：保留名称（14 号 w500 `muted`）；缩略图位置画灰色占位块（底色 `Color(0xFFF4F3F7)`、居中 `Icons.place_outlined` 灰色、整体 `Opacity(0.45)`，无描边）——**2.2 对不可见场所不下发 `coverUrl`**（下架可能正因图片违规，不让原图回到客户端），用占位块表达 UI 稿的「图片置灰」（规划复核 2026-10-18 统一口径）；名称下「Tempat tidak tersedia lagi」（`collectUnavailablePlace`，12 号 `muted`）→「Ketuk untuk menghapus」（同上）。不显示类型与地址。
4. 点不可见条目 → `showConfirmSheet(danger: true)`（`confirm_sheet.dart`:21）：`icon: Icons.bookmark_remove_outlined`、标题 `collectUnavailablePost` / `collectUnavailablePlace`、`message` `collectRemovePostMessage`「Postingan ini mungkin sudah dihapus atau disembunyikan. Hapus dari Disimpan?」/ `collectRemovePlaceMessage`「Tempat ini mungkin sudah dihapus. Hapus dari Disimpan?」、`confirmLabel` `collectRemoveConfirm`「Hapus dari Disimpan」、`cancelLabel` `commonCancel`「Batal」；Key `savedRemoveConfirm` / `savedRemoveCancel`。**用 `onConfirm` 异步钩子**（`confirm_sheet.dart` 头注释 V1.1.4 Story 1.2）：钩子里调 2.3 仓库的取消收藏（帖子 `DELETE /content-posts/{postId}/collect`、场所 `DELETE /places/{placeToken}/collect`）。
   - 成功（2xx，或 404 —— 收藏行已不存在，结果等同移除）→ 钩子返回 true、抽屉关闭 → 列表**本地移除该行**（不整页重拉、不回顶）→ Toast「Dihapus dari Disimpan」（`collectRemovedToast`）。
   - 失败（网络 / 5xx）→ Toast「Gagal membatalkan, coba lagi」（2.3 建的取消失败键，见 AC7）且 `top: true`（抽屉开着，底部 Toast 会压在按钮上，`app_toast.dart` 头注释）；钩子返回 false，抽屉**保持打开**可直接重试；列表不变。
   - 取消 / 点遮罩：什么都不发生。
5. **App 不区分是否发 `ContentUncollectedEvent`**：移除不可见帖子调的就是普通取消收藏接口，「此刻不可见 → 不发事件」由后端按 P-7 判断（AC11c）。App 不传任何额外标记。
6. 正常（可见）条目**不提供**移除入口（PRD 只要求不可见条目可移除；可见条目到详情页取消）。
7. Given 收藏的帖子被作者删除，When 打开 Disimpan → Postingan，Then 该行显示「Konten tidak tersedia lagi」+「Ketuk untuk menghapus」、不显示原标题与作者；点击 → 确认 → 该行消失 + Toast「Dihapus dari Disimpan」（AC9）。Given 收藏的场所被下架，Then 该行名称灰、缩略图为灰色半透明占位、显示「Tempat tidak tersedia lagi」，可同样移除；场所恢复后再进入列表即正常显示（AC9a，读时判断，App 无缓存逻辑）。

**AC4 — 空态与失败态（E5 / E8 / E9）** `[L0]` `[L2]`（PRD §6.5 ⑤）
1. 空态（首页 `items` 为空且无下一页）：`EmptyState`（`empty_state.dart`:11）`icon: Icons.bookmark_border_rounded`、`iconBackground: AppColors.cream2`、**`iconColor: AppColors.mint`**（见 AC6 对 `EmptyState` 的最小扩展）；帖子「Belum ada postingan disimpan」+「Ketuk ikon simpan di postingan untuk menyimpannya di sini.」（`collectEmptyPostsTitle` / `collectEmptyPostsHint`），场所「Belum ada tempat disimpan」+「Ketuk ikon simpan di halaman tempat untuk menyimpannya di sini.」（`collectEmptyPlacesTitle` / `collectEmptyPlacesHint`）。**不放按钮**。上方保留分段与隐私横条。Key：`savedPostsEmpty` / `savedPlacesEmpty`。
2. 首屏加载失败（E9）：只在 Tab 内容区（分段下方）显示 `EmptyState` `icon: Icons.cloud_off_outlined`、`iconBackground: cream2`、图标 `muted`（默认色）、标题「Gagal memuat」（`collectLoadFailedTitle`）、说明「Periksa koneksi lalu coba lagi.」（`collectLoadFailedHint`）、按钮「Coba lagi」（`commonRetry`）→ 重新拉当前子分类首页。Key：外包 `KeyedSubtree(key: ValueKey('savedError'))`（`EmptyState` 自身按钮 Key 固定为 `emptyStateAction`）。**上方名片、四格、Hewanku、Aktivitas、Tab 行、分段照常**，隐私横条不显示。
3. 首屏加载中：分段 + 隐私横条下方一个 20px 小圈（内边距 `lg`），不画骨架。
4. **Postinganku 加载失败仍静默**（现状 :509），本 story 不改（PRD §6.5 ⑤、UI 稿 E9 注）。
5. Given 无收藏，When 切到 Disimpan，Then 帖子 / 场所各自显示对应空态、无按钮；Given 网络断开，When 切到 Disimpan，Then Tab 内容区显示「Gagal memuat」+「Coba lagi」，名片照常；恢复网络点「Coba lagi」→ 正常列表。

**AC5 — `/me?saved=post|place` 落点与列表刷新** `[L0]` `[L2]`（AD-15、AD-16、PRD §6.5 ⑥）
1. 路由：`_tabRootPage` 的 `AppTab.me => const MePage()`（`app_router.dart`:195）改为 `MePage(saved: state.uri.queryParameters['saved'])`（照同函数里 Toko 读 `?category=` 的写法，:193）。`/me` 是 shell 分支根（`StatefulShellBranch` 循环生成，:1218-1229；`DeepLinkRoutes.shellTabRoots` 含 `/me`，`deep_link_routes.dart`:24），已在 `_controlledLocations`（:158-165），游客命中被 redirect 到 `/home`（redirect 比对的是无 query 的 path）。
2. `MePage` 增加可选参数 `final String? saved;`（`const MePage({super.key, this.saved})`，既有 `const MePage()` 调用与测试零影响）。Postinganku / Disimpan 的选中态与子分类放在一个新的有状态小组件里（建议 `_PostsArea`，ListView 中替换 :97-102 两项），**`initState` 与 `didUpdateWidget` 都处理 `saved`**（照 `toko_page_v2.dart`:71 / :82-83）：`'post'` → Disimpan + Postingan；`'place'` → Disimpan + Tempat；其他值 / null → 不改当前选中。
3. **消费后清参数**：处理完非空 `saved` 后，在 post-frame 里 `GoRouter.maybeOf(context)?.go('/me')`（`maybeOf`：单测里 `MaterialApp(home: MePage())` 没有 GoRouter，不得抛）。理由：分支记住的 location 若一直是 `/me?saved=post`，用户手动切回 Postinganku 后再从详情页点「Lihat」，参数值不变 → `didUpdateWidget` 不触发 → 停在 Postinganku（Toko 的 `?category=` 有同样的坑，本 story 不顺手改 Toko）。
4. **滚动到 Tab 行**：处理非空 `saved` 后，post-frame 对 Tab 行（`GlobalKey`）`Scrollable.ensureVisible(alignment: 0, duration: 250ms)`；context 为空则跳过（UI 稿 E2「已向上滚动」；否则用户落在页顶、看不到 Disimpan）。
5. **列表失效**（AD-16「收藏 / 取消成功后 invalidate 对应列表」）：2.3 已建变更信号 `collectionsRevisionProvider(CollectTarget)`，按钮成功后 `bump()`。本 story 的两个列表 provider 在 `build()` 里 `ref.watch(collectionsRevisionProvider(CollectTarget.post|place))`，信号一变自动重拉——**不改 2.3 的按钮代码**。T0 核对 2.3 实际的信号名。
6. Given 在帖子详情收藏后点 Toast「Lihat」，When 落到「我的」，Then Disimpan 与「Postingan」被选中、Tab 行滚到可见、刚收藏的帖子在第一条；场所详情点「Lihat」→ Disimpan + 「Tempat」。Given 用户随后手动切回 Postinganku、再去另一帖子收藏并点「Lihat」，Then 仍正确切到 Disimpan + Postingan。

**AC6 — 数据层与缓存登记** `[L0]`（AD-11、AD-16、`CLAUDE.md` 换账号缓存重置）
1. 落位：在 2.3 建的 `features/collect/` 下补本 story 的部分（T0 核 2.3 实际文件名与类名，有出入以 2.3 为准并先改本文件）：
   - `core/network/api_paths.dart`：加 `meCollectionPosts = '$base/me/collections/posts'`、`meCollectionPlaces = '$base/me/collections/places'`（放在 `mePosts` :129 附近）。
   - `features/collect/domain/collected_item.dart`：`CollectedPost { postId (int), collectedAt (DateTime), available (bool), post: CollectedPostSummary? }`、`CollectedPostSummary { type, excerpt?, coverUrl?, authorNickname, authorAvatarUrl? }`（解析 `post.author.nickname / avatarUrl`）、`CollectedPlace { placeToken, collectedAt, available, name, type?, coverUrl?, addressText? }`（`place` 为 null 时后三项为 null）；`fromJson` 对缺字段宽容（缺 `available` 视为 false）。
   - 仓库：在 2.3 的收藏仓库上加 `fetchCollectedPosts({String? cursor})` / `fetchCollectedPlaces({String? cursor})`，返回 `({List<…> items, String? nextCursor})`；`queryParameters: {'cursor': ?cursor}`（同 `my_posts_repository.dart`:47-50）。取消收藏**直接复用** 2.3 的方法，不另写 DELETE。
   - 列表状态 `features/collect/presentation/collection_list_controller.dart`：`CollectionListState<T> { items, nextCursor, loadingMore, loadMoreFailed }`（`hasMore => nextCursor != null`）；`CollectedPostsController` / `CollectedPlacesController extends AsyncNotifier`，方法 `loadMore()`、`removeLocal(key)`（帖子按 `postId`、场所按 `placeToken`）、`retry()`（`ref.invalidateSelf()`）。写法照 `pawcoin_controller.dart`（:44-80）。
   - Provider：`collectedPostsProvider` / `collectedPlacesProvider`，**`AsyncNotifierProvider.autoDispose`**：只在 Disimpan 且对应子分类可见时被 `watch`；切走即释放，再切回重新拉首页（满足「刚收藏的在最上方」）。
2. **默认态不发请求**：停在 Postinganku 时两个列表 provider 都不被读（单测用 fake 仓库计数断言 0 次）。「我的」页在 `IndexedStack` 常驻，Disimpan 可见期间 provider 一直存活 → 必须靠 AC5.5 的失效保证新鲜。
3. **换账号清理**：`lib/app.dart` 的 `resetUserScopedCaches`（:355-390）加 `ref.invalidate(collectedPostsProvider)` 与 `ref.invalidate(collectedPlacesProvider)`（写一行注释：「V1.4.0 ④ Disimpan：「我的」页常驻，Disimpan 可见时 autoDispose 不回收，不登记 = 换账号看到上个账号的收藏（隐私）」）；`test/app/user_scoped_cache_registry_test.dart` 的 `_mustReset`（:13-33）追加两项（加一行 `// V1.4.0 push-collect` 注释）。
4. 子分类与 Tab 选中态是页面内存态，不持久化、不进 prefs；换账号时 `MePage` 不重建也无妨（列表数据已失效重拉）。

**AC7 — 文案（新增 arb，EN / ID 两份；前缀 `collect*`，与 2.3 不重名）** `[L0]`

本 story 新建：

| key | EN | ID | 用处 |
|---|---|---|---|
| `collectPrivacyHint` | Only you can see this | Hanya kamu yang bisa melihat | 隐私横条 |
| `collectUnavailablePost` | Content no longer available | Konten tidak tersedia lagi | 不可见帖子行 / 抽屉标题 |
| `collectUnavailablePlace` | Place no longer available | Tempat tidak tersedia lagi | 不可见场所行 / 抽屉标题 |
| `collectTapToRemove` | Tap to remove | Ketuk untuk menghapus | 不可见条目 |
| `collectRemovePostMessage` | This post may have been deleted or hidden. Remove from Saved? | Postingan ini mungkin sudah dihapus atau disembunyikan. Hapus dari Disimpan? | 移除抽屉 |
| `collectRemovePlaceMessage` | This place may have been removed. Remove from Saved? | Tempat ini mungkin sudah dihapus. Hapus dari Disimpan? | 移除抽屉 |
| `collectRemoveConfirm` | Remove from Saved | Hapus dari Disimpan | 抽屉主按钮 |
| `collectRemovedToast` | Removed from Saved | Dihapus dari Disimpan | 移除成功 Toast |
| `collectEmptyPostsTitle` | No saved posts yet | Belum ada postingan disimpan | E5 |
| `collectEmptyPostsHint` | Tap the save icon on a post to keep it here. | Ketuk ikon simpan di postingan untuk menyimpannya di sini. | E5 |
| `collectEmptyPlacesTitle` | No saved places yet | Belum ada tempat disimpan | E8 |
| `collectEmptyPlacesHint` | Tap the save icon on a place page to keep it here. | Ketuk ikon simpan di halaman tempat untuk menyimpannya di sini. | E8 |
| `collectLoadFailedTitle` | Couldn't load | Gagal memuat | E9 |
| `collectLoadFailedHint` | Check your connection and try again. | Periksa koneksi lalu coba lagi. | E9 |
| `collectLoadMore` | Load more | Muat lagi | 加载更多 |

- **与 2.3 的键归属**（2.3 先做，由 2.3 建；本 story 只复用，**不得重复建**）：「Disimpan」→ `collectSavedToast`（EN「Saved」，2.3 用于收藏 Toast、本 story 用作 Tab 标签）；「Gagal membatalkan, coba lagi」→ `collectRemoveFailed`（2.3 用于取消失败 Toast、本 story 用于移除失败 Toast）。键名已与 2.3 story 对齐（规划复核 2026-10-18）；T0 仍以 2.3 实际提交为准 grep 核对。
- 复用现有键：`meMyPostsTitle`（Postinganku）、`profilePostsTitle`（Postingan / EN Posts）、`placeDetailTitle`（Tempat / EN Place）、`commonCancel`、`commonRetry`、`mePostTypeMomen` / `mePostTypeCerita` / `mePostTypeTips`（经 `PostGridTile` badge 映射间接使用）、`placeType*`（经 `PlaceTypeLabel.label`）。
- EN 值不得与 ID 相同（`test/l10n/microcopy_rules_test.dart` D-3）；改完跑 `flutter gen-l10n`。

**AC8 — 埋点（AD-17、P-8、PRD §4）** `[L0]`
1. `collection_list_viewed {tab}`：`tab ∈ {post, place}`。**每次进入一个子分类报一次**：点 Disimpan Tab（报当前子分类）、在 Disimpan 内切换分段、经 `?saved=` 落到 Disimpan；重复点当前已选项、页面重建、加载更多**不报**。在点击 / 参数处理处调用，**不在 `build` 里调**。
2. `collection_item_opened {tab, is_unavailable}`：点任一行时报（正常条目在跳转前报；不可见条目在弹抽屉前报，`is_unavailable: true`）。**不带** postId / token / 名称（NFR-5 与 `Analytics._piiKeys`，`analytics.dart`:42）。
3. 事件名字面量写在 `Analytics.capture('…'` 里（守卫按正则提取，`v112_events_test.dart`:164-166）。
4. 命名守卫 `test/analytics/v112_events_test.dart`：`allowedPrefixes`（:217-343）**新增 `'collection_'`**，照该文件惯例写注释（「V1.4.0 ④ 收藏列表（P-8）：模块是「我的收藏列表」这件事，事件名由 PRD §4 定死，扩表、不改名；与 2.3 的 `content_collected` / `place_collected` 刻意分开——那是收藏动作，这里是列表浏览。⚠️ 新模块入表，不是为遗留事件放宽规则」）；词尾 `_viewed` / `_opened` 已在 `allowedSuffixes`（:346-454）；属性 `is_unavailable` 加进 `propNames`（:492-518，`tab` 已在）。**不删守卫、不放宽正则**。
5. `test/analytics/v140_push_collect_events_test.dart`（1.3 已建、2.3 已追加）：往 `pushCollectInventory` 追加 `collection_list_viewed` / `collection_item_opened`。**不改** `v140_events_inventory_test.dart`（Toko 线旧清单），属性名须过其 NFR-5 全局体检（:67-100）。

**AC9 — 回归** `[L0]`：`flutter analyze` 零警告；`flutter test` 全绿。`test/me/me_page_test.dart` 被按设计打破的断言**更新不删**（见 Dev Notes「测试」）；不整文件 `dart format`；不写 golden。L2：Android 模拟器逐屏对照 UI 稿 E1 ~ E9，还原度 ≥ 95%（Hewanku 区以本分支现状为准，不对照 ② 底稿）。

## Tasks / Subtasks

- [x] **T0 开工核对**（对照前面 story 实际提交的代码，有出入先改本文件、Completion Notes 记一句）：
  - 2.2：接口路径与 JSON 字段名（`postId` / `collectedAt` / `available` / `post.{type,excerpt,coverUrl,author.{nickname,avatarUrl}}`（无 `title`）；`placeToken` / `name` / `place.{type,coverUrl,addressText}`；`nextCursor`），`available=false` 时 `post` / `place` 确为 null。
  - 2.3：`features/collect/` 实际结构、仓库类名与 provider 名、取消收藏方法名与返回 / 异常形态（404 是否抛 `DioException`）、帖子 / 场所收藏按钮的成功回调位置、Toast「Lihat」实际跳的 location（预期 `/me?saved=post|place`）、arb 键 `collectSavedToast` / `collectRemoveFailed` 与信号 `collectionsRevisionProvider` 的实际名字。
  - 1.3 / 2.3：`test/analytics/v140_push_collect_events_test.dart` 的实际常量名；`v112_events_test.dart` 是否已有 `'collection_'`（不应有）。
- [x] **T1 数据层**：`ApiPaths` 两条、domain 模型、仓库两个 fetch、两个 controller + provider（AC6.1）；`resetUserScopedCaches` + `_mustReset` 登记（AC6.3）
- [x] **T2 行组件**：`CollectedPostRow` / `CollectedPlaceRow`（正常 + 不可见两态，AC2.2 / AC2.3 / AC3.2 / AC3.3）；`PostGridTile._badgeStyle` 改为公开 `badgeStyle`（见实现提示）；`EmptyState` 加可选 `iconColor`
- [x] **T3 「我的」页**：Tab 行 + `_PostsArea`（Disimpan 分段 / 横条 / 列表 / 空态 / 失败 / 加载更多）、移除抽屉（AC1 ~ AC4）
- [x] **T4 路由与失效**：`_tabRootPage` 传 `saved`、`MePage(saved:)`、清参数与滚动（AC5.1 ~ AC5.4）；列表 provider 订阅 `collectionsRevisionProvider`（AC5.5）
- [x] **T5 埋点**：两事件 + 守卫登记 + V1.4.0 清单（AC8）
- [x] **T6 文案**：arb 两份（AC7）+ `flutter gen-l10n`
- [x] **T7 测试与收尾**：见「测试」；`flutter analyze` + `flutter test`；Completion Notes 标「L2 待本地验收」

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ ba55e1c5）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo_app/lib/features/me/presentation/me_page.dart` | `MePage extends ConsumerWidget`（:34），无构造参数；body 是 `ListView`（:76），**无 `RefreshIndicator`**；顺序 名片（:80）→ 四格（:86）→ Edit Profil（:89）→ `_PetZone`（:91）→ `_ActivitySection`（:95）→ `SizedBox(lg)`（:96）→ `Padding(bottom: sm) + _SectionTitle(l10n.meMyPostsTitle)`（:97-101）→ `_MyPostsList()`（:102）；`_SectionTitle` 12 号 w700 大写 muted（:112-127）；`_MyPostsList` 读 `myPostsProvider`（:503），失败静默 `SizedBox(height: 1)`（:509），空态 `meNoPosts`（:511-519），2 列 `PostGridTile` 网格（:522-540） | `MePage` 加 `saved` 参数；:97-102 换成 `_PostsArea`（Tab 行 + Postinganku = 原 `_MyPostsList` / Disimpan 新内容） | `_PetZone`、`_ActivitySection`、`_MyPostsList` 内容；Hewanku 区归 ②（本分支不改） |
| `lib/features/me/data/my_posts_repository.dart` | `MyPostsRepository.fetch({cursor})`（:44-55，`queryParameters: {'cursor': ?cursor}`）；`myPostsProvider = FutureProvider.autoDispose`（:63-64） | 不改（只照写法） | — |
| `lib/shared/widgets/post_grid_tile.dart` | `static (String, Color, Color) _badgeStyle(type, l10n)`（:44-53，私有）；badge 胶囊 9 号 w700、内边距 6 / 2、圆角 5（:107-118） | `_badgeStyle` → 公开 `badgeStyle`（仅改名 + 文档注释，行为不变；文件内一处调用 :59 同步） | 网格格子外观 |
| `lib/shared/widgets/masonry_card.dart` | 另有一份同名私有 `_badgeStyle`（:105） | **不动**（不顺手合并） | — |
| `lib/features/content/presentation/feed_tab_row.dart` | `FeedTabRow`（:10）绑 `FeedCategory`；私有 `_Tab`（:47-80）胶囊规格 | 不改，照抄规格 | — |
| `lib/features/place/presentation/place_list_page.dart` | 私有 `_PlaceRow`（:626-705，缩略图 56、名称 2 行 w600、类型 · 距离、标签、计数）；私有 `_Thumb`（:707-737，`cream2` 占位 + `lineViolet` 前景描边、圆角 10） | 不改，照抄占位与描边写法 | — |
| `lib/features/place/domain/place_summary.dart` | `PlaceType.fromApi`（:30，未知 → null 不兜底）；文案 `PlaceTypeLabel.label`（`place_labels.dart`:11） | 复用 | — |
| `lib/features/place/presentation/place_detail_page.dart` | `routeFor(token, {from})`（:117）；`from` 白名单 `_knownFrom`（:549-556），未知记 `other` | 不改 | — |
| `lib/shared/widgets/confirm_sheet.dart` | `showConfirmSheet(…, icon, danger, onConfirm, confirmKey, cancelKey)`（:21-55）；`onConfirm` 返回 false 抽屉不关 | 复用 | — |
| `lib/shared/widgets/app_toast.dart` | `showAppToast(context, msg, {top, actionLabel, onAction})`（:35）；`showAppToastOnOverlay`（:46） | 复用 | — |
| `lib/shared/widgets/empty_state.dart` | `EmptyState`（:11）；`iconBackground` 非空时 80 圆底 + 36 图标，**颜色写死 `textTertiary`**（:50-56）；按钮 Key 固定 `emptyStateAction` | 加可选 `Color? iconColor`（默认 null = 原 `textTertiary`，两条图标路径都用它），既有调用零影响 | 默认外观 |
| `lib/shared/widgets/dashed_rect.dart` | `DashedRRectPainter(color, radius, dash, gap, strokeWidth)`（:5） | 复用 | — |
| `lib/core/router/app_router.dart` | `_controlledLocations` 含 `/me`（:158-165）；`_tabRootPage(tab, state)`（:190-196），`AppTab.shop` 读 `?category=`（:193），`AppTab.me => const MePage()`（:195）；shell 分支循环生成（:1218-1229）；`/content/:id`（:1057）、场所详情（:600） | :195 传 `saved` | 其余路由 |
| `lib/core/router/deep_link_routes.dart` | `shellTabRoots = {'/home','/profile','/shop','/me'}`（:24），`isShellTabRoot` 精确匹配（:27） | 不改 | — |
| `lib/features/shop/presentation/toko_page_v2.dart` | `initState` 读 `initialCategory`（:71）、`didUpdateWidget` 比较新旧（:82-83） | 不改，照写法 | — |
| `lib/core/network/api_paths.dart` | `mePosts`（:129）、`contentPosts`（:309）、`places`（:459） | 加两条 | — |
| `lib/app.dart` | `resetUserScopedCaches`（:355-390），`myPostsProvider` 在 :366 | 加两行 invalidate | 其余登记 |
| `test/app/user_scoped_cache_registry_test.dart` | `_mustReset`（:13-33） | 加两项 | — |
| `lib/core/analytics/analytics.dart` | `capture(event, [props])`（:163）、`debugCaptureSink`（:159）、`_piiKeys`（:42） | 不改 | — |
| `test/analytics/v112_events_test.dart` | `allowedPrefixes`（:217-343，无 `collection_`）、`allowedSuffixes`（:346-454，含 `_viewed` / `_opened`）、`propNames`（:492-518，含 `tab`，无 `is_unavailable`） | 加前缀与属性 | 规则本身 |
| `test/analytics/v140_push_collect_events_test.dart` | 1.3 建、2.3 追加的 ④ 线清单 | 追加两条（AC8.5） | 前面 story 的条目；Toko 旧清单不动 |
| `lib/features/user_profile/presentation/public_profile_page.dart` | 他人主页「Postingan」小节 + 网格 + `profilePostsLoadMore`（:648） | **不改** | — |
| `test/me/me_page_test.dart` | `_pump` 只 override `myPostsProvider` / `meCountsProvider` / `petProfileProvider` / `archiveStatsProvider`（:33-44），`home: MePage()`（:53）；AC4.3 用 `find.text(l10n.meMyPostsTitle.toUpperCase())` 定位（:289） | 更新 :289 一处（见「测试」） | 其余用例 |
| `lib/features/collect/` | **不存在**（2.3 新建 `data/` 仓库与详情页按钮） | 补 domain / 列表 controller / 行组件 / Disimpan 内容 | 2.3 的对外方法签名不改 |

### 拍板点（按建议实现，Completion Notes 复述）

- **① 移除用 `showConfirmSheet` 的 `onConfirm` 钩子、失败抽屉不关**：PRD 只写了确认后移除 + Toast，没写失败。钩子模式是仓库既有做法（举报抽屉同款），失败可原地重试；失败 Toast 复用 2.3 的「Gagal membatalkan, coba lagi」，不新增文案。DELETE 返回 404 视同移除成功（收藏行已经没了，留着这一行只会让用户点了又报错）。
- **② 不加下拉刷新**：「我的」页现在没有 `RefreshIndicator`，加了会作用于整页（名片、四格、Hewanku），超出本 story 范围；列表新鲜度靠 autoDispose（切走再切回重拉）+ 收藏 / 取消后失效 + 失败重试。
- **③ 场所详情不带 `from`**：`place_detail_viewed` 会记成 `other`；收藏列表的入口归因由 `collection_item_opened` 承载。不为此改场所详情页的 `from` 白名单（2.3 也在改该文件，减少冲突面）。
- **④ `?saved=` 消费后清掉、并滚到 Tab 行**：见 AC5.3 / AC5.4 的理由；PRD ⑥ 只写「跳到并选中对应子分类」，滚动与清参数是让它稳定成立的最小做法。
- **⑤ 可见条目不提供移除**：PRD 只给不可见条目定义了移除；可见条目到详情页取消收藏（2.3）。
- **⑥ 两处共享组件做最小扩展**：`PostGridTile._badgeStyle` 公开（避免第三份配色拷贝，`masonry_card.dart` 已有一份私有副本就是前车之鉴）；`EmptyState` 加 `iconColor`（E5 / E8 书签图标是紫色，现组件写死灰色）。两者默认行为逐字节不变。

### 实现提示

- Tab 行布局：`Container(decoration: BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.line2))))` 里一个 `Row`，每项 `InkWell` → `Container(padding: EdgeInsets.only(top: 8, bottom: 9), decoration: 选中时 Border(bottom: BorderSide(color: mint, width: 2.5)))`；用 `Transform.translate(offset: Offset(0, 1))` 或负 margin 让下划线压在分隔线上（UI 稿 `margin-bottom:-1px`），做不到像素级可接受，L2 对照即可。
- 不可见场所缩略图：只画占位块（见 AC1 不可见场所），不发图片请求。
- 移除流程：在 `await showConfirmSheet` 之前先取 `final overlay = Overlay.of(context, rootOverlay: true)`，成功后用 `showAppToastOnOverlay(overlay, l10n.collectRemovedToast)`（`app_toast.dart` 头注释的 await 后用法）。成功后调 controller `removeLocal`；**不要**为移除去 invalidate 整个列表（会回顶、丢已加载的后续页）。
- 列表是 `ListView` 子项里的 `Column`（不嵌套可滚动），行数随页增长；不用 `ListView.builder` 嵌套（外层已是 `ListView`，嵌套需 `shrinkWrap`，与现 `_MyPostsList` 的 `GridView.count(shrinkWrap, NeverScrollable)` 同口径即可）。
- 首次切到 Disimpan 时 `ref.watch(当前子分类 provider)`；另一个子分类不 watch（不预取）。
- 埋点调用放在 `onTap` / 分段切换回调 / `saved` 处理函数里，属性只用受控字面量：`{'tab': 'post'}`、`{'tab': 'place', 'is_unavailable': true}`。
- 作者昵称用接口值直接显示（它是 UGC，不走 arb）；类型标按 `post.type` 线格式走 `PostGridTile.badgeStyle`，未知类型落 `default` 分支（与网格一致）。
- `collectedAt` 解析但本 story 不展示（UI 稿行内无时间）。

### 架构约束

- AD-11：列表契约以 2.2 为准；不可见帖子接口不给标题 / 作者 / 封面，App 也**不得**从其他缓存（Feed、详情）补齐这些信息。
- AD-16：只改 `me_page.dart` 的 Postinganku 小节；他人主页不改；两个列表 provider 登记 `resetUserScopedCaches`；收藏 / 取消成功后失效对应列表。
- AD-17 / P-8：事件名保留 PRD 原名，守卫登记前缀并注释，不改名、不放宽正则。
- P-5 / P-7：可见性与是否发取消事件都由后端判断，App 不做任何判断分支。
- NFR2：收藏只本人可见——不在任何对外页面（他人主页、分享卡）露出收藏列表。
- 护栏：不引新依赖（虚线用 `DashedRRectPainter`，不可见场所用占位块）；对外标识帖子沿用 `/content/{id}`、场所用 token。

### 与前后 story 的边界（本 story 不做）

- 不做收藏 / 取消按钮、收藏 Toast 与「Lihat」跳转本身（2.3）；本 story 不改 2.3 的按钮代码，只订阅其变更信号。
- 不改后端；列表接口、移除不发事件的判断在 2.1 / 2.2。
- 不改 Postinganku 的加载失败静默、不给 Postinganku 加分页（现状只拉首批）。
- 不做收藏夹分组、搜索、「谁收藏了我」（PRD 不做项）。
- 不碰 Hewanku 区（② 多宠物在并行分支改版）、不加等级胶囊（①）；UI 稿 E 组底稿里的「Kelola」与多宠头像排是 ② 定稿，**本分支不画**。三者合并、整页还原度对照在 3.1 对齐分支。
- `DeepLinkRoutes.isShellTabRoot('/me?saved=…')` 为 false（精确匹配）：只影响推送 / 冷启动深链的 push 判定；本 story 的落点只经 2.3 的 `context.go`，**不改** `deep_link_routes.dart`。

### 测试

**验证层级**
- **L0（云端可跑）**：
  - `test/collect/collected_item_test.dart`：两种 `fromJson`——可见帖子字段齐全；`available=false` 且 `post=null`；场所 `place=null` 时类型 / 封面 / 地址为 null 而 `name` 仍在；缺 `available` 视为 false。
  - `test/collect/collection_list_controller_test.dart`（fake 仓库）：首页加载；`loadMore` 追加且用 `nextCursor`；末页 `nextCursor=null` → `hasMore=false` 且 `loadMore` 不再请求；`loadMore` 失败保留已加载 + `loadMoreFailed=true`、再次 `loadMore` 成功清标记；`removeLocal` 只删目标行、不触发请求。
  - `test/collect/collected_rows_test.dart`：帖子行可见态显示正文摘要、作者昵称、类型标（三种 type 文案 / 颜色与 `PostGridTile.badgeStyle` 一致）、无封面画占位；不可见态**找不到**原摘要与作者文本、显示「Konten tidak tersedia lagi」+「Ketuk untuk menghapus」+ `visibility_off` 图标；`available=true` 但 `post=null` 按不可见渲染不崩。场所行可见态显示名称 / 类型 / 地址、未知类型省略类型行；不可见态名称仍在、缩略图为占位块（无图片请求）与「Tempat tidak tersedia lagi」。
  - `test/me/me_page_saved_tab_test.dart`（`_pump` 照 `me_page_test.dart`，再 override 两个列表 provider 的仓库为 fake）：
    - 默认选中 Postinganku、`meNoPosts` / 网格照旧；**fake 仓库两个 fetch 调用次数为 0**。
    - 点 `meTabSaved` → 出现 `savedSegPost` / `savedSegPlace` / `savedPrivacyHint`、帖子列表按 fake 顺序渲染（客户端不重排）；点 `savedSegPlace` → 场所列表；再点 `meTabMyPosts` → 回到网格。
    - 空态：两子分类各自文案、**无 `emptyStateAction`**；失败态：`savedError` 出现、`savedPrivacyHint` 不出现、`meEditNickname` 与 Tab 行仍在，点重试后 fake 第二次返回数据 → 列表出现。
    - Postinganku 失败仍静默：`myPostsProvider` 抛错时不出现任何错误文案（回归）。
    - 加载更多：有 `nextCursor` 时出现 `savedLoadMore`，点击后追加；失败出现 `savedLoadMoreRetry`、已加载行仍在。
    - 移除：点不可见帖子 → 抽屉标题 / 正文 / 按钮文案正确；点 `savedRemoveConfirm` → fake 取消收藏被以该 `postId` 调用一次、该行消失、出现「Dihapus dari Disimpan」；fake 抛网络错 → 抽屉仍在、出现「Gagal membatalkan, coba lagi」、行仍在；fake 抛 404 → 按成功处理；点 `savedRemoveCancel` → 不调接口。场所同测一遍（按 `placeToken`）。
    - 点正常帖子 / 场所 → 用测试 `GoRouter`（含 `/content/:id`、`/places/:token` 两条桩路由）断言落到 `/content/{id}` / `/places/{token}`。
    - 埋点（`Analytics.debugCaptureSink`）：点 Disimpan 报一次 `collection_list_viewed{tab: post}`；切 Tempat 报 `{tab: place}`；重复点已选分段不再报；页面 `pump` 重建不报；点正常行报 `collection_item_opened{tab: post, is_unavailable: false}`、点不可见行报 `is_unavailable: true`；属性集合恰为这两 / 一个键。
  - `test/me/me_saved_query_param_test.dart`：`MaterialApp.router` + 最小 `GoRouter`（`/me` builder 同 `_tabRootPage` 写法：`MePage(saved: s.uri.queryParameters['saved'])`，再加 `/home` 桩）：`go('/me?saved=place')` → Disimpan + Tempat 选中、`collection_list_viewed{tab: place}` 报一次；随后路由 location 变回 `/me`（参数被清）；手动点回 Postinganku 后再 `go('/me?saved=post')` → 又切到 Disimpan + Postingan（AC5.6 的回归点）；`saved=foo` 不改选中态；`MaterialApp(home: MePage(saved: 'post'))`（无 GoRouter）不抛。另加一条源码扫描：`app_router.dart` 含 `AppTab.me => MePage(saved: state.uri.queryParameters['saved'])`（防有人改回 `const MePage()`）。
  - `test/user_profile/public_profile_self_view_test.dart`（补一条）：他人主页 / 自看主页都找不到 `meTabSaved`（AC8a）。
  - 共享组件回归：`test/shared/` 下补 `EmptyState` 不传 `iconColor` 时图标仍为 `textTertiary`、传了用传入色；`PostGridTile` 现有测试不变绿。
  - 守卫：`test/app/user_scoped_cache_registry_test.dart`（两项新登记）、`test/analytics/v112_events_test.dart`、`test/analytics/v140_push_collect_events_test.dart`、`test/analytics/v140_events_inventory_test.dart`（NFR-5 体检）、`test/l10n/microcopy_rules_test.dart` 全绿。
  - **会被按设计打破、需更新的既有断言**：`test/me/me_page_test.dart`:289 `find.text(l10n.meMyPostsTitle.toUpperCase())` —— Tab 标签不再大写，改为 `find.byKey(const ValueKey('meTabMyPosts'))`，用例名与「Edit Profile → Hewanku → Aktivitas → Postinganku」顺序语义不变。其余用例（:62 的 `meNoPosts`、:128 的网格顺序）因默认停在 Postinganku 应不受影响。
- **L1**：无（纯 App）。
- **L2（本地）**：Android 模拟器连后端（含 2.1 / 2.2）演示：详情收藏 → Toast「Lihat」→ Disimpan 最上方；场所同；让一条收藏帖子被删除 / 作者拉黑我 → 不可见行 → 移除；场所下架 → 灰显 → 恢复后正常；空态 / 断网失败态；对照 UI 稿 E1 ~ E9 还原度 ≥ 95%（Hewanku 区不计入，归 3.1）。
- 云端只跑 L0；Completion Notes 标「L2 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-push-collect.md#Story 2.4]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-push-collect-delta.md#AD-11、AD-15、AD-16、AD-17、P-5 ~ P-8、§0.3 第 9 条]
- [Source: _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/1-4-0推送开关与收藏prd.md#3.2 收藏（我的收藏 / 不可见的内容 / AC7 ~ AC9a / AC11c）、§4 埋点、§6.5 ⑤⑥⑦]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#④ 推送开关与收藏（Q3 不可见条目点开弹底部抽屉移除、Q4 收藏 Toast 指向「我的」、第二步告知项：收藏用列表行 / 不可见帖子不露原标题作者 / Postinganku 失败静默不改）]
- [Source: _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/ui-1-4-0-push-collect-integrated-v1.html#E1 ~ E9]
- [Source: _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md#2026-10-18 V1.4.0 四条线共用地基]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/cloud-rules-push-collect.md]

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，bmad-dev-story 流程）

### Debug Log References

- `flutter analyze` 零警告；`flutter gen-l10n` 已跑。
- 新增 / 改动相关测试全绿；全量 `flutter test` 3495 例全绿（1 skipped 为存量）。
- 未整文件 `dart format`。

### Completion Notes List

- **🔴 L2 待本地验收（还原度 ≥ 95%）**：Android 模拟器连后端（含 2.1 / 2.2）：详情收藏 → Toast「Lihat」→ Disimpan 最上方；场所同；收藏帖子被删除 / 作者拉黑我 → 不可见行 → 移除；场所下架 → 灰显 → 恢复后正常；空态 / 断网失败态；逐屏对照 UI 稿 E1 ~ E9（Hewanku 区不计入，归 3.1）。本 story 无 L1。
- T0 核对：2.2 JSON 字段与 story 一致（帖子无 `title`、不可见时 `post` / `place` 为 null、末页无 `nextCursor`）；2.3 实际名字一致（`CollectRepository.uncollectPost/uncollectPlace`、`collectionsRevisionProvider`、`CollectTarget.fromSavedParam`、arb `collectSavedToast` / `collectRemoveFailed`、清单常量 `pushCollectInventory`）；`v112_events_test.dart` 原无 `collection_`。**名字微调**：story 写的行组件 `CollectedPostRow` / `CollectedPlaceRow` 与领域模型同名易混，实际组件名 `CollectedPostRowView` / `CollectedPlaceRowView`（Key 仍按 story：`collectedPostRow_{postId}` / `collectedPlaceRow_{placeToken}`）；分页结果类型为 typedef `CollectionPageData<T>`。
- 拍板点复述：① 移除用 `showConfirmSheet(onConfirm)`，失败抽屉不关可重试，404 视同成功；② 不加下拉刷新；③ 场所详情不带 `from`；④ `?saved=` 消费后 `go('/me')` 清参数并滚到 Tab 行；⑤ 可见条目不提供移除；⑥ `PostGridTile.badgeStyle` 公开（仅改名）、`EmptyState` 加可选 `iconColor`（默认外观不变，有回归测试）。
- 两个列表 provider 为 `AsyncNotifierProvider.autoDispose` + `retry: (_, _) => null`（同 1.3：Riverpod 3 默认自动重试会让失败态停在加载中），已登记 `resetUserScopedCaches` 与 `_mustReset`；默认停在 Postinganku 时零请求（测试钉住）。
- 既有断言更新：`test/me/me_page_test.dart` AC4.3 的 Postinganku 定位由大写文本改为 `ValueKey('meTabMyPosts')`，顺序语义不变。
- **code-review 修复（CONFIRMED）**：① `loadMore` 在途时若列表被信号重拉 / 重试（换代）则丢弃结果，否则旧页会拼回新首页（重复 Key）；② `loadMore` 回来后在**最新**状态上追加，避免在途期间本地移除的行被拼回；③ `_PostsArea` 加 `AutomaticKeepAliveClientMixin`——「我的」页 body 是懒构建 `ListView`，滚出缓存范围会回收、选中的 Disimpan / 子分类丢失；④ 信号重拉 / 重试期间保留已加载内容（不塌成转圈），只有从没拿到数据时才转圈 / 报错；⑤ 单行坏数据（缺 `postId` / 空 `placeToken`）只跳过该行，不让整页失败；⑥ 移除成功回调前检查 `context.mounted`。
- code-review 未采纳：Tab / 分段复用既有文案键（story AC7 明定复用 `collectSavedToast` / `profilePostsTitle` / `placeDetailTitle`）；`_PostsList` / `_PlacesList` 合并为泛型组件（重构超出范围）；404 分支保留（AC3.4 明定，注释已写明是防御）。

### File List

- `petgo_app/lib/features/collect/domain/collected_item.dart`（新）
- `petgo_app/lib/features/collect/presentation/collection_list_controller.dart`（新）
- `petgo_app/lib/features/collect/presentation/collected_rows.dart`（新）
- `petgo_app/lib/features/collect/presentation/saved_collections_section.dart`（新）
- `petgo_app/lib/features/collect/data/collect_repository.dart`（改：`fetchCollectedPosts` / `fetchCollectedPlaces`）
- `petgo_app/lib/core/network/api_paths.dart`（改：`meCollectionPosts` / `meCollectionPlaces`）
- `petgo_app/lib/features/me/presentation/me_page.dart`（改：`MePage(saved:)`、`_PostsArea` Tab 行 + Disimpan）
- `petgo_app/lib/core/router/app_router.dart`（改：me Tab 读 `?saved=`）
- `petgo_app/lib/app.dart`（改：`resetUserScopedCaches` 登记两个列表 provider）
- `petgo_app/lib/shared/widgets/post_grid_tile.dart`（改：`badgeStyle` 公开）
- `petgo_app/lib/shared/widgets/empty_state.dart`（改：可选 `iconColor`）
- `petgo_app/lib/l10n/app_en.arb` / `app_id.arb`（改：15 个 `collect*` 键）
- 测试（新）：`test/collect/collect_fakes.dart`、`test/collect/collected_item_test.dart`、`test/collect/collection_list_controller_test.dart`、`test/collect/collected_rows_test.dart`、`test/me/me_page_saved_tab_test.dart`、`test/me/me_saved_query_param_test.dart`、`test/shared/empty_state_icon_color_test.dart`
- 测试（改）：`test/me/me_page_test.dart`（AC4.3 定位）、`test/app/user_scoped_cache_registry_test.dart`（两项）、`test/analytics/v112_events_test.dart`（前缀 `collection_` + 属性 `is_unavailable`）、`test/analytics/v140_push_collect_events_test.dart`（追加两条）

### Change Log

- 2026-10-18：实现 Story 2.4（「我的」页 Postinganku / Disimpan Tab、收藏列表、不可见条目移除、`?saved=` 落点、埋点）；code-review 后修 6 项。状态 → review。
