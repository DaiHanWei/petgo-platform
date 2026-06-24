import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/detail_repository.dart';
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
