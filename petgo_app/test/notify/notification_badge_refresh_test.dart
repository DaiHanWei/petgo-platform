import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/presentation/notification_bell.dart';

/// 假仓库：每次调用返回预设序列里的下一个数，用来模拟「服务端计数涨了」。
class _SeqRepo implements NotificationRepository {
  _SeqRepo(this._counts);

  final List<int> _counts;
  int calls = 0;

  @override
  Future<int> unreadCount() async {
    final v = _counts[calls.clamp(0, _counts.length - 1)];
    calls++;
    return v;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Future<ProviderContainer> _pump(WidgetTester tester, _SeqRepo repo) async {
  final container = ProviderContainer(
    overrides: [notificationRepositoryProvider.overrideWithValue(repo)],
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(home: Scaffold(body: NotificationBell())),
    ),
  );
  await tester.pumpAndSettle();
  return container;
}

void main() {
  /// 🔴 Bug 20260911-495 的核心：角标此前**只在启动/切账号时拉一次**，
  /// 之后的失效点全在「变少」那一侧（点开通知、标已读）。
  /// 于是别人给你点赞/评论、服务端计数涨了，铃铛上还是启动那一刻的数字（常常是 0）。
  testWidgets('计数从 0 涨到 3：invalidate 后角标必须跟上', (tester) async {
    final repo = _SeqRepo([0, 3]);
    final container = await _pump(tester, repo);

    expect(find.byKey(const ValueKey('notificationBadge')), findsNothing,
        reason: '首次拉到 0，不该有角标');

    // 模拟「回到前台 / 下拉刷新」那一下。
    container.invalidate(unreadCountProvider);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('notificationBadge')), findsOneWidget);
    expect(find.text('3'), findsOneWidget);
    expect(repo.calls, 2, reason: '必须真的重新问过服务端，不能只用缓存');
  });

  /// 🛡 刷新期间不许闪：loading 态要保留上一次的值。
  /// 写成 `maybeWhen(data:..., orElse: () => 0)` 就会在每次刷新时先把角标闪没。
  testWidgets('刷新过程中角标保留旧值，不闪没', (tester) async {
    final repo = _SeqRepo([3, 5]);
    final container = await _pump(tester, repo);
    expect(find.text('3'), findsOneWidget);

    container.invalidate(unreadCountProvider);
    await tester.pump(); // 只推一帧：此刻处于 loading，新值还没回来
    expect(find.byKey(const ValueKey('notificationBadge')), findsOneWidget,
        reason: '刷新中角标不该消失');
    expect(find.text('3'), findsOneWidget, reason: '刷新中应保留上一次的值');

    await tester.pumpAndSettle();
    expect(find.text('5'), findsOneWidget);
  });
}
