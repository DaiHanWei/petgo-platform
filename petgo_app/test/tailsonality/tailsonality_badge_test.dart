import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/calendar_month.dart';
import 'package:tailtopia/features/profile/domain/pet_header_info.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_info_card.dart';
import 'package:tailtopia/features/profile/presentation/widgets/timeline_item_tile.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_providers.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_repository.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_results_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_routes.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 3.3 · L0：列表行内佩戴切换 / 卸下（AC3 · D-16）、档案卡小标（AC5）、Diary 条目（AC7）。
void main() {
  TailsonalityResult r(String token, {bool unlocked = true, bool equipped = false, int index = 1}) =>
      TailsonalityResult(
        token: token,
        typeCode: 'ENTJ-H',
        letters: 'ENTJ',
        energy: 'H',
        questionSet: 'CAT',
        resultIndex: index,
        unlocked: unlocked,
        contentVersion: 1,
        createdAt: DateTime.utc(2026, 9, 20),
        equipped: equipped,
      );

  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Widget app(Widget home, {List overrides = const []}) => ProviderScope(
        retry: (_, _) => null,
        overrides: [...overrides],
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: home,
        ),
      );

  group('列表行内切换（AC3）', () {
    Future<_BadgeRepo> pump(WidgetTester tester, _BadgeRepo repo) async {
      String? pushed;
      final router = GoRouter(initialLocation: TailsonalityRoutes.results, routes: [
        GoRoute(path: TailsonalityRoutes.results, builder: (c, s) => const TailsonalityResultsPage()),
        GoRoute(
            path: TailsonalityRoutes.resultPattern,
            builder: (c, s) {
              pushed = s.pathParameters['token'];
              return Scaffold(body: Text('result:$pushed'));
            }),
      ]);
      await tester.pumpWidget(ProviderScope(
        retry: (_, _) => null,
        overrides: [
          petProfileProvider.overrideWith((ref) async => const PetProfile(id: 1, name: 'Momo', cardToken: 't')),
          tailsonalityRepositoryProvider.overrideWithValue(repo),
          tailsonalityResultsProvider.overrideWith((ref) => repo.fetchResults()),
        ],
        child: MaterialApp.router(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          routerConfig: router,
        ),
      ));
      await tester.pumpAndSettle();
      return repo;
    }

    testWidgets('三态：Dipakai / Pakai ini / 未解锁无控件；热区 ≥44', (tester) async {
      await pump(tester, _BadgeRepo([r('a', equipped: true), r('b'), r('c', unlocked: false)]));
      expect(find.descendant(of: find.byKey(const ValueKey('tsBadgeControl_a')), matching: find.text('Dipakai')),
          findsOneWidget);
      expect(find.descendant(of: find.byKey(const ValueKey('tsBadgeControl_b')), matching: find.text('Pakai ini')),
          findsOneWidget);
      expect(find.byKey(const ValueKey('tsBadgeControl_c')), findsNothing);
      final size = tester.getSize(find.byKey(const ValueKey('tsBadgeControl_b')));
      expect(size.width, greaterThanOrEqualTo(44));
      expect(size.height, greaterThanOrEqualTo(44));
    });

    testWidgets('点「Pakai ini」→ 调接口一次、切换后仅一行 Dipakai、报 equipped（带 result_index），不进结果页',
        (tester) async {
      final repo = await pump(tester, _BadgeRepo([r('a', equipped: true, index: 1), r('b', index: 2)]));
      await tester.tap(find.byKey(const ValueKey('tsBadgeControl_b')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('tsBadgeControl_b'))); // 请求中再点：忽略
      await tester.pumpAndSettle();
      expect(repo.equipCalls, ['b']);
      expect(find.text('Dipakai'), findsOneWidget);
      expect(find.descendant(of: find.byKey(const ValueKey('tsBadgeControl_b')), matching: find.text('Dipakai')),
          findsOneWidget);
      expect(events.where((e) => e.$1 == 'tailsonality_badge_equipped').single.$2, {'result_index': 2});
      expect(find.textContaining('result:'), findsNothing, reason: '控件吃掉点击，不冒泡到行');
    });

    testWidgets('失败 → toast，状态不变、不报埋点', (tester) async {
      final repo = _BadgeRepo([r('a', equipped: true), r('b')])..fail = true;
      await pump(tester, repo);
      await tester.tap(find.byKey(const ValueKey('tsBadgeControl_b')));
      await tester.pumpAndSettle();
      expect(find.text('Gagal pasang badge, coba lagi'), findsOneWidget);
      expect(find.descendant(of: find.byKey(const ValueKey('tsBadgeControl_a')), matching: find.text('Dipakai')),
          findsOneWidget);
      expect(events.where((e) => e.$1 == 'tailsonality_badge_equipped'), isEmpty);
      await tester.pumpAndSettle(const Duration(seconds: 4));
    });

    testWidgets('D-16：点「Dipakai」→ 确认弹窗「Lepas」→ 卸下，所有行变 Pakai ini；「Batal」不调接口', (tester) async {
      final repo = await pump(tester, _BadgeRepo([r('a', equipped: true), r('b')]));
      await tester.tap(find.byKey(const ValueKey('tsBadgeControl_a')));
      await tester.pumpAndSettle();
      expect(find.text('Lepas badge?'), findsOneWidget);
      expect(find.text('Badge Momo nggak akan tampil di profil.'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tsBadgeRemoveCancel')));
      await tester.pumpAndSettle();
      expect(repo.unequipCalls, 0);

      await tester.tap(find.byKey(const ValueKey('tsBadgeControl_a')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('tsBadgeRemoveConfirm')));
      await tester.pumpAndSettle();
      expect(repo.unequipCalls, 1);
      expect(find.text('Dipakai'), findsNothing);
      expect(find.text('Pakai ini'), findsNWidgets(2));
    });

    testWidgets('点行主体仍进结果页', (tester) async {
      await pump(tester, _BadgeRepo([r('a'), r('c', unlocked: false)]));
      await tester.tap(find.byKey(const ValueKey('tsRowTapArea')).first);
      await tester.pumpAndSettle();
      expect(find.text('result:a'), findsOneWidget);
    });
  });

  group('档案卡小标（AC5.3）', () {
    testWidgets('有小标显示 4 字母胶囊；无小标不渲染', (tester) async {
      await tester.pumpWidget(app(const Scaffold(
          body: PetInfoCard(profile: PetHeaderInfo(name: 'Momo', tailsonalityBadge: 'ENTJ')))));
      expect(find.byKey(const ValueKey('petInfoTailsonality')), findsOneWidget);
      expect(find.text('ENTJ'), findsOneWidget);
      await tester.pumpWidget(app(const Scaffold(body: PetInfoCard(profile: PetHeaderInfo(name: 'Momo')))));
      expect(find.byKey(const ValueKey('petInfoTailsonality')), findsNothing);
    });

    test('PetProfile 解析并带进页头；copyWith 保留', () {
      final p = PetProfile.fromJson({'id': 1, 'name': 'Momo', 'cardToken': 't', 'tailsonalityBadge': 'ENTJ'});
      expect(p.tailsonalityBadge, 'ENTJ');
      expect(p.header.tailsonalityBadge, 'ENTJ');
      expect(p.copyWith(name: 'X').tailsonalityBadge, 'ENTJ');
      expect(PetProfile.fromJson({'id': 1, 'name': 'Momo', 'cardToken': 't'}).tailsonalityBadge, isNull);
    });
  });

  group('Diary 条目（AC7）', () {
    Map<String, dynamic> wire() => {
          'kind': 'TAILSONALITY',
          'itemType': 'TAILSONALITY_BANNER',
          'date': '2026-09-30T09:00:00Z',
          'eventDate': '2026-09-30',
          'tailsonalityResultToken': 'r' * 32,
          'tailsonalityCode': 'ENTJ-H',
          'tailsonalityTestedOn': '2026-09-01',
        };

    test('解析新类型与字段；日历新维缺键 → false', () {
      final it = TimelineItem.fromJson(wire());
      expect(it.resolvedType, TimelineItemType.tailsonalityBanner);
      expect(it.kind, TimelineKind.tailsonality);
      expect(it.tailsonalityResultToken, 'r' * 32);
      expect(it.tailsonalityCode, 'ENTJ-H');
      expect(it.tailsonalityTestedOn, DateTime.parse('2026-09-01'));
      expect(it.displayDate, DateTime.parse('2026-09-30'));
      expect(CalendarDayCell.fromJson({'day': 3}).hasTailsonality, isFalse);
      expect(CalendarDayCell.fromJson({'day': 3, 'hasTailsonality': true}).hasTailsonality, isTrue);
    });

    testWidgets('渲染代号 · 角色名 + 测试日期 + 日期槽；缺图回落占位；点击回调', (tester) async {
      var taps = 0;
      await tester.pumpWidget(app(Scaffold(
          body: TimelineItemTile(item: TimelineItem.fromJson(wire()), onTap: () => taps++))));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('timelineTailsonalityBanner')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineHappyCard')), findsNothing, reason: '不回落照片卡');
      expect(find.text('ENTJ-H · Literally CEO Banget'), findsOneWidget);
      expect(find.textContaining('Dites'), findsOneWidget);
      expect(find.text('30'), findsOneWidget, reason: '日期槽 = 解锁日');
      expect(find.byKey(const ValueKey('timelineTailsonalityThumbPlaceholder')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('timelineTailsonalityBanner')));
      expect(taps, 1);
    });
  });
}

class _BadgeRepo implements TailsonalityRepository {
  _BadgeRepo(this.items);

  List<TailsonalityResult> items;
  final List<String> equipCalls = [];
  int unequipCalls = 0;
  bool fail = false;

  static TailsonalityResult _with(TailsonalityResult x, bool equipped) => TailsonalityResult(
        token: x.token,
        typeCode: x.typeCode,
        letters: x.letters,
        energy: x.energy,
        questionSet: x.questionSet,
        resultIndex: x.resultIndex,
        unlocked: x.unlocked,
        contentVersion: x.contentVersion,
        createdAt: x.createdAt,
        equipped: equipped,
      );

  @override
  Future<List<TailsonalityResult>> fetchResults() async => items;

  @override
  Future<void> equipBadge(String resultToken) async {
    equipCalls.add(resultToken);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    if (fail) {
      throw DioException(requestOptions: RequestOptions(path: '/x'));
    }
    items = [for (final x in items) _with(x, x.token == resultToken)];
  }

  @override
  Future<void> unequipBadge() async {
    unequipCalls++;
    items = [for (final x in items) _with(x, false)];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
