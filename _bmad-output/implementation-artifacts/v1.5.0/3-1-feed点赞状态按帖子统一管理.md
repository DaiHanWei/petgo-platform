# Story 3.1: Feed 点赞状态按帖子统一管理（App）

Status: review

> 版本 v1.5.0 · 主题 likes（会话 ⑤）· 分支 `feat/1.5.0-likes`
> 口径优先级：`decision-log.md` > PRD B `Feed双击点赞/1-5-0Feed双击点赞prd.md`（FR-210，AC 记作 B-AC*n*）> `architecture-v1.5.0-likes-delta.md`（F-1 ~ F-3、待拍板 Q-6）> UI 稿 `ui-1-5-0-feed-double-tap-like-integrated-v1.html`（C1 ~ C3）。
> 前置：无（与 Epic 1–2 无依赖；按会话顺序在 Epic 2 之后做）。
> 范围层：**只 App**（`petgo_app/`），后端与接口不动。
> 🔴 PRD B「改造顺序」：本 story 做完、`like_button` 现有测试**不改预期**全绿之后，才开始 4.1。
> 🔴 云端只跑 L0；模拟器 / 真机验收（L2）待本地。

## Story

As a 用户,
I want 同一条帖子不管在顶置卡、「发现」「关注」、话题页还是详情页，看到的点赞状态都一样，切页签也不回退,
so that 点过的赞不会「消失」，也为图片双击点赞打好基础。

## Acceptance Criteria

**AC1 — 点赞状态 store** `[L0]`（FR-210、F-1）
新建 `lib/features/content/domain/post_like_state.dart`：
- `PostLikeOverride { bool liked; int count; bool inFlight; int epoch; int bounceTick; }`（`bounceTick` 供 4.1 / 4.2 的按钮弹跳，本 story 先放字段、恒 0 递增逻辑见 AC3）。
- 全局 `_likeEpochCounter` 放在 notifier 外（登录态重建不归零，同 `features/social/domain/follow_state.dart:114-129` 的做法）。
- `class PostLikeStore extends Notifier<Map<int, PostLikeOverride>>`：
  - `int get currentEpoch`；
  - `LikeView effective(int postId, {required bool snapLiked, required int snapCount, required int snapEpoch})`：覆盖存在且（`inFlight` 或 `epoch > snapEpoch`）→ 用覆盖；否则用快照；
  - `Future<LikeToggleOutcome> toggle(int postId, {required LikeView current, required bool wantLiked, required LikeTracking tracking})`：`current.inFlight` → 返回 `ignoredInFlight`（不发请求、不报事件）；`current.liked == wantLiked` → `ignoredNoChange`；否则：报埋点（AC4）→ 写乐观覆盖（`liked = wantLiked`、`count ± 1`、`inFlight = true`、新 epoch）→ 调 `likeRepositoryProvider` 的 `like` / `unlike` → 成功写后端真值（`inFlight = false`、新 epoch）并 `syncFeedCounts(...)`（AC5）→ 返回 `settled(liked, count)`；失败写回 `current` 的 liked / count（`inFlight = false`、新 epoch）→ 返回 `failed`。
- `final postLikeStoreProvider = NotifierProvider<PostLikeStore, Map<int, PostLikeOverride>>(PostLikeStore.new);`
- **登录门控不在 store 里**（需要 `BuildContext` 弹窗），由调用的 Widget 先 `requireLogin` 再调 `toggle`。

**AC2 — 快照点赞版本号** `[L0]`（F-2）
- `FeedItem` 加 `final int likeEpoch;`（默认 0，不来自 JSON），`copyWith` 支持；`ContentDetail` 同样加 `likeEpoch` 与 `withLikeEpoch`；`PinnedSlot` 加 `withLikeEpoch`（或把现有 `withFollowEpoch` 扩成同时带两个戳——二选一，全仓统一一种）。
- 在**每个**给帖子快照打 `followEpoch` 的地方，紧挨着打 `likeEpoch = ref.read(postLikeStoreProvider.notifier).currentEpoch`（发请求前读）：
  - `features/content/presentation/feed_controller.dart:99`（首屏）、`:128`（自己的新帖插入）、`:146-157`（翻页）、`:267-268`（`pinnedSlotProvider`）；
  - `features/content/presentation/following_feed_controller.dart:19-22`、`:42-48`；
  - `features/topic/presentation/topic_providers.dart:25-26`（`topicPinnedProvider`）、`:109-121`、`:142`（`topicPostsProvider` 首屏 / 翻页）；
  - `features/content/presentation/detail_providers.dart:18`。
- 开工时 `grep -rn "followEpoch: epoch\|withFollowEpoch" lib/features/content lib/features/topic` 核对清单，不漏、不多（`post_likers_controller.dart`、social / profile 下的打戳与帖子无关，不改）。

