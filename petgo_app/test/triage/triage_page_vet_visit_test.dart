import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_history_item.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/triage/presentation/triage_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 健康记录提醒 Story 3.5：Kesehatan 页「+ Kontrol dokter」与 Kontrol dokter 条目。
class _Repo extends ConsultRepository {
  _Repo(this.items) : super(dio: Dio());
  final List<ConsultHistoryItem> items;
  int historyCalls = 0;

  @override
  Future<ConsultSession?> active() async => null;

  @override
  Future<ConsultSession?> pendingRating() async => null;

  @override
  Future<ConsultHistoryPage> history({String? cursor, int limit = 20}) async {
    historyCalls++;
    return ConsultHistoryPage(items: items, hasMore: false);
  }
}

final _year = DateTime.now().year;

ConsultHistoryItem _vetVisit() => ConsultHistoryItem.fromJson({
      'type': 'VET_VISIT',
      'date': '2027-01-15T05:00:00Z',
      'recordId': 31,
      'petId': 2,
      'petName': 'Boba',
      'eventDate': '2027-01-15',
      'note': 'Kontrol jahitan, semua baik. Kembali dua minggu lagi untuk cek ulang.',
      'nextDate': '$_year-02-03',
      'overdue': false,
      'recordStatus': 'UPCOMING',
    });

class _H {
  _H(this.container, this.repo, this.pushed);
  final ProviderContainer container;
  final _Repo repo;
  final List<String> pushed;
}

