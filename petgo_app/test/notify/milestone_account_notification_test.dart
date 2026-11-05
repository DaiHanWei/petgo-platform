import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.2 social-topic Story 2.3（R-4 · G-2 · UI B4）：账号级里程碑（第一次关注 / 收藏）在通知中心
/// 的文案不拼宠物名；点击进当前宠物的里程碑列表，当前宠物只读 → 先切到第一只 ACTIVE（L0）。
class _FakeNotificationRepo extends NotificationRepository {
  _FakeNotificationRepo(this.items) : super(dio: Dio());

  final List<NotificationItem> items;

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => items.where((e) => !e.read).length;

  @override
  Future<void> markRead(String token) async {}
}

class _NotGone extends ContentGoneProbe {
  _NotGone() : super(Dio());

  @override
  Future<bool> isGone(int postId) async => false;
}

NotificationItem _item(String targetRef, {String type = 'MILESTONE_SM_NODE'}) => NotificationItem(
      type: type,
      targetRef: targetRef,
      deepLinkType: 'MILESTONE_NODE',
      deepLinkToken: 'tok-$targetRef',
      read: false,
      createdAt: DateTime.now(),
    );

PetListItem _pet(int id, String name, {String status = PetListItem.statusActive}) =>
    PetListItem(id: id, name: name, petType: 'CAT', status: status);

Future<(ProviderContainer, List<String>)> _pump(
  WidgetTester tester,
  List<NotificationItem> items, {
  List<PetListItem> pets = const [],
  int? currentPetId,
}) async {
  final container = ProviderContainer(overrides: [
    notificationRepositoryProvider.overrideWithValue(_FakeNotificationRepo(items)),
    contentGoneProbeProvider.overrideWithValue(_NotGone()),
    petListProvider.overrideWith((ref) async => pets),
  ]);
  addTearDown(container.dispose);
  if (currentPetId != null) await container.read(currentPetIdProvider.notifier).select(currentPetId);
  final visited = <String>[];
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: GoRouter(
        initialLocation: '/notifications',
        routes: [
          GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
          GoRoute(
              path: '/profile/milestones',
              builder: (_, _) {
                visited.add('/profile/milestones@${container.read(currentPetIdProvider)}');
                return const SizedBox();
              }),
        ],
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return (container, visited);
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() => Analytics.debugCaptureSink = null);

  group('B4 文案不拼宠物名（R-4）', () {
    testWidgets('有宠物名 → 庆祝标题 + 正文，不含宠物名', (tester) async {
      await _pump(tester, [_item('C-S19')], pets: [_pet(1, 'Mochi')]);
      expect(find.text('Teman pertama! 🤝'), findsOneWidget);
      expect(find.textContaining('Kamu mulai mengikuti seseorang'), findsOneWidget);
      expect(find.textContaining('Mochi'), findsNothing);
    });

    testWidgets('无宠物名 → 仍走庆祝标题 + 正文（不退化成短标题）', (tester) async {
      await _pump(tester, [_item('G-S13')]);
      expect(find.text('Simpan pertama! 🔖'), findsOneWidget);
      expect(find.textContaining('Postingan pertama sudah tersimpan'), findsOneWidget);
    });

    testWidgets('非账号级编码无宠物名 → 照旧短标题（回归）', (tester) async {
      await _pump(tester, [_item('C-S14')]);
      expect(find.textContaining('Komentar pertama'), findsOneWidget);
    });
  });

  group('B4 点击（G-2）', () {
    testWidgets('当前宠物 READ_ONLY → 先 select 第一只 ACTIVE 再进 /profile/milestones；埋点照旧', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
      final (container, visited) = await _pump(tester, [_item('C-S19')],
          pets: [_pet(1, 'RO', status: PetListItem.statusReadOnly), _pet(2, 'Momo'), _pet(3, 'Kiki')],
          currentPetId: 1);
      await tester.tap(find.text('Teman pertama! 🤝'));
      await tester.pumpAndSettle();
      expect(container.read(currentPetIdProvider), 2);
      expect(visited, ['/profile/milestones@2']);
      final tap = seen.where((e) => e.$1 == 'app_notification_item_tapped').single.$2!;
      expect(tap['notif_type'], 'milestone_sm');
      expect(tap['level'], 'S');
    });

    testWidgets('当前宠物 ACTIVE → 不切，直接进列表', (tester) async {
      final (container, visited) = await _pump(tester, [_item('D-S20')],
          pets: [_pet(1, 'Momo'), _pet(2, 'Bobby')], currentPetId: 2);
      await tester.tap(find.text('Simpan pertama! 🔖'));
      await tester.pumpAndSettle();
      expect(container.read(currentPetIdProvider), 2);
      expect(visited, ['/profile/milestones@2']);
    });

    testWidgets('非账号级编码 + 当前只读 → 不切（其余类型不变）', (tester) async {
      final (container, visited) = await _pump(tester, [_item('C-S14')],
          pets: [_pet(1, 'RO', status: PetListItem.statusReadOnly), _pet(2, 'Momo')], currentPetId: 1);
      await tester.tap(find.text('Ada yang komentar soal RO! 💬'));
      await tester.pumpAndSettle();
      expect(container.read(currentPetIdProvider), 1);
      expect(visited, ['/profile/milestones@1']);
    });
  });
}
