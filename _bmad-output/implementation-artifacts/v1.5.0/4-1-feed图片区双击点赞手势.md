# Story 4.1: Feed 图片区双击点赞手势（App）

Status: review

> 版本 v1.5.0 · 主题 likes（会话 ⑤）· 分支 `feat/1.5.0-likes`
> 口径优先级：`decision-log.md` > PRD B `Feed双击点赞/1-5-0Feed双击点赞prd.md`（FR-209、§4 埋点）> `architecture-v1.5.0-likes-delta.md`（F-4、F-5、F-9、待拍板 Q-9）> UI 稿 `ui-1-5-0-feed-double-tap-like-integrated-v1.html` 与 `拆页-App/`（A1 ~ A4、B1 ~ B4、C1 ~ C3、D2）。
> 前置：**Story 3.1**（`postLikeStoreProvider`，`like_button` 旧测试全绿）。
> 范围层：只 App。动效（大心）在 4.2；本 story 只留一个「播动效」回调钩子，先接空实现。
> 🔴 云端只跑 L0；真机手感与读屏（L2）待本地。

## Story

As a 刷 Feed 的用户,
I want 在帖子图片上双击就点赞，单击仍然进详情,
so that 不用去找卡片下方的小爱心。

## Acceptance Criteria

**AC1 — 判定器 `FeedTapArbiter`** `[L0]`（FR-209「单击和双击的判断」、F-4）
新建 `lib/shared/media/feed_tap_arbiter.dart`（纯 Dart，可单测，**不改** `lightbox_gestures.dart` 与 `image_lightbox.dart` 的行为）：
```dart
/// Feed 图片区单击 / 双击仲裁（V1.5.0 FR-209）。窗口复用大图页的 [kLightboxSingleTapDelay]（220ms），不另定时长。
class FeedTapArbiter {
  FeedTapArbiter({required VoidCallback onSingleTap, required ValueChanged<Offset> onDoubleTap,
      Duration window = kLightboxSingleTapDelay, double slop = kDoubleTapSlop});
  void tapUp(Offset globalPosition);
  void cancel();
  void dispose();
}
```
规则：
1. 第一下抬起 → 起 `window` 计时；到期无第二下 → `onSingleTap()`（进详情，B-AC3）。
2. 窗口内第二下且与第一下距离 ≤ `slop`（Flutter `kDoubleTapSlop`）→ 撤销计时、`onDoubleTap(第二下位置)`，并进入 `window` 长的「吞点」期：期内所有 `tapUp` 忽略（三连击只赞一次、不进详情，B-AC5）。
3. 窗口内第二下但距离 > `slop` → 第一下**立即**按单击执行，第二下作为新的第一下重新计时。
4. `cancel()`：撤销待执行的单击（滚动 / 横滑 / 长按开始、Widget 销毁时调用）。
5. 计时用可注入的 `Timer` 工厂或直接 `Timer`（测试用 `fakeAsync`）。

**AC2 — `MasonryCard` 图片区接入** `[L0]`（FR-209「生效范围」「和其他手势的关系」）
- `lib/shared/widgets/masonry_card.dart:263-296` 的图片区 `GestureDetector` 抽成一个私有 `StatefulWidget`（如 `_FeedImageTapArea`，持有 `FeedTapArbiter`），卡片本身保持 `StatelessWidget`：
  - `onTapUp: (d) => arbiter.tapUp(d.globalPosition)`、`onTapCancel: arbiter.cancel`；`onLongPress` 不变，长按开始时先 `arbiter.cancel()`（B-AC10）；`behavior: HitTestBehavior.opaque` 不变。
  - 单击回调 = 原 `onTap`（进详情页顶部）；双击回调 = AC3 动作。
- **只图片区**：正文区 `GestureDetector`（`:298` 起）、作者行、操作行一行不动（B-AC7：正文双击不点赞，第一下照常进详情）。
- 纯文字帖（`!item.hasImage`）图片区不存在，自然不支持。
- 读屏：`MediaQuery.accessibleNavigationOf(context)` 为 true 时，图片区退回原来的 `onTap: onTap`（不挂判定器），语义仍是「打开详情」；`Semantics(button: onTap != null)` 不变。
- 顶置卡与普通卡都生效（同一组件）；`promo_pinned_card.dart` 不受影响（加一条回归断言：推广卡上双击不调点赞）。
- 首页「发现」「关注」、话题页同一组件，一起生效。