**AC3 — `LikeButton` 改造** `[L0]`（F-3、B-AC12）
`lib/features/content/presentation/like_button.dart`：
- 构造参数保持不变，新增可选 `final int snapshotEpoch;`（默认 0）。
- 删掉 State 里的 `_liked / _count / _inFlight` 与 `didUpdateWidget` 跟随逻辑，改为 `ref.watch(postLikeStoreProvider.select((m) => m[widget.postId]))` + `effective(...)` 计算显示值。
- `_toggle`：`if (!requireLogin(ref, context, onAllowed: () {})) return;`（entrySource 保持默认 `other`，与现状一致）→ `store.toggle(..., wantLiked: !view.liked, tracking: LikeTracking(source, feedTab, rankMode, reason, trigger: 'button'))` → `settled` 时调 `widget.onSettled?.call(liked, count)`（保持向后兼容）。
- `_openLikers` 用 `effective` 的 `count` 判断 `<= 0`。
- 外观、热区、key（`likeButton_<id>` / `likeCount_<id>`）、`expandCountHitArea`、`likersSource` 一律不变。
- 顶部注释「直接复用、不得重写」改为：「⚠️ V1.5.0 FR-210 起状态已外提到 `postLikeStoreProvider`（`post_like_state.dart`）；本组件只负责外观、登录门控与触发。改点赞逻辑去改 store，不要在这里重新存状态。」
- 现有 `test/content/like_button_test.dart`、`like_button_split_test.dart`、`like_button_reason_test.dart`、`feed_action_row_test.dart`、`comment_actions_report_like_test.dart` **断言不改**；只允许为构造方式补 `ProviderScope` / provider override（Completion Notes 逐个说明改了哪几行、为什么不影响预期）。

**AC4 — 埋点不变 + `trigger`** `[L0]`（PRD B §4）
`post_like_tapped` 事件名不变，属性在原有 `liked / source / feed_tab / rank_mode / reason` 基础上加 `trigger`（本 story 只有 `button`）。只在 `toggle` 真正发起请求时报（飞行中、已是目标态都不报）。`test/analytics/v112_events_test.dart`、`v116_events_test.dart`、`v116_feed_rank_attribution_test.dart` 照常通过（若有「属性白名单」断言，按设计把 `trigger` 加进去）。

**AC5 — 写回** `[L0]`（FR-210、F-2）
- store 落定（成功）时调 `syncFeedCounts(ref, postId, liked:, likeCount:)` —— 原来只有详情页调；store 统一调后，详情页 `comment_composer.dart:470` 的 `onSettled: syncFeedCounts(...)` 删除（否则写两次；其行为由 store 覆盖），或保留但无害——二选一，Completion Notes 说明。
- `syncFeedCounts` 的签名改为接收 `Ref`（store 里没有 `WidgetRef`）或在 store 内用 `ref.exists` + `ref.read` 直接写两条流——实现自选，保持「没建过的流不为写回而创建」（`ref.exists` 先判）。
- `feed_controller.dart:233` 注释「顶置卡（pinnedSlotProvider）是独立快照，不在此回写」改为「顶置卡与话题页靠 `postLikeStoreProvider` 覆盖同步（V1.5.0 FR-210）」。
- `MasonryCard` 给 `LikeButton` 传 `snapshotEpoch: item.likeEpoch`；详情底栏传 `detail.likeEpoch`。

**AC6 — 换账号清空** `[L0]`
`lib/app.dart` `resetUserScopedCaches`（`:382` 起）加 `ref.invalidate(postLikeStoreProvider);` 并在注释说明「已赞态是账号维度」。登出（→ guest）不清（与该函数现有约定一致）。

**AC7 — 同步行为** `[L0]` `[L2]`（B-AC11、AC13 ~ AC17）
L0 用 `ProviderContainer` + 假 `likeRepositoryProvider` 写测试 `test/content/post_like_state_test.dart`：
1. 顶置卡与普通卡同一帖：点任一 → 两个 `effective` 同时变（B-AC11）。
2. 「发现」点赞 → 重建「发现」（快照 epoch 是旧的）→ 仍是已赞、计数正确（B-AC15）；「关注」流同一帖读到已赞（B-AC16）。
3. 话题页（`topicPostsProvider` 快照）点赞 → 首页同帖已赞；反之亦然（B-AC17）。
4. 详情页点赞 / 取消 → Feed 卡同步（B-AC14）。
5. 下拉刷新（新快照 epoch > 覆盖 epoch）→ 以新快照为准，不闪回；飞行中刷新 → 仍显示乐观值，落定后按后端（B-AC13）。
6. 失败回滚到点击前的有效值；飞行中再点不发第二次请求。
7. 换账号后 store 为空。
L2：模拟器按 PRD §2.1 三个复现路径实测（切页签回退、顶置 / 普通卡、话题页 ↔ 首页）。

