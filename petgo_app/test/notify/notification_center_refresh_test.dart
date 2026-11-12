import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 修复线（2026-11-12）：通知中心下拉刷新 —— 有条目 / 空态都能拉；拉完换成新数据；失败保留旧列表。
class _Repo extends NotificationRepository {
  _Repo() : super(dio: Dio());
  List<NotificationItem> next = const [];
  bool fail = false;
  int calls = 0;

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async {
    calls++;
    if (fail) throw DioException(requestOptions: RequestOptions(path: '/notifications'));
    return NotificationPage(items: next, hasMore: false);
  }

  @override
  Future<int> unreadCount() async => 0;

  @override
  Future<void> markRead(String token) async {}

  @override
  Future<void> markAllRead() async {}
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

NotificationItem _item(String token, String title) => NotificationItem(
      type: 'CONTENT_LIKED',
      deepLinkType: 'CONTENT_LIKED',
      deepLinkToken: token,
      targetRef: '55',
      title: title,
      read: false,
      createdAt: DateTime.now().toUtc(),
      actorName: 'Rani',
      actorCount: 1,
    );

Future<void> _pump(WidgetTester tester, _Repo repo) async {
  final router = GoRouter(
    initialLocation: '/notifications',
    routes: [GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage())],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    notificationRepositoryProvider.overrideWithValue(repo),
    authControllerProvider.overrideWith(_Auth.new),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
}

Future<void> _pullDown(WidgetTester tester) async {
  await tester.fling(find.byKey(const ValueKey('notificationRefresh')), const Offset(0, 400), 1000);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('有条目：下拉刷新 → 重拉首页、换成新条目', (tester) async {
    final repo = _Repo()..next = [_item('a', 'Rani liked your post')];
    await _pump(tester, repo);
    expect(find.text('Rani liked your post'), findsOneWidget);

    repo.next = [_item('b', 'Budi liked your post'), _item('a', 'Rani liked your post')];
    await _pullDown(tester);

    expect(repo.calls, 2);
    expect(find.byKey(const ValueKey('notification_b')), findsOneWidget);
  });

  testWidgets('空态也能下拉刷新', (tester) async {
    final repo = _Repo();
    await _pump(tester, repo);
    expect(find.byKey(const ValueKey('notificationEmpty')), findsOneWidget);

    repo.next = [_item('a', 'Rani liked your post')];
    await _pullDown(tester);

    expect(repo.calls, 2);
    expect(find.byKey(const ValueKey('notification_a')), findsOneWidget);
  });

  testWidgets('刷新失败：保留旧列表，不换成错误页', (tester) async {
    final repo = _Repo()..next = [_item('a', 'Rani liked your post')];
    await _pump(tester, repo);

    repo.fail = true;
    await _pullDown(tester);

    expect(repo.calls, 2);
    expect(find.byKey(const ValueKey('notification_a')), findsOneWidget);
    await tester.pump(const Duration(seconds: 5)); // 等轻提示自动消失（其计时器不能留到用例结束）
  });
}