**AC3 — 双击动作表** `[L0]`（FR-209「规则」、F-5、Q-9）
动作直接在 `_FeedImageTapArea`（`ConsumerStatefulWidget`）内通过 `ref` 完成，`MasonryCard` 把 `item`、`feedTab`、`rankMode` 传给它，不新增对外参数。按序：
1. 游客：`requireLogin(ref, context, onAllowed: () {}, entrySource: 'double_tap_like')` 返回 false → 结束（只弹登录窗；不播动效、不震、不报点赞事件；登录后不自动补赞，B-AC6）。
2. `view = store.effective(item.id, snapLiked: item.liked, snapCount: item.likeCount, snapEpoch: item.likeEpoch)`。
3. `view.liked || view.inFlight` → 只调 `playBurst()`（4.2 实现；本 story 空实现），**不**发请求、不震、不报、按钮不弹（B-AC2，Q-9）。
4. 否则：`HapticFeedback.lightImpact()` + `playBurst()` + `store.toggle(item.id, current: view, wantLiked: true, tracking: LikeTracking(source: 'feed', feedTab: feedTab, rankMode: rankMode, reason: feedTab == 'following' ? item.reason : null, trigger: 'double_tap'))`；store 在发起时把该帖 `bounceTick + 1`（3.1 预留字段），`LikeButton` 据此弹跳（弹跳动画本身在 4.2）（B-AC1）。
5. 请求失败：store 回滚按钮与计数；动效照常播完、不提示（B-AC8）。
- 帖子已删 / 不可见：接口报错 → 同第 5 条。
- 自己的帖子：和点爱心一样可以赞。

**AC4 — 埋点** `[L0]`（PRD B §4、F-9）
双击真正发起点赞时 `post_like_tapped` 带 `trigger: 'double_tap'`，`source = 'feed'`，`feed_tab / rank_mode / reason` 与该卡爱心按钮相同；已赞 / 飞行中再双击不报。游客双击只有登录转化 `signup_succeeded.entrySource = double_tap_like`（由现有 `requireLogin → showHardDialog(entrySource)` 透传，不新增事件）。

**AC5 — 手势冲突** `[L0]` `[L2]`（B-AC4、AC9、AC10）
- 多图（`FeedImage` 内 `PageView`）：在第 2 / 3 张双击 → 点赞成功、不翻页；第二下轻微位移不被识别为滑动（Tap 识别器在拖动阈值内胜出）。
- 纵向滚动：手指按下后开始滚动 → `onTapCancel` → 不进详情、不算双击。
- 长按：举报菜单不变，长按不参与双击判断。
- 下拉刷新不受影响。
- L0 用 widget test（`tester.tap` 两次间隔 100ms / 300ms、`tester.drag`、`tester.longPress`）；手感 L2 真机。

**AC6 — 验收对照** `[L0]` `[L1]` `[L2]`
- B-AC1：双击未赞帖 → 按钮红、+1（L0 假仓库）；`content_likes` 新增一行、作者收到通知（L2 连 stag 真后端）。
- B-AC2 ~ AC10：L0 widget test 各一例（AC3 断言单击在 220ms 后才导航；三连击只调一次 `like`）；L2 真机复测。

## Tasks / Subtasks

- [x] T0 开工核对：3.1 已在本分支且 `like_button` 旧测试全绿；`masonry_card.dart` 行号
- [x] T1 `FeedTapArbiter` + 单测（AC1）
- [x] T2 `_FeedImageTapArea` 接入、读屏退回（AC2）
- [x] T3 双击动作表（AC3）+ 埋点（AC4）
- [x] T4 widget test（AC5、AC6）+ L0；Completion Notes 标「L2 待本地」

## Dev Notes

### 现状
| 位置 | 现状 |
|---|---|
| `lib/shared/media/lightbox_gestures.dart:89` | `const Duration kLightboxSingleTapDelay = Duration(milliseconds: 220);` |
| `lib/shared/media/image_lightbox.dart:398-412` `_handleTapUp` | Timer 仲裁单击关闭 / 双击缩放；**无距离判断、无三连击忽略**——本 story 的判定器是新写的，只复用常量 |
| `test/shared/lightbox_gesture_test.dart:139-141` | 断言 `kLightboxSingleTapDelay < kDoubleTapTimeout` 且 ≥ 180ms ——**不要改这个常量** |
| `lib/shared/widgets/masonry_card.dart:263-296` | 图片区 `GestureDetector(behavior: opaque, onTap, onLongPress, child: Column[FeedImage(...)])` |
| `lib/features/auth/domain/auth_guard.dart:15-31` | `requireLogin(ref, context, {pendingAction, onAllowed, entrySource = 'other'})` |
| `lib/shared/widgets/feed_image.dart:295` | 多图 `PageView.builder`；单图不挂横向识别器 |

