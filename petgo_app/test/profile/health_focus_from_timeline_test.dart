import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/profile/data/health_record_repository.dart';
import 'package:tailtopia/features/profile/domain/health_list_item.dart';
import 'package:tailtopia/features/profile/presentation/health_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.1.2 · Story 3.3 遗留项补齐（2026-08-04）：Diary 时间线的类④ 胶囊点进来，
/// 必须**定位到点的那一条**，而不是只跳到列表顶部。
///
/// 为什么值得单独一个文件：这条链路跨了三处（时间线注入跳转 → 路由解析 `?focus=` →
/// 列表页滚动+高亮），任一处断掉都表现为「点进去还得自己找」，而这个体感缺陷不会让任何测试变红。
class _FakeHealthRepo implements HealthRecordRepository {
  _FakeHealthRepo(this._items);
  final List<HealthListItem> _items;

  @override
  Future<List<HealthListItem>> list() async => _items;

  @override
  Future<void> create(HealthRecordDraft draft) async {}

  @override
  Future<void> update(int id, HealthRecordDraft draft) async {}

  @override
  Future<void> delete(int id) async {}
}

/// 造一串够长的记录，保证目标条目在首屏之外 —— 否则「有没有滚动」测不出来。
List<HealthListItem> _manyRecords({required int targetId}) => [
      for (var i = 1; i <= 12; i++)
        HealthListItem(
          kind: 'RECORD',
          id: i,
          editable: true,
          type: i == targetId ? 'MENSTRUATION' : 'VACCINE',
          vaccineName: 'Rabies $i',
          eventDate: DateTime(2026, 1, i),
        ),
    ];

Future<void> _pumpWithFocus(WidgetTester tester, List<HealthListItem> items, String query) async {
  // 视口刻意压矮：本用例要的就是「目标在首屏外」这个前提。
  // 宽度取 500 而非真机的 ~400：400 宽时本页条目内的 Row 会横向溢出（既有布局问题，
  // 与登录强弹窗那处同类），不在本次改动范围内。
  await tester.binding.setSurfaceSize(const Size(500, 600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  final router = GoRouter(
    initialLocation: '/profile/health$query',
    routes: [
      GoRoute(
        path: '/profile/health',
        builder: (c, s) => HealthListPage(
          presetAddType: s.uri.queryParameters['add'],
          focusRecordId: int.tryParse(s.uri.queryParameters['focus'] ?? ''),
        ),
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [healthRecordRepositoryProvider.overrideWithValue(_FakeHealthRepo(items))],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
    ),
  ));
  await tester.pumpAndSettle();
}

/// 取某条 tile 当前的底色（高亮态与常态的判据）。
Color? _tileColor(WidgetTester tester, int id) {
  final material = find.ancestor(
    of: find.byKey(ValueKey('healthTile_RECORD_$id')),
    matching: find.byType(Material),
  );
  return tester.widget<Material>(material.first).color;
}

void main() {
  testWidgets('?focus=<id> → 目标条目已滚入视口且高亮', (tester) async {
    await _pumpWithFocus(tester, _manyRecords(targetId: 11), '?focus=11');

    final target = find.byKey(const ValueKey('healthTile_RECORD_11'));
    expect(target, findsOneWidget, reason: '目标条目必须已构建（滚到了它那儿）');

    // 已滚入视口：它的位置必须落在屏幕高度内。
    final box = tester.getRect(target);
    expect(box.top, lessThan(600), reason: '还在屏幕外说明没滚过去');
    expect(box.bottom, greaterThan(0));

    expect(_tileColor(tester, 11), AppColors.mintTint, reason: '定位到的那条要看得出来是哪条');
    // 取相邻那条做对照（滚到底部后列表顶部的条目可能已被回收，拿不到）。
    expect(_tileColor(tester, 12), AppColors.card, reason: '其它条目保持常态，只高亮一条');
  });

  testWidgets('高亮是一次性的：约 2.2 秒后自动熄灭（不是选中态）', (tester) async {
    await _pumpWithFocus(tester, _manyRecords(targetId: 3), '?focus=3');
    expect(_tileColor(tester, 3), AppColors.mintTint);

    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(_tileColor(tester, 3), AppColors.card,
        reason: '常亮会被误读成「已选中」，而这里只是提示「你刚点的是这条」');
  });

  testWidgets('focus 指向不存在的记录（已被删）→ 按普通列表渲染，不报错不空屏', (tester) async {
    await _pumpWithFocus(tester, _manyRecords(targetId: 2), '?focus=999');

    expect(find.byKey(const ValueKey('healthTile_RECORD_1')), findsOneWidget);
    expect(tester.takeException(), isNull);
    expect(_tileColor(tester, 1), AppColors.card, reason: '没有任何条目该被高亮');
  });

  testWidgets('不带 focus → 行为与改动前完全一致（列表顶部，无高亮）', (tester) async {
    await _pumpWithFocus(tester, _manyRecords(targetId: 2), '');

    expect(find.byKey(const ValueKey('healthTile_RECORD_1')), findsOneWidget);
    expect(_tileColor(tester, 1), AppColors.card);
    // 首屏外的条目不该被构建（= 没有发生滚动）。
    expect(find.byKey(const ValueKey('healthTile_RECORD_12')), findsNothing);
  });

  testWidgets('问诊条目不参与定位（它没有结构化记录 id，只读）', (tester) async {
    final items = [
      HealthListItem(
          kind: 'CONSULT',
          id: 5,
          editable: false,
          type: 'CONSULT',
          symptomSummary: 'Vomiting',
          eventDate: DateTime(2026, 2, 1)),
      HealthListItem(
          kind: 'RECORD', id: 5, editable: true, type: 'VACCINE', eventDate: DateTime(2026, 2, 2)),
    ];
    await _pumpWithFocus(tester, items, '?focus=5');

    // 两者 id 都是 5，但只有结构化那条能被高亮 —— 否则会高亮错的行。
    expect(_tileColor(tester, 5), AppColors.mintTint);
    final consultMaterial = find.ancestor(
      of: find.byKey(const ValueKey('healthTile_CONSULT_5')),
      matching: find.byType(Material),
    );
    expect(tester.widget<Material>(consultMaterial.first).color, AppColors.card);
  });
}
