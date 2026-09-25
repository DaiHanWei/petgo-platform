import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/place_repository.dart';
import '../domain/place_comment.dart';
import 'place_location_controller.dart';

/// 场所评论区控制器（V1.3.0 batch-b1 Story 1.7），按场所 token 分族。
///
/// <h2>🔴 为什么不是一个 `FutureProvider` + `invalidate`</h2>
/// 评论是**游标分页累积**的：用户翻到第三页时 `invalidate` 会把前三页全部重拉、
/// 并把列表打回第一页 —— 他会莫名其妙被弹回顶部。所以累积状态必须由控制器自己持有，
/// 「加载更多」只往后追加（同 `feed_controller.dart` 的既定范式）。
///
/// <p>⚠️ 族键**只有 token，不带坐标**：评论与位置无关，把坐标混进来会让每次 GPS 抖动
/// 都把整个评论区打回 loading。
///
/// <p>`isAutoDispose: true`：详情页是 push 进来的一次性页面，退出就该回收 ——
/// Riverpod 3 的 legacy `AsyncNotifierProvider` 默认是 **false**（不自动回收），
/// 不显式打开的话每看过一个场所就永久挂一个族。
class PlaceCommentsController extends AsyncNotifier<PlaceCommentPage> {
  /// ⚠️ Riverpod 3 的 class family：族参数**从构造器进来**（provider 的工厂签名是
  /// `(Arg) -> Notifier`），`build()` 本身仍然不带参数。
  PlaceCommentsController(this.token);

  final String token;

  @override
  Future<PlaceCommentPage> build() async {
    return ref.read(placeRepositoryProvider).fetchComments(token);
  }

  /// 追加下一页。
  ///
  /// 失败**不改动已加载内容**（F13）：保留现有列表，由调用方提示一声即可 ——
  /// 把整屏换成错误态是这条口径首先要避免的事。
  /// 在途的那次「加载更多」。🔴 连点两下会用同一个游标发两次请求、把同一页追加两遍
  /// （重复条目 + 重复 ValueKey），所以在途时直接复用它（batch-b1 复审）。
  Future<void>? _loadingMore;

  Future<void> loadMore() => _loadingMore ??= _loadMore().whenComplete(() => _loadingMore = null);

  Future<void> _loadMore() async {
    final current = state.value;
    final cursor = current?.nextCursor;
    if (current == null || !current.hasMore || cursor == null) return;
    final next =
        await ref.read(placeRepositoryProvider).fetchComments(token, cursor: cursor);
    // 等待期间页面走了（autoDispose）或列表被整体重拉过：这一页已经不属于当前列表，丢掉。
    if (!ref.mounted || !identical(state.value, current)) return;
    state = AsyncData(current.append(next));
  }

  /// 发表成功 / 删除成功后重拉第一页。
  ///
  /// ⚠️ 刻意**重拉**而不是本地插入/移除一条：
  /// 新评论落的是 `UNDER_REVIEW`、带「仅你可见」标签，而那条状态只有服务端知道；
  /// 本地拼一条出来，标签、排序、`total` 三样都会与服务端不一致。
  /// ⚠️ 刻意**不先置 `AsyncLoading`**：置了的话整个评论区会在一瞬间被转圈顶掉，
  /// 而用户刚刚才发出一条评论 —— 那一下闪烁看起来像是"发失败了、列表没了"。
  /// 直接用新结果替换即可（失败时保留 AsyncError，由调用方提示）。
  Future<void> reload() async {
    final result = await AsyncValue.guard(
        () => ref.read(placeRepositoryProvider).fetchComments(token));
    // 🔴 等待期间页面走了（autoDispose）：再写 state 会抛出未处理异常 —— 拉黑 / 举报作者后
    //    触发的那次 reload 是不等结果的，离开页面正好撞上（code review #11，同 _loadMore）。
    if (!ref.mounted) return;
    state = result;
  }
}

final placeCommentsProvider =
    AsyncNotifierProvider.family<PlaceCommentsController, PlaceCommentPage, String>(
  PlaceCommentsController.new,
  isAutoDispose: true,
);

/// 让某个场所的**详情**重新取数 —— 评论发表 / 删除之后要用。
///
/// 🔴 为什么非刷不可：评论数（`commentCount`）在**详情响应**里，而评论列表是另一个接口。
/// 只刷评论区的话，同一屏上计数行的「💬 3」与评论区标题的「评论 (4)」会对不上
/// （code-review 2026-09-15）。
///
/// 🔴 **按 token 失效整族，而不是猜"当前那个族键"**：详情族键带着归一后的坐标，
/// 而调用方（评论区 / 输入条）手里并没有那份坐标 —— 猜错就只是失效了一个没人在看的族，
/// 屏幕上那份继续显示旧数字。实际存在的族键只可能是两种：不带坐标的、以及当前定位那一个，
/// 两个都失效比去猜可靠。
void invalidatePlaceDetail(WidgetRef ref, String token) {
  ref.invalidate(placeDetailProvider((token: token, lat: null, lng: null)));
  final coords = ref.read(placeLocationProvider).value?.coordinates;
  if (coords != null) {
    ref.invalidate(
        placeDetailProvider(placeDetailQueryFor(token, coords.latitude, coords.longitude)));
  }
}

/// 同 [invalidatePlaceDetail]，但作用在 [ProviderContainer] 上 —— 给**可能在页面销毁后**
/// 才走到收尾的异步流程用（例如补充照片上传途中用户返回）：那时 WidgetRef 已不可用。
void invalidatePlaceDetailIn(ProviderContainer container, String token) {
  container.invalidate(placeDetailProvider((token: token, lat: null, lng: null)));
  final coords = container.read(placeLocationProvider).value?.coordinates;
  if (coords != null) {
    container.invalidate(
        placeDetailProvider(placeDetailQueryFor(token, coords.latitude, coords.longitude)));
  }
}
