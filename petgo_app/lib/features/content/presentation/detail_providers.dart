import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/detail_repository.dart';
import '../domain/comment.dart';
import '../domain/content_detail.dart';

/// 内容详情（按 id 的 family）。AsyncValue 三态：loading 骨架 / data / error（多态分类）。
///
/// autoDispose：离开详情页即弃缓存，重进重新拉取——否则点赞/评论变化后重进读到旧缓存
/// （如点赞后返回列表再进入，liked 显示丢失）。
final detailProvider = FutureProvider.autoDispose.family<ContentDetail, int>(
  (ref, id) => ref.read(detailRepositoryProvider).getDetail(id),
);

/// 评论区刷新信号（Story 3.5）。发表/回复/删除后 [bump]，CommentSection watch 后重拉。
class CommentsRefreshNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state = state + 1;
}

final NotifierProvider<CommentsRefreshNotifier, int> commentsRefreshProvider =
    NotifierProvider<CommentsRefreshNotifier, int>(CommentsRefreshNotifier.new);

/// 当前回复目标（Story 3.5）。null = 发一级评论；非空 = 回复某一级评论。
class ReplyTarget {
  const ReplyTarget({required this.parentId, required this.toName});

  final int parentId;
  final String toName;
}

class ReplyTargetNotifier extends Notifier<ReplyTarget?> {
  @override
  ReplyTarget? build() => null;

  void set(ReplyTarget target) => state = target;

  void clear() => state = null;
}

final NotifierProvider<ReplyTargetNotifier, ReplyTarget?> replyTargetProvider =
    NotifierProvider<ReplyTargetNotifier, ReplyTarget?>(ReplyTargetNotifier.new);

/// 🔴 回复发表成功后的**落点**（V1.3.0 Story 2.6 · AC5）。
///
/// 二级回复按时间正序挂在父评论下，且默认只内嵌 3 条 —— 一条新回复很可能**落在折叠区里**，
/// 用户发完看不到自己刚回的内容（与 Story 2.5 · AC6 的一级评论同一类问题，只是成因不同：
/// 那边是热度序把它挤出第一页，这边是默认折叠把它藏起来）。
///
/// 所以回复成功后要做两件事：**展开该父评论的回复区** + **滚动定位过去**。
///
/// ⚠️ 带 [seq] 是因为「连着回同一条父评论两次」必须两次都定位 ——
/// 只存 parentId 的话第二次状态没变，监听方收不到通知。
/// ⚠️ 落点由 `CommentSection` 在**重拉完成之后**消费并清空（刷新会清掉 `_expanded`，
/// 先展开再刷新等于白展开）。
class ReplyLanding {
  const ReplyLanding({required this.parentId, required this.replyId, required this.seq});

  /// 要展开的父评论。
  final int parentId;

  /// 刚发出的那条回复。**回复区可能不止一页** —— 二级按时间正序，新回复落在**最后一页**，
  /// 只展开第一页时它照样看不见。带上 id，评论区才能一路翻到它为止。
  final int replyId;

  final int seq;
}

class ReplyLandingNotifier extends Notifier<ReplyLanding?> {
  @override
  ReplyLanding? build() => null;

  void request({required int parentId, required int replyId}) => state = ReplyLanding(
        parentId: parentId,
        replyId: replyId,
        seq: (state?.seq ?? 0) + 1,
      );

  void clear() => state = null;
}

final NotifierProvider<ReplyLandingNotifier, ReplyLanding?> replyLandingProvider =
    NotifierProvider<ReplyLandingNotifier, ReplyLanding?>(ReplyLandingNotifier.new);

/// 评论框聚焦请求信号。点击互动栏评论图标时 [requestFocus]，CommentComposer 监听后弹出键盘。
/// （回复按钮通过 [replyTargetProvider] 变更触发聚焦，无需经此信号。）
class CommentFocusNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void requestFocus() => state = state + 1;
}

final NotifierProvider<CommentFocusNotifier, int> commentFocusProvider =
    NotifierProvider<CommentFocusNotifier, int>(CommentFocusNotifier.new);

/// 🔴 本次会话内**自己新发的一级评论 id**（V1.3.0 Story 2.5 · AC6 · AD-A8.8）。
///
/// Story 2.4 把一级评论默认序改成了**热度序**，于是刚发的评论（0 赞）排在所有有赞评论之后
/// —— 在一个有 10+ 条热门评论的帖子里，**用户发完看不到自己刚发的东西**。这是改热度序
/// 必然带出来的副作用，不是偶发 bug。
///
/// 收口方式与三条约束：
/// 1. **服务端排序一点不改** —— 置顶纯粹是客户端这一次会话的事；
/// 2. **置顶项不参与游标计算** —— 游标取服务端返回那一页的最后一条，
///    把插进来的项算进去会制造重复/漏条；
/// 3. **离开详情页即失效** —— `CommentSection` 在 `dispose` 时清空本集合。
///
/// ⚠️ 不要把它做成持久化的「我的评论」标记：那会变成一个永久置顶自己评论的功能，
/// 而这里要的只是「别让我找不到刚发的那条」。
/// ⚠️ 存的是**整条评论**而不只是 id：热度序下 0 赞的新评论很可能**根本不在第一页里**，
/// 只记 id 就只能"把已经拿到的那条挪到前面"，拿不到的时候还是看不见。
/// 存下整条，列表就能在服务端没返回它时**直接把本地这份插到首位**。
class SessionPinnedCommentsNotifier extends Notifier<Map<int, Comment>> {
  @override
  Map<int, Comment> build() => const <int, Comment>{};

  void add(Comment comment) => state = {...state, comment.id: comment};

  void clear() => state = const <int, Comment>{};
}

final NotifierProvider<SessionPinnedCommentsNotifier, Map<int, Comment>>
    sessionPinnedCommentsProvider =
    NotifierProvider<SessionPinnedCommentsNotifier, Map<int, Comment>>(
        SessionPinnedCommentsNotifier.new);
