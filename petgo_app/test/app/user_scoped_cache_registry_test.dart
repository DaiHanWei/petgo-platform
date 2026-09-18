import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// 「按当前用户算的缓存必须登记换账号清理」的登记簿（batch-a #8 / batch-b1 复审 S1·F1）。
///
/// 同一类漏登记已经出现三次：引导标记、宠物推荐、@ 候选集。每次都是「新加了一个常驻的
/// 用户维度 provider，却没进 `resetUserScopedCaches`」—— 同设备 A 直切 B 时，
/// B 看到的是按 A 算的数据（隐私泄漏 / 自己的宠物被推给自己 / 拉黑的人又冒出来）。
///
/// ⚠️ 这里只能守住「登记过的不被删掉」。新增用户维度 provider 时**把它加进下面的清单**，
/// 这条红了就说明有人把登记删了。
const _mustReset = <String>[
  'petProfileProvider',
  'timelineFirstPageProvider',
  'archiveStatsProvider',
  'milestoneListProvider',
  'healthListProvider',
  'myPostsProvider',
  'feedProvider',
  'unreadCountProvider',
  'idCardListProvider',
  'newbieTasksProvider',
  'pawCoinProvider',
  'orderListProvider',
  // batch-b1
  'petRecommendationsProvider',
  'mentionCandidatesProvider',
];

void main() {
  final app = File('lib/app.dart').readAsStringSync();
  final start = app.indexOf('void resetUserScopedCaches');
  final body = app.substring(start, app.indexOf('\n}', start));

  for (final name in _mustReset) {
    test('$name 已登记 resetUserScopedCaches', () {
      expect(body, contains('ref.invalidate($name)'));
    });
  }

  /// S2：取消拉黑与拉黑是对称的 —— 推荐位按拉黑关系过滤，首页横滑行常驻不回收。
  test('取消拉黑成功后让推荐位重算', () {
    final src = File('lib/features/social/presentation/blocked_users_page.dart').readAsStringSync();
    final unblock = src.substring(src.indexOf('.unblock(b.userId)'));
    expect(unblock.substring(0, 400), contains('invalidatePetRecommendations(ref)'));
  });

  /// F8：场所评论点作者进主页拉黑 / 举报后回来，评论区要重拉（同帖子评论区）。
  test('场所评论作者入口带 onBlocked / onReported 收尾', () {
    final src =
        File('lib/features/place/presentation/place_comment_section.dart').readAsStringSync();
    expect(src, contains('onBlocked: () => _onAuthorHidden(ref)'));
    expect(src, contains('onReported: () => _onAuthorHidden(ref)'));
  });
}