## Tasks / Subtasks

- [x] T0 开工核对：`like_button.dart` / `feed_controller.dart` / `topic_providers.dart` / `detail_providers.dart` 行号；打戳点清单
- [x] T1 `post_like_state.dart`（AC1）
- [x] T2 快照 `likeEpoch` 字段与打戳（AC2）
- [x] T3 `LikeButton` 改造 + 注释（AC3、AC4）
- [x] T4 写回与详情页（AC5）
- [x] T5 `resetUserScopedCaches`（AC6）
- [x] T6 测试（AC7）+ L0；Completion Notes 标「L2 待本地」

## Dev Notes

### 现状（2026-11-14 核 `dev_1.5.0`）

| 位置 | 现状 |
|---|---|
| `like_button.dart:91-170` | State 存 `_liked/_count/_inFlight`；`didUpdateWidget` 不飞行时跟随外部值；`_toggle`：飞行中 return → `requireLogin` → 埋点 → 乐观翻转 → 调仓库 → 真值校正 + `onSettled` → 失败回滚 |
| `masonry_card.dart:367-379` | `LikeButton(postId, initialLiked: item.liked, initialCount: item.likeCount, source: 'feed', likersSource: 'feed_card', feedTab, rankMode, reason: feedTab == 'following' ? item.reason : null)` |
| `comment_composer.dart:460-471` | 详情底栏 `LikeButton(source: 'detail', likersSource: 'content_detail', expandCountHitArea: false, onSettled: syncFeedCounts)` |
| `feed_controller.dart:195-216` `syncCounts` / `:235-246` `syncFeedCounts` | 只写 `feedProvider` / `followingFeedProvider`，`ref.exists` 先判 |
| `feed_controller.dart:263` `pinnedSlotProvider` | `FutureProvider<PinnedSlot?>`，失败吞成 null |
| `topic_providers.dart:21` `topicPinnedProvider` / `:184` `topicPostsProvider` | `FutureProvider.autoDispose.family` / `AsyncNotifierProvider.autoDispose.family<…, TopicPostsKey>` |
| `features/social/domain/follow_state.dart` | 覆盖 + epoch 的范本：`FollowOverride(epoch)`、`_followEpochCounter` 放 notifier 外、`currentEpoch` |
| `features/content/presentation/feed_view.dart:454 / :516` | 顶置卡与普通卡都是 `MasonryCard`（同一帖可同屏两张） |

### 为什么不逐个写回顶置 / 话题列表
`pinnedSlotProvider` / `topicPinnedProvider` 是只读 `FutureProvider`；`topicPostsProvider` 是 autoDispose family，外面拿不到全部 key。关注态（AD-7）在同一个问题上已经用「覆盖 + 快照版本号」，这里照抄同一套（待拍板 Q-6，默认按此）。

### 测试与命令
- 新增：`test/content/post_like_state_test.dart`（AC7）。
- 回归：上面 AC3 列的 5 个测试文件 + `test/analytics/*` + `test/content/pinned_slot_test.dart`。
- L0：`cd petgo_app && flutter analyze && flutter test test/content test/analytics`；最后跑一次全量 `flutter test`。
- **不对整文件跑 `dart format`**（存量未统一格式化）；手工对齐。

## Dev Agent Record

### Completion Notes
- **L1/L2 待本地验收**：AC7 的 L2（模拟器按 PRD §2.1 三个复现路径：切页签回退、顶置 / 普通卡、话题页 ↔ 首页）云端未做。本 story 无 L1。
- **L0**：`flutter test` 全量 4956 通过（含 4.1 预先写好的判定器单测 8 例，不影响本 story）；`flutter analyze` 0 error / 0 warning。
  - ⚠️ 云端 SDK 是 **Flutter 3.50 / Dart 3.14**（项目基线 3.44），提交的 `pubspec.lock` 在该 SDK 下 `--enforce-lockfile` 无法解析，只能用 session 启动时 `pub get` 重新解析出的依赖（riverpod 3.3.2 → 3.5.0 等）跑；**`pubspec.lock` 与 macOS / Windows 插件注册生成文件未提交**。基线 analyze 有 799 条 info（798 条是 3.50 对 `package:flutter/material.dart` 的弃用），本 story 只新增 3 条同类弃用 info：riverpod 3.5 对 `Ref.exists` 的弃用（`syncFeedCountsFromStore` 与测试里各用一次，与存量 `syncFeedCounts` 写法一致；3.3.2 下不存在替代 API）。本地 3.44 + 提交的 lock 下请再跑一次 analyze / test。
