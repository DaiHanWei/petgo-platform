import 'package:petgo/features/content/data/feed_repository.dart';
import 'package:petgo/features/content/domain/feed_item.dart';

/// 测试用 Feed 仓库：返回固定 items（默认空），hasMore=false，同步完成。
///
/// 用于 app 级 widget 测试覆写 [feedRepositoryProvider]，让首页 Feed 进入确定态
/// （否则真实 dio 调用在测试环境悬挂在 loading 骨架）。
class FakeFeedRepository implements FeedRepository {
  FakeFeedRepository([this.items = const []]);

  final List<FeedItem> items;

  @override
  Future<FeedPage> getFeed({
    FeedCategory category = FeedCategory.all,
    String? cursor,
    int limit = 20,
  }) async =>
      FeedPage(items: items, nextCursor: null, hasMore: false);
}
