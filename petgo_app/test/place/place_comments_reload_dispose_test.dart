import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/place/presentation/place_comments_controller.dart';

/// code review #11：拉黑 / 举报评论作者后触发的 reload() 不等结果；请求在途时离开页面，
/// 回来那次写 state 会抛出未处理异常。
class _GatedRepo extends Fake implements PlaceRepository {
  final List<Completer<PlaceCommentPage>> pending = [];
  bool firstServed = false;

  @override
  Future<PlaceCommentPage> fetchComments(String token, {String? cursor}) {
    if (!firstServed) {
      firstServed = true;
      return Future.value(PlaceCommentPage.empty);
    }
    final c = Completer<PlaceCommentPage>();
    pending.add(c);
    return c.future;
  }
}

void main() {
  test('reload 在途时页面关闭 → 请求回来不抛', () async {
    final repo = _GatedRepo();
    final container = ProviderContainer(overrides: [placeRepositoryProvider.overrideWithValue(repo)]);
    final sub = container.listen(placeCommentsProvider('p1'), (_, _) {});
    await container.read(placeCommentsProvider('p1').future);

    final f = container.read(placeCommentsProvider('p1').notifier).reload();
    sub.close();
    await Future<void>.delayed(Duration.zero);
    repo.pending.single.complete(PlaceCommentPage.empty);

    await expectLater(f, completes);
    container.dispose();
  });
}
