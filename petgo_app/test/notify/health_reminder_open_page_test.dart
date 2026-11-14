import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/notify/data/health_notification_repository.dart';
import 'package:tailtopia/features/notify/domain/health_reminder_attribution.dart';
import 'package:tailtopia/features/notify/domain/health_reminder_open.dart';
import 'package:tailtopia/features/notify/presentation/health_reminder_open_page.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 健康记录提醒 Story 3.4 · AC2 / AC4：点开路由页（假仓储）。
class _Repo implements HealthNotificationRepository {
  _Repo(this.answer);
  final Future<HealthReminderOpenResult> Function() answer;
  final calls = <(String, String)>[];

  @override
  Future<HealthReminderOpenResult> open(String token, {required String source}) {
    calls.add((token, source));
    return answer();
  }
}

const _analytics = {
  'notificationId': 'nid-1',
  'notificationKind': 'V-D0',
  'pushPoint': 'd_day',
  'origin': 'record',
  'mergedCount': 2,
};

class _Harness {
  _Harness(this.container, this.repo, this.locations);
  final ProviderContainer container;
  final _Repo repo;
  final List<String> locations;
}

Future<_Harness> _pump(WidgetTester tester, Map<String, dynamic>? body,
    {String src = 'push', String token = 'tok-1', int currentPet = 1, bool throws = false}) async {
  SharedPreferences.setMockInitialValues({});
  final repo = _Repo(() async {
    if (throws) throw StateError('network');
    return HealthReminderOpenResult.fromJson(body!);
  });
  final c = ProviderContainer(overrides: [
    healthNotificationRepositoryProvider.overrideWithValue(repo),
    effectivePetIdProvider.overrideWithValue(AsyncData(currentPet)),
  ]);
  addTearDown(c.dispose);
  final locations = <String>[];
  final router = GoRouter(initialLocation: '/home', routes: [
    GoRoute(path: '/home', builder: (_, _) => const Scaffold(body: Text('HOME'))),
    GoRoute(
        path: '/health-reminder/open',
        builder: (_, s) => HealthReminderOpenPage(
            token: s.uri.queryParameters['t'] ?? '', source: s.uri.queryParameters['src'] ?? 'inbox')),
    GoRoute(
        path: '/profile/health',
        builder: (_, s) {
          locations.add(s.uri.toString());
          return const Scaffold(body: Text('HEALTH'));
        }),
    GoRoute(path: '/notifications', builder: (_, _) => const Scaffold(body: Text('INBOX'))),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: MaterialApp.router(
      routerConfig: router,
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  router.push('/health-reminder/open?t=${Uri.encodeQueryComponent(token)}&src=$src');
  await tester.pumpAndSettle();
  return _Harness(c, repo, locations);
}

void main() {
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Map<String, Object>? opened() => events.where((e) => e.$1 == 'health_notification_opened').singleOrNull?.$2;

  testWidgets('FORM：replace 到 /profile/health?add=…&nid=；返回键回到点击前的页面；同宠物不报 pet_switched', (tester) async {
    final h = await _pump(tester, {
      'status': 'OK',
      'petId': 1,
      'route': 'FORM',
      'prefill': {'type': 'VACCINE', 'vaccinePreset': 'F4', 'dose': 2, 'eventDateEmpty': true},
      'analytics': _analytics,
    });
    expect(h.repo.calls.single, ('tok-1', 'push'));
    expect(find.text('HEALTH'), findsOneWidget);
    final uri = Uri.parse(h.locations.single);
    expect(uri.queryParameters, {'add': 'VACCINE', 'preset': 'F4', 'dose': '2', 'dateEmpty': '1', 'nid': 'tok-1'});
    expect(events.where((e) => e.$1 == 'pet_switched'), isEmpty);
    expect(opened(), {
      'notification_id': 'nid-1',
      'pet_id': 1,
      'notification_kind': 'V-D0',
      'push_point': 'd_day',
      'origin': 'record',
      'merged_count': 2,
      'source': 'push',
      'route': 'form',
    });
    // replace：返回回到 HOME，不会再回到加载页重复点开。
    final nav = tester.state<NavigatorState>(find.byType(Navigator).last);
    nav.pop();
    await tester.pumpAndSettle();
    expect(find.text('HOME'), findsOneWidget);
    expect(h.repo.calls, hasLength(1));
  });

  testWidgets('PAGE：别的宠物 → 切宠物 + pet_switched{source: health_reminder}；focus + safety + nid；记归因', (tester) async {
    final h = await _pump(tester, {
      'status': 'OK',
      'petId': 7,
      'route': 'PAGE',
      'focusRecordId': 42,
      'safetyBoxes': ['PREGNANT', 'PERMETHRIN'],
      'analytics': _analytics,
    }, currentPet: 1, src: 'inbox');
    expect(h.container.read(currentPetIdProvider), 7);
    final sw = events.where((e) => e.$1 == 'pet_switched').single.$2!;
    expect(sw, {'from_pet_id': 1, 'to_pet_id': 7, 'source': 'health_reminder'});
    expect(Uri.parse(h.locations.single).queryParameters,
        {'focus': '42', 'safety': 'PREGNANT,PERMETHRIN', 'nid': 'tok-1'});
    expect(opened()!['route'], 'page');
    expect(opened()!['source'], 'inbox');
    final attr = h.container.read(healthReminderAttributionProvider.notifier).lookup(7);
    expect(attr?.notificationId, 'nid-1');
    expect(attr?.notificationKind, 'V-D0');
  });

  testWidgets('PAGE_SATISFIED → route=page_satisfied', (tester) async {
    await _pump(tester, {'status': 'OK', 'petId': 1, 'route': 'PAGE_SATISFIED', 'analytics': _analytics});
    expect(opened()!['route'], 'page_satisfied');
    expect(find.text('HEALTH'), findsOneWidget);
  });

  testWidgets('PET_UNAVAILABLE → 通知中心 + toast；route=inbox_fallback', (tester) async {
    await _pump(tester, {'status': 'PET_UNAVAILABLE', 'analytics': _analytics});
    expect(find.text('INBOX'), findsOneWidget);
    expect(find.text('This pet is no longer available.'), findsOneWidget);
    expect(opened()!['route'], 'inbox_fallback');
    await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
  });

  testWidgets('接口失败 / 404 → 通知中心、无 toast、不报 opened', (tester) async {
    await _pump(tester, null, throws: true);
    expect(find.text('INBOX'), findsOneWidget);
    expect(find.text('This pet is no longer available.'), findsNothing);
    expect(opened(), isNull);
  });

  test('归因：30 分钟内命中、过期不命中、换宠物不命中；push 点开每个 token 只报一次', () {
    final c = ProviderContainer();
    addTearDown(c.dispose);
    final n = c.read(healthReminderAttributionProvider.notifier);
    var t = DateTime(2026, 1, 1, 10);
    n.now = () => t;
    n.recordOpened(3, 'nid-9', 'D-D0');
    t = t.add(const Duration(minutes: 29));
    expect(n.lookup(3)?.notificationId, 'nid-9');
    expect(n.lookup(4), isNull);
    t = t.add(const Duration(minutes: 2));
    expect(n.lookup(3), isNull);
    expect(n.claimPushReport('a'), isTrue);
    expect(n.claimPushReport('a'), isFalse);
    expect(n.claimPushReport('b'), isTrue);
  });

  test('healthReminderTargetLocation：FORM 只带非空预填；PAGE 无 focus / safety 时只带 nid', () {
    final form = HealthReminderOpenResult.fromJson({
      'status': 'OK',
      'route': 'FORM',
      'prefill': {'type': 'CUSTOM', 'customName': 'Grooming', 'eventDateEmpty': false},
    });
    expect(Uri.parse(healthReminderTargetLocation(form, 't')).queryParameters,
        {'add': 'CUSTOM', 'custom': 'Grooming', 'nid': 't'});
    final page = HealthReminderOpenResult.fromJson({'status': 'OK', 'route': 'PAGE'});
    expect(healthReminderTargetLocation(page, 't'), '/profile/health?nid=t');
  });
}