/// 本页 AI 卡绿点常驻脉冲 → 不能 pumpAndSettle，固定帧推进。
Future<void> _frames(WidgetTester tester) async {
  for (var i = 0; i < 6; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<_H> _pump(WidgetTester tester,
    {required bool loggedIn, List<PetListItem> pets = const [], List<ConsultHistoryItem> items = const []}) async {
  SharedPreferences.setMockInitialValues({});
  tester.view.physicalSize = const Size(1170, 6000);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final repo = _Repo(items);
  final c = ProviderContainer(overrides: [
    consultRepositoryProvider.overrideWithValue(repo),
    petListProvider.overrideWith((ref) async => pets),
    effectivePetIdProvider.overrideWithValue(AsyncData(pets.isEmpty ? null : pets.first.id)),
  ]);
  addTearDown(c.dispose);
  if (loggedIn) c.read(authControllerProvider.notifier).applyVetLogin(); // 仅借 isLoggedIn=true
  await c.read(petListProvider.future);
  final pushed = <String>[];
  final router = GoRouter(initialLocation: '/triage', routes: [
    GoRoute(path: '/triage', builder: (_, _) => const TriagePage()),
    GoRoute(
        path: '/profile/health',
        builder: (_, s) {
          pushed.add(s.uri.toString());
          return const Scaffold(body: Text('HEALTH'));
        }),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
    ),
  ));
  await _frames(tester);
  return _H(c, repo, pushed);
}

Future<void> _back(WidgetTester tester) async {
  tester.state<NavigatorState>(find.byType(Navigator).last).pop();
  await _frames(tester);
}

const _mochi = PetListItem(id: 1, name: 'Mochi', petType: 'CAT');
const _boba = PetListItem(id: 2, name: 'Boba', petType: 'DOG');
final _add = find.byKey(const ValueKey('consultAddVetVisit'));

void main() {
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  group('AC1 「+ Kontrol dokter」', () {
    testWidgets('游客：不显示', (tester) async {
      await _pump(tester, loggedIn: false, pets: const [_mochi]);
      expect(_add, findsNothing);
    });

    testWidgets('已登录但没有宠物：不显示', (tester) async {
      await _pump(tester, loggedIn: true);
      expect(_add, findsNothing);
    });

    testWidgets('1 只：不弹选择器，直接 push add=VET_VISIT&from=consult；返回后重拉历史', (tester) async {
      final h = await _pump(tester, loggedIn: true, pets: const [_mochi]);
      expect(find.text('+ Vet visit'), findsOneWidget);
      final before = h.repo.historyCalls;
      await tester.tap(_add);
      await _frames(tester);
      expect(find.byKey(const ValueKey('petPickerTitle')), findsNothing);
      expect(Uri.parse(h.pushed.single).queryParameters, {'add': 'VET_VISIT', 'from': 'consult'});
      expect(events.where((e) => e.$1 == 'pet_switched'), isEmpty, reason: '同一只不报');
      await _back(tester);
      expect(h.repo.historyCalls, greaterThan(before));
    });

    testWidgets('2 只：先弹选择器；选另一只 → 切宠物 + pet_switched{consult_page} → push', (tester) async {
      final h = await _pump(tester, loggedIn: true, pets: const [_mochi, _boba]);
      await tester.tap(_add);
      await _frames(tester);
      expect(find.byKey(const ValueKey('petPickerTitle')), findsOneWidget);
      await tester.tap(find.text('Boba'));
      await _frames(tester);
      await tester.tap(find.byKey(const ValueKey('petPickerConfirm')));
      await _frames(tester);
      expect(h.container.read(currentPetIdProvider), 2);
      expect(events.where((e) => e.$1 == 'pet_switched').single.$2,
          {'from_pet_id': 1, 'to_pet_id': 2, 'source': 'consult_page'});
      expect(h.pushed.single, contains('add=VET_VISIT'));
    });

    testWidgets('2 只：选择器取消 → 什么都不做', (tester) async {
      final h = await _pump(tester, loggedIn: true, pets: const [_mochi, _boba]);
      await tester.tap(_add);
      await _frames(tester);
      await _back(tester); // 关掉抽屉 = 取消
      expect(h.pushed, isEmpty);
      expect(h.container.read(currentPetIdProvider), isNot(2));
    });
  });

  group('AC2 Kontrol dokter 条目', () {
    testWidgets('标题带宠物名、右侧记录卡格式日期、备注 2 行、状态胶囊', (tester) async {
      await _pump(tester, loggedIn: true, pets: const [_mochi, _boba], items: [_vetVisit()]);
      final tile = find.byKey(const ValueKey('historyVetVisit_31'));
      expect(tile, findsOneWidget);
      expect(find.descendant(of: tile, matching: find.text('Vet visit · Boba')), findsOneWidget);
      expect(find.descendant(of: tile, matching: find.text('15 Jan 2027')), findsOneWidget);
      final note = tester.widget<Text>(find.textContaining('Kontrol jahitan'));
      expect(note.maxLines, 2);
      expect(find.byKey(const ValueKey('healthStatus_upcoming_vv31')), findsOneWidget);
      expect(find.text('Next: 3 Feb'), findsOneWidget);
      expect(find.byKey(const ValueKey('consultHistoryEmpty')), findsNothing, reason: '只有 Kontrol dokter 也不是空态');
    });

    testWidgets('点条目 → 切到那只宠物 + push focus=<recordId>；返回后重拉', (tester) async {
      final h = await _pump(tester, loggedIn: true, pets: const [_mochi, _boba], items: [_vetVisit()]);
      final before = h.repo.historyCalls;
      await tester.tap(find.byKey(const ValueKey('historyVetVisit_31')));
      await _frames(tester);
      expect(h.container.read(currentPetIdProvider), 2);
      expect(Uri.parse(h.pushed.single).queryParameters, {'focus': '31'});
      await _back(tester);
      expect(h.repo.historyCalls, greaterThan(before));
    });

    test('fromJson：未知 type 不崩；VET_VISIT 字段解析', () {
      final unknown = ConsultHistoryItem.fromJson({'type': 'SOMETHING_NEW'});
      expect(unknown.isVetVisit, isFalse);
      final v = _vetVisit();
      expect(v.isVetVisit, isTrue);
      expect(v.recordId, 31);
      expect(v.petId, 2);
      expect(v.eventDate, DateTime(2027, 1, 15));
      expect(v.recordStatus, 'UPCOMING');
    });

    testWidgets('三类都没有 → 空态', (tester) async {
      await _pump(tester, loggedIn: true, pets: const [_mochi]);
      expect(find.byKey(const ValueKey('consultHistoryEmpty')), findsOneWidget);
    });
  });
}