### 为什么不用 `onDoubleTap`
同时注册 `onTap` + `onDoubleTap` 时，单击要等满 `kDoubleTapTimeout`（300ms）才触发；PRD 定的是 220ms（与大图页一致）。

### 测试与命令
- 新增：`test/shared/feed_tap_arbiter_test.dart`（单击 220ms 后触发、双击、三连击、超距、cancel）；`test/content/feed_double_tap_like_test.dart`（AC3 动作表各分支、AC5 手势冲突、推广卡不响应、正文区不响应、读屏退回）。
- 回归：`test/content/*`、`test/shared/lightbox_*`、`test/analytics/*`。
- L0：`cd petgo_app && flutter analyze && flutter test`。

## Dev Agent Record

### Completion Notes
- **L1/L2 待本地验收**：真机手感（双击节奏、多图横滑、纵向滚动、长按）、读屏、震动、B-AC1 连 stag 真后端（`content_likes` 新行 + 作者通知）云端未做；动效本身在 4.2（本 story `_playBurst()` 为空实现）。
- **L0**：`flutter test` 全量 4971 通过；`flutter analyze` 0 error / 0 warning，相对基线无新增非弃用类 info（SDK 3.50 与 lock 说明同 3.1）。
- T0 核对：3.1 已在本分支（f18ddd3b），`like_button_test` / `like_button_split_test` / `like_button_reason_test` 断言未改全绿；`masonry_card.dart` 图片区 `GestureDetector` 在 `:263` 附近，与 story 一致。
- 实现：
  - `lib/shared/media/feed_tap_arbiter.dart`：纯逻辑判定器（窗口 = `kLightboxSingleTapDelay`，`slop = kDoubleTapSlop`）；`lightbox_gestures.dart` / `image_lightbox.dart` 未改。
  - `MasonryCard` 图片区抽成私有 `_FeedImageTapArea`（`ConsumerStatefulWidget`），卡片仍是 `StatelessWidget`、无新对外参数；正文区手势一行未动。双击动作表照 F-5；已赞 / 飞行中只播动效（不请求、不震、不报、按钮不弹，Q-9）；未赞时 `HapticFeedback.lightImpact()` + `store.toggle(..., bounce: true, trigger: 'double_tap')`。
  - store：`toggle` 新增可选 `bounce`，为 true 时乐观写入把 `bounceTick + 1`（3.1 预留字段）。
  - 复审后加固：延迟单击触发前复查「所在路由仍是当前页、所在 Tab 仍在前台（`TickerMode`）」，否则丢弃（窗口内已切走 / 两下超距第一下已进详情时不再推第二次）；同位置 State 被复用给另一条帖子时撤销待执行单击；新指针移动超过 `kTouchSlop` 即撤销（快速轻扫在 tap-down 前就被拖动赢走时 Tap 识别器收不到 cancel）；`onTap == null` 的卡片退回原手势、不挂判定器。
- **按设计打破的既有测试**：`test/content/feed_carousel_test.dart` 两例「点击图片仍进详情」改为「先断言 0、再 `pump(kLightboxSingleTapDelay)` 后断言 1」——表达新规则「图片区单击 220ms 后进详情」，未删、未 skip。
- 新增测试：`test/shared/feed_tap_arbiter_test.dart`（单击 220ms、双击、三连击、超距、窗口外、cancel、dispose；用 `testWidgets` 假时钟——`fake_async` 只是传递依赖，直接 import 会触发 lint）；`test/content/feed_double_tap_like_test.dart`（B-AC1 ~ AC10 各一例 + 飞行中、读屏、推广卡、窗口内滚动 / 换页两条加固）。游客用例用默认测试视图：测试字体比真机宽，390 宽下登录弹窗溢出 2px（测试环境问题，与本 story 无关）。
- **待确认**：`bounceTick` 按帖子计（F-5 设计）——同一帖同时出现在顶置卡与普通卡时，4.2 接上弹跳后两个爱心都会弹一下。

### File List
- `petgo_app/lib/shared/media/feed_tap_arbiter.dart`（新）
- `petgo_app/lib/shared/widgets/masonry_card.dart`
- `petgo_app/lib/features/content/domain/post_like_state.dart`
- `petgo_app/test/shared/feed_tap_arbiter_test.dart`（新）
- `petgo_app/test/content/feed_double_tap_like_test.dart`（新）
- `petgo_app/test/content/feed_carousel_test.dart`
