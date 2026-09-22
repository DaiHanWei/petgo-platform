import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/profile/data/health_record_repository.dart';
import 'package:tailtopia/features/profile/domain/health_list_item.dart';
import 'package:tailtopia/features/profile/presentation/health_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假健康记录 repo：list 返固定混排；记录 create 次数。
class _FakeHealthRepo implements HealthRecordRepository {
  _FakeHealthRepo(this._items);
  final List<HealthListItem> _items;
  int createCalls = 0;
  HealthRecordDraft? lastDraft;

  @override
  Future<List<HealthListItem>> list() async => _items;

  @override
  Future<void> create(HealthRecordDraft draft) async {
    createCalls++;
    lastDraft = draft;
  }

  @override
  Future<void> update(int id, HealthRecordDraft draft) async {}

  @override
  Future<void> delete(int id) async {}
}

Future<_FakeHealthRepo> _pump(WidgetTester tester, List<HealthListItem> items) async {
  // 0711 改版后本页含 KATEGORI 网格 + 列表 + 底部按钮，纵向内容变高；
  // ListView 懒 layout 会让 fold 外 widget 不构建 → 用高视口让全部内容在视口内可被 finder 命中。
  await tester.binding.setSurfaceSize(const Size(500, 2000));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  final repo = _FakeHealthRepo(items);
  await tester.pumpWidget(ProviderScope(
    overrides: [healthRecordRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: HealthListPage(),
    ),
  ));
  await tester.pumpAndSettle();
  return repo;
}

void main() {
  final mixed = [
    HealthListItem(
        kind: 'CONSULT', id: 9, editable: false, type: 'CONSULT',
        symptomSummary: 'Vomiting observed', aiLevel: 'GREEN', eventDate: DateTime(2024, 6, 1)),
    HealthListItem(
        kind: 'RECORD', id: 1, editable: true, type: 'VACCINE',
        vaccineName: 'Rabies', eventDate: DateTime(2024, 3, 1)),
  ];

  testWidgets('混排渲染：结构化可点(chevron) + 问诊只读(🏥 badge)', (tester) async {
    await _pump(tester, mixed);
    expect(find.byKey(const ValueKey('healthTile_CONSULT_9')), findsOneWidget);
    expect(find.byKey(const ValueKey('healthTile_RECORD_1')), findsOneWidget);
    expect(find.text('· from consultation'), findsOneWidget); // 问诊只读标识
    // 结构化项有 chevron（可编辑），问诊项无。
    expect(find.descendant(
        of: find.byKey(const ValueKey('healthTile_RECORD_1')),
        matching: find.byIcon(Icons.chevron_right)), findsOneWidget);
    expect(find.descendant(
        of: find.byKey(const ValueKey('healthTile_CONSULT_9')),
        matching: find.byIcon(Icons.chevron_right)), findsNothing);
  });

  testWidgets('空态引导', (tester) async {
    await _pump(tester, const []);
    expect(find.textContaining('No health records yet'), findsOneWidget);
  });

  testWidgets('点 Add → 表单弹出 → 保存调 create', (tester) async {
    final repo = await _pump(tester, const []);
    // FAB 已改为底部整宽「Tambah catatan」按钮（0711）。
    await tester.tap(find.byKey(const ValueKey('healthAddBottom')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('healthTypeDropdown')), findsOneWidget);

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(repo.createCalls, 1);
    expect(repo.lastDraft?.type, 'VACCINE'); // 默认类型
  });

  testWidgets('问诊只读项点击不弹编辑表单', (tester) async {
    await _pump(tester, mixed);
    await tester.tap(find.byKey(const ValueKey('healthTile_CONSULT_9')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('healthTypeDropdown')), findsNothing); // 未弹表单
  });

  testWidgets('bug 497: 点问诊分类卡直接跳 /triage（取代 bug 428 的来源说明 toast）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(500, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final router = GoRouter(
      initialLocation: '/health',
      routes: [
        GoRoute(path: '/health', builder: (_, _) => const HealthListPage()),
        GoRoute(
            path: '/triage',
            builder: (_, _) => const Scaffold(body: Text('TRIAGE_STUB'))),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [healthRecordRepositoryProvider.overrideWithValue(_FakeHealthRepo(mixed))],
      child: MaterialApp.router(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        routerConfig: router,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('healthCat_CONSULT')));
    await tester.pumpAndSettle();
    expect(find.text('TRIAGE_STUB'), findsOneWidget); // 已跳问诊
    expect(find.byKey(const ValueKey('healthTypeDropdown')), findsNothing); // 不弹添加表单
    expect(
        find.text(
            'Consultation records are added automatically when you archive a consultation result'),
        findsNothing); // 不再弹 toast
  });
}