- T0 核对：打戳点清单与 story 一致（`feed_controller` 首屏 / 自己的新帖 / 翻页 / 顶置、`following_feed_controller` 首屏 / 翻页、`topic_providers` 顶置 / 首屏 / 翻页、`detail_providers`）；`post_likers_controller` 等与帖子无关的未改。行号与 story 有个位数漂移，未改 story 正文。
- 实现选择：
  - 快照版本号：`FeedItem.likeEpoch` + `copyWith(likeEpoch:)`；`ContentDetail.withLikeEpoch`、`PinnedSlot.withLikeEpoch`（与 `withFollowEpoch` 链式调用，全仓统一这一种）。
  - 详情的 `likeEpoch` 取**本次取数**那一刻（不是进入时）：评论后重拉带回的是后端新值。
  - 写回（AC5）：store 落定时调新函数 `syncFeedCountsFromStore(Ref, …)`（`ref.exists` 先判）；详情底栏 `onSettled: syncFeedCounts(...)` **删除**（否则写两次），`comment_composer.dart` 不再 import `feed_controller.dart`。`LikeButton.onSettled` 参数保留（向后兼容）。
  - 复审后加：`PostLikeStore.adoptFresherSnapshot`——详情页拉到比覆盖新的快照时采纳为覆盖（`content_detail_page` 的回写监听里调用），否则顶置卡 / 话题页 / Feed 卡会继续显示更旧的覆盖值；`toggle` 同时检查 store 自身的飞行中状态；store 被换账号 invalidate 后在途请求返回 `failed`（不回调、不写回）。
  - store **不** watch 登录态（登出不清），只在 `resetUserScopedCaches` 里 `ref.invalidate(postLikeStoreProvider)`（AC6）；版本号计数器在 notifier 外，不归零。
- **测试改动（断言均未改，逐个说明）**：
  - `like_button_test.dart` / `like_button_split_test.dart` / `like_button_reason_test.dart` / `feed_action_row_test.dart` / `comment_actions_report_like_test.dart`：**未改一行**，全绿。
  - `test/content/masonry_card_topic_tap_test.dart`、`test/mention/mention_tap_priority_test.dart`、`test/content/feed_test.dart`（「MasonryCard 注销作者…」一例）：只把 `pumpWidget(MaterialApp(...))` 包进 `ProviderScope(child: ...)`——卡片里的 `LikeButton` 现在 build 时读 store，裸 pump 会报 `No ProviderScope found`；断言与被测行为不变。
  - `test/analytics/v116_feed_rank_attribution_test.dart`：两条精确比对属性表的断言按 AC4 设计加 `'trigger': 'button'`（事件名与其余属性不变）。
  - 新增 `test/content/post_like_state_test.dart`（AC7 第 1–7 条 + 已是目标态 / 埋点属性 / 写回不建流 / 采纳更新快照）。
- **待确认**：失败回滚按 AC1 原文写「点击前的值 + 新 epoch」。复审指出边界情况：请求飞行中用户下拉刷新、随后请求失败 → 回滚覆盖的 epoch 比刷新快照新，刷新后的卡会显示点击前的旧值直到下次刷新。若要改成「恢复点击前的覆盖（含原 epoch）/ 无则删除」需主会话拍板（架构 F-1 原文担心删除会让别处退回更旧快照）。
- 分层说明：`content/domain/post_like_state.dart` 引用 `presentation/feed_controller.dart` 的写回函数（F-1 指定 store 落定时写回两条流），与 `feed_controller` 互相 import；Dart 允许，功能无影响。

### File List
- `petgo_app/lib/features/content/domain/post_like_state.dart`（新）
- `petgo_app/lib/features/content/domain/feed_item.dart`
- `petgo_app/lib/features/content/domain/content_detail.dart`
- `petgo_app/lib/features/content/domain/pinned_slot.dart`
- `petgo_app/lib/features/content/presentation/like_button.dart`
- `petgo_app/lib/features/content/presentation/feed_controller.dart`
- `petgo_app/lib/features/content/presentation/following_feed_controller.dart`
- `petgo_app/lib/features/content/presentation/detail_providers.dart`
- `petgo_app/lib/features/content/presentation/comment_composer.dart`
- `petgo_app/lib/features/content/presentation/content_detail_page.dart`
- `petgo_app/lib/features/topic/presentation/topic_providers.dart`
- `petgo_app/lib/shared/widgets/masonry_card.dart`
- `petgo_app/lib/app.dart`
- `petgo_app/test/content/post_like_state_test.dart`（新）
- `petgo_app/test/analytics/v116_feed_rank_attribution_test.dart`
- `petgo_app/test/content/masonry_card_topic_tap_test.dart`
- `petgo_app/test/mention/mention_tap_priority_test.dart`
- `petgo_app/test/content/feed_test.dart`
