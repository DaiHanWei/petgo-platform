import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/health_record_repository.dart';
import 'package:tailtopia/features/profile/domain/health_list_item.dart';
import 'package:tailtopia/features/profile/presentation/health_list_page.dart';
import 'package:tailtopia/shared/widgets/design/online_pulse_dot.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';

/// 假健康记录 repo：list 返固定混排；记录 create 次数。
class _FakeHealthRepo implements HealthRecordRepository {
  _FakeHealthRepo(this._items);
  final List<HealthListItem> _items;
  int createCalls = 0;
  HealthRecordDraft? lastDraft;

  @override
  Future<List<HealthListItem>> list({int? petId}) async => _items;

  @override
  Future<int?> create(HealthRecordDraft draft, {int? petId}) async {
    createCalls++;
    lastDraft = draft;
    return 100 + createCalls;
  }

  @override
  Future<void> update(int id, HealthRecordDraft draft, {int? petId}) async {}

  @override
  Future<void> delete(int id, {int? petId}) async {}

  @override
  Future<HealthRecordPreview> preview(HealthRecordPreviewRequest request, {int? petId}) async =>
      const HealthRecordPreview();
}

/// list 恒抛指定 DioException（无档案 404 / 5xx），记录调用次数以断言是否被自动重试。
class _ThrowingHealthRepo extends _FakeHealthRepo {
  _ThrowingHealthRepo(this.error) : super(const []);
  final DioException error;
  int listCalls = 0;

  @override
  Future<List<HealthListItem>> list({int? petId}) async {
    listCalls++;
    throw error;
  }
}

DioException _httpError(int status) {
  final req = RequestOptions(path: '/api/v1/pet-profiles/me/health-records');
  return DioException(
    requestOptions: req,
    type: DioExceptionType.badResponse,
    response: Response<dynamic>(requestOptions: req, statusCode: status),
  );
}

Future<_FakeHealthRepo> _pump(WidgetTester tester, List<HealthListItem> items) async {
  // 0711 改版后本页含 KATEGORI 网格 + 列表 + 底部按钮，纵向内容变高；
  // ListView 懒 layout 会让 fold 外 widget 不构建 → 用高视口让全部内容在视口内可被 finder 命中。
  await tester.binding.setSurfaceSize(const Size(500, 2000));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  final repo = _FakeHealthRepo(items);
  await tester.pumpWidget(ProviderScope(
    overrides: [effectivePetIdProvider.overrideWithValue(const AsyncData(1)), healthRecordRepositoryProvider.overrideWithValue(repo)],
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


/// V1.5.0 Story 3.1：带路由（/triage 占位）与自定义页面参数的挂载。
Future<_FakeHealthRepo> _pumpRouted(
  WidgetTester tester,
  List<HealthListItem> items, {
  HealthListPage page = const HealthListPage(),
  bool settle = true,
}) async {
  await tester.binding.setSurfaceSize(const Size(500, 2400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  final repo = _FakeHealthRepo(items);
  final router = GoRouter(
    initialLocation: '/health',
    routes: [
      GoRoute(path: '/health', builder: (_, _) => page),
      GoRoute(path: '/triage', builder: (_, _) => const Scaffold(body: Text('TRIAGE_STUB'))),
    ],
  );
  addTearDown(router.dispose);
  await tester.pumpWidget(ProviderScope(
    overrides: [effectivePetIdProvider.overrideWithValue(const AsyncData(1)), healthRecordRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp.router(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      routerConfig: router,
    ),
  ));
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }
  return repo;
}

/// 埋点收集（测试结束自动卸下）。
List<(String, Map<String, Object>?)> _captureEvents() {
  final events = <(String, Map<String, Object>?)>[];
  Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  addTearDown(() => Analytics.debugCaptureSink = null);
  return events;
}

Finder _tiles() => find.byWidgetPredicate(
    (w) => w is InkWell && w.key is ValueKey<String> && (w.key! as ValueKey<String>).value.startsWith('healthTile_'));

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
      overrides: [effectivePetIdProvider.overrideWithValue(const AsyncData(1)), healthRecordRepositoryProvider.overrideWithValue(_FakeHealthRepo(mixed))],
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
    expect(find.text('TRIAGE_STUB'), findsOneWidget); // 已跳问诊（V1.5.0 FR-172：Konsultasi 卡不筛选）
    expect(find.byKey(const ValueKey('healthTypeDropdown')), findsNothing); // 不弹添加表单
    expect(
        find.text(
            'Consultation records are added automatically when you archive a consultation result'),
        findsNothing); // 不再弹 toast
  });

  testWidgets('无宠物档案 404 → 不自动重试、立即显示「先建档」空态', (tester) async {
    final repo = _ThrowingHealthRepo(_httpError(404));
    await tester.pumpWidget(ProviderScope(
      overrides: [effectivePetIdProvider.overrideWithValue(const AsyncData(1)), healthRecordRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: HealthListPage(),
      ),
    ));
    await tester.pump(); // future 完成 → 直接进错误态，不经重试等待
    expect(find.text('Create a pet profile first to keep health records.'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);

    // 推进远超默认重试总时长（~15s+），确认没有在后台再请求。
    await tester.pump(const Duration(seconds: 30));
    expect(repo.listCalls, 1);
  });

  test('healthListRetry：4xx 不重试；5xx / 网络错误仍走默认重试', () {
    expect(healthListRetry(0, _httpError(404)), isNull);
    expect(healthListRetry(0, _httpError(403)), isNull);
    expect(healthListRetry(0, _httpError(500)), isNotNull);
    expect(
        healthListRetry(
            0,
            DioException(
                requestOptions: RequestOptions(path: '/x'),
                type: DioExceptionType.connectionError)),
        isNotNull);
    // 默认策略的上限保持：第 10 次后不再重试。
    expect(healthListRetry(10, _httpError(500)), isNull);
  });

  // ===================== V1.5.0 Story 3.1 =====================

  group('V1.5.0 3.1 AC1 模型', () {
    test('fromJson：新字段解析；缺字段 = null / done；未知状态 → done', () {
      final full = HealthListItem.fromJson({
        'kind': 'RECORD', 'id': 5, 'editable': true, 'type': 'VACCINE', 'eventDate': '2026-03-01',
        'vaccinePreset': 'F3', 'dose': 2, 'dewormType': null, 'productDurationMonths': null,
        'nextDate': '2026-04-01', 'displayNextDate': '2026-04-01', 'recordStatus': 'DUE_SOON',
        'nextDateLocked': true,
      });
      expect(full.vaccinePreset, 'F3');
      expect(full.dose, 2);
      expect(full.nextDate, DateTime(2026, 4, 1));
      expect(full.displayNextDate, DateTime(2026, 4, 1));
      expect(full.recordStatus, HealthRecordStatus.dueSoon);
      expect(full.nextDateLocked, isTrue);

      final legacy = HealthListItem.fromJson({'kind': 'RECORD', 'id': 6, 'editable': true, 'type': 'DEWORM'});
      expect(legacy.recordStatus, HealthRecordStatus.done);
      expect(legacy.displayNextDate, isNull);
      expect(legacy.nextDateLocked, isFalse);
      expect(HealthRecordStatus.parse('SOMETHING_NEW'), HealthRecordStatus.done);
    });

    test('recordTypes：Kontrol dokter 在 Lainnya 之前', () {
      const t = HealthListPage.recordTypes;
      expect(t, contains('VET_VISIT'));
      expect(t.indexOf('VET_VISIT'), t.indexOf('CUSTOM') - 1);
    });
  });

  group('V1.5.0 3.1 AC2 记录卡', () {
    final year = DateTime.now().year;
    final items = [
      HealthListItem(
          kind: 'RECORD', id: 1, editable: true, type: 'VACCINE', vaccineName: 'F3 (Tricat)',
          vaccinePreset: 'F3', dose: 2, eventDate: DateTime(2024, 3, 1),
          displayNextDate: DateTime(year, 1, 28), recordStatus: HealthRecordStatus.dueSoon),
      HealthListItem(
          kind: 'RECORD', id: 2, editable: true, type: 'DEWORM', dewormType: 'INTERNAL',
          eventDate: DateTime(2024, 2, 1), recordStatus: HealthRecordStatus.overdue),
      HealthListItem(
          kind: 'RECORD', id: 3, editable: true, type: 'VET_VISIT', eventDate: DateTime(2024, 1, 5),
          displayNextDate: DateTime(year + 1, 2, 3), recordStatus: HealthRecordStatus.upcoming),
      HealthListItem(
          kind: 'RECORD', id: 4, editable: true, type: 'VACCINE', vaccineName: 'Vaksin X',
          vaccinePreset: 'OTHER', eventDate: DateTime(2023, 12, 9)),
      HealthListItem(kind: 'RECORD', id: 6, editable: true, type: 'DEWORM', eventDate: DateTime(2023, 11, 9)),
    ];

    testWidgets('日期在右上角（d MMM yyyy）；标题拼预设 / 剂次 / 驱虫类型；Kontrol dokter 类型行写 Konsultasi', (tester) async {
      await _pump(tester, items);
      final date = find.byKey(const ValueKey('healthTileDate_RECORD_1'));
      expect(tester.widget<Text>(date).data, '1 Mar 2024');
      // 右上角：日期与类型标签同一行（纵向居上），且在类型标签右侧。
      final typeLabel = find.descendant(
          of: find.byKey(const ValueKey('healthTile_RECORD_1')), matching: find.text('Vaccine'));
      expect((tester.getCenter(date).dy - tester.getCenter(typeLabel).dy).abs(), lessThan(2));
      expect(tester.getCenter(date).dx, greaterThan(tester.getCenter(typeLabel).dx));

      expect(find.text('Vaccine · F3 (Tricat) · Dose 2'), findsOneWidget);
      expect(find.text('Deworming · Internal (worms)'), findsOneWidget);
      expect(find.text('Vaccine · Vaksin X'), findsOneWidget, reason: 'OTHER 预设 → 原文');
      expect(find.descendant(of: find.byKey(const ValueKey('healthTile_RECORD_6')), matching: find.text('Deworming')),
          findsNWidgets(2), reason: '无驱虫类型 → 标题只写 Obat Cacing（类型行 + 标题各一）');
      final vet = find.byKey(const ValueKey('healthTile_RECORD_3'));
      expect(find.descendant(of: vet, matching: find.text('Consultation')), findsOneWidget);
      expect(find.descendant(of: vet, matching: find.text('Vet visit')), findsOneWidget);
    });

    testWidgets('状态胶囊：overdue / dueSoon / upcoming 各有图标 + 文字；done 无胶囊；本年不写年、跨年写年', (tester) async {
      await _pump(tester, items);
      final overdue = find.byKey(const ValueKey('healthStatus_overdue_2'));
      expect(find.descendant(of: overdue, matching: find.text('Past due')), findsOneWidget);
      expect(find.descendant(of: overdue, matching: find.byIcon(Icons.error_outline_rounded)), findsOneWidget);
      final soon = find.byKey(const ValueKey('healthStatus_dueSoon_1'));
      expect(find.descendant(of: soon, matching: find.text('Next: 28 Jan')), findsOneWidget);
      expect(find.descendant(of: soon, matching: find.byIcon(Icons.alarm_rounded)), findsOneWidget);
      final up = find.byKey(const ValueKey('healthStatus_upcoming_3'));
      expect(find.descendant(of: up, matching: find.text('Next: 3 Feb ${year + 1}')), findsOneWidget);
      expect(find.descendant(of: up, matching: find.byIcon(Icons.calendar_today_rounded)), findsOneWidget);
      expect(find.byKey(const ValueKey('healthStatus_done_4')), findsNothing);
      expect(find.byWidgetPredicate((w) => w is Container && '${w.key}'.contains('healthStatus_') && '${w.key}'.contains('_4')),
          findsNothing);
    });

    testWidgets('问诊只读卡按 done 显示（无胶囊）；疫苗 / 驱虫卡下不再有商品入口行', (tester) async {
      await _pump(tester, [...mixed, ...items]);
      expect(find.byWidgetPredicate((w) => w is Container && '${w.key}'.contains('healthStatus_') && '${w.key}'.endsWith('_9]>')),
          findsNothing);
      expect(find.byIcon(Icons.storefront_outlined), findsNothing);
      expect(find.byWidgetPredicate((w) => '${w.key}'.contains('healthCategoryJump_')), findsNothing);
    });
  });

  group('V1.5.0 3.1 AC3 AppBar 与分类卡', () {
    testWidgets('AppBar 只留返回与标题（无问诊图标、无「+」）', (tester) async {
      await _pump(tester, mixed);
      expect(find.byKey(const ValueKey('healthStartConsult')), findsNothing);
      expect(find.byKey(const ValueKey('healthAddTop')), findsNothing);
      expect(find.byKey(const ValueKey('healthAddTopLocked')), findsNothing);
      final appBar = tester.widget<AppBar>(find.byType(AppBar));
      expect(appBar.actions, anyOf(isNull, isEmpty));
      expect(find.byKey(const ValueKey('healthAddBottom')), findsOneWidget, reason: '底部按钮保留');
    });

    testWidgets('Konsultasi 卡：固定副标题、不显示日期；绿点；点击跳 /triage 并报 health_consult_card_tapped', (tester) async {
      final events = _captureEvents();
      await _pumpRouted(tester, mixed);
      final card = find.byKey(const ValueKey('healthCat_CONSULT'));
      expect(find.descendant(of: card, matching: find.text('Symptoms · vet')), findsOneWidget);
      expect(find.descendant(of: card, matching: find.text('1 Jun 2024')), findsNothing);
      expect(find.descendant(of: card, matching: find.byType(OnlinePulseDot)), findsOneWidget);
      await tester.tap(card);
      await tester.pumpAndSettle();
      expect(find.text('TRIAGE_STUB'), findsOneWidget);
      expect(events.where((e) => e.$1 == 'health_consult_card_tapped').single.$2, {'pet_id': 1});
      expect(events.where((e) => e.$1 == 'health_category_filtered'), isEmpty, reason: 'Konsultasi 不筛选');
    });

    testWidgets('绿点产品口径恒为 pulsing: true（测试默认关以便 pumpAndSettle）', (tester) async {
      HealthListPage.debugConsultPulse = true;
      addTearDown(() => HealthListPage.debugConsultPulse = false);
      await _pumpRouted(tester, mixed, settle: false);
      final dot = tester.widget<OnlinePulseDot>(find.byKey(const ValueKey('healthConsultPulse')));
      expect(dot.pulsing, isTrue);
      await tester.pumpWidget(const SizedBox()); // 卸载，停掉 repeat 动画
    });

    testWidgets('类型名「Berahi / Heat」（原 Siklus Berahi / Heat cycle）', (tester) async {
      await _pump(tester, const []);
      expect(find.descendant(of: find.byKey(const ValueKey('healthCat_MENSTRUATION')), matching: find.text('Heat')),
          findsOneWidget);
      expect(find.text('Heat cycle'), findsNothing);
    });
  });

  group('V1.5.0 3.1 AC4 分类筛选', () {
    final ac64 = [
      HealthListItem(kind: 'CONSULT', id: 9, editable: false, type: 'CONSULT',
          symptomSummary: 'Vomiting observed', eventDate: DateTime(2024, 6, 1)),
      HealthListItem(kind: 'RECORD', id: 1, editable: true, type: 'VACCINE', eventDate: DateTime(2024, 5, 1)),
      HealthListItem(kind: 'RECORD', id: 2, editable: true, type: 'VACCINE', eventDate: DateTime(2024, 4, 1)),
      HealthListItem(kind: 'RECORD', id: 3, editable: true, type: 'DEWORM', eventDate: DateTime(2024, 3, 1)),
      HealthListItem(kind: 'RECORD', id: 4, editable: true, type: 'VET_VISIT', eventDate: DateTime(2024, 2, 1)),
    ];

    testWidgets('AC64：点 Vaksin 只剩 2 条 + 标题 + See all；再点 / See all 回到 5 条；点 Konsultasi 不筛选', (tester) async {
      final events = _captureEvents();
      await _pumpRouted(tester, ac64);
      expect(_tiles(), findsNWidgets(5));
      expect(find.text('ALL RECORDS · LATEST'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('healthCat_VACCINE')));
      await tester.pumpAndSettle();
      expect(_tiles(), findsNWidgets(2));
      expect(find.byKey(const ValueKey('healthTile_RECORD_1')), findsOneWidget);
      expect(find.byKey(const ValueKey('healthTile_RECORD_2')), findsOneWidget);
      expect(find.text('VACCINE · LATEST'), findsOneWidget);
      expect(find.text('See all'), findsOneWidget);
      // 选中卡：2px mint 描边。
      final selected = tester.widget<Container>(find.descendant(
          of: find.byKey(const ValueKey('healthCat_VACCINE')), matching: find.byType(Container)).first);
      final border = (selected.decoration! as BoxDecoration).border! as Border;
      expect(border.top.width, 2);
      expect(find.byKey(const ValueKey('healthTypeDropdown')), findsNothing, reason: '点卡不再开表单');

      await tester.tap(find.byKey(const ValueKey('healthCat_VACCINE'))); // 再点同一张 = 清除
      await tester.pumpAndSettle();
      expect(_tiles(), findsNWidgets(5));
      expect(find.text('ALL RECORDS · LATEST'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('healthCat_VACCINE')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('healthFilterClear'))); // See all = 清除
      await tester.pumpAndSettle();
      expect(_tiles(), findsNWidgets(5));

      // AC68 埋点口径：每次「施加」报一次，清除不报。
      final filtered = events.where((e) => e.$1 == 'health_category_filtered').toList();
      expect(filtered, hasLength(2));
      expect(filtered.first.$2, {'pet_id': 1, 'category': 'vaccine', 'record_count': 2});

      await tester.tap(find.byKey(const ValueKey('healthCat_CONSULT')));
      await tester.pumpAndSettle();
      expect(find.text('TRIAGE_STUB'), findsOneWidget);
    });

    testWidgets('AC65：Sterilisasi 无记录 → 筛选空态、不开表单；宠物完全没记录时点卡同样是筛选空态', (tester) async {
      await _pump(tester, mixed);
      await tester.tap(find.byKey(const ValueKey('healthCat_NEUTER')));
      await tester.pumpAndSettle();
      expect(find.text('No records in this category yet.'), findsOneWidget);
      expect(find.byKey(const ValueKey('healthTypeDropdown')), findsNothing);
    });

    testWidgets('AC65 补：完全无记录 → healthListEmpty；点卡 → 筛选空态', (tester) async {
      await _pump(tester, const []);
      expect(find.byKey(const ValueKey('healthListEmpty')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('healthCat_CUSTOM')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('healthFilterEmpty')), findsOneWidget);
      expect(find.byKey(const ValueKey('healthListEmpty')), findsNothing);
    });

    testWidgets('AC66：有筛选时 Tambah catatan 预选该类型；无筛选默认 Vaksin 且保存后仍无筛选', (tester) async {
      await _pump(tester, ac64);
      await tester.tap(find.byKey(const ValueKey('healthCat_DEWORM')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('healthAddBottom')));
      await tester.pumpAndSettle();
      expect(tester.widget<DropdownButtonFormField<String>>(find.byKey(const ValueKey('healthTypeDropdown'))).initialValue,
          'DEWORM');
      Navigator.of(tester.element(find.byKey(const ValueKey('healthTypeDropdown')))).pop();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('healthFilterClear')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('healthAddBottom')));
      await tester.pumpAndSettle();
      expect(tester.widget<DropdownButtonFormField<String>>(find.byKey(const ValueKey('healthTypeDropdown'))).initialValue,
          'VACCINE');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(find.text('ALL RECORDS · LATEST'), findsOneWidget, reason: '无筛选保存后保持无筛选');
    });

    Future<void> saveWithType(WidgetTester tester, String typeLabel) async {
      await tester.tap(find.byKey(const ValueKey('healthAddBottom')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('healthTypeDropdown')));
      await tester.pumpAndSettle();
      await tester.tap(find.text(typeLabel).last);
      await tester.pumpAndSettle();
      if (typeLabel == 'Custom') {
        await tester.enterText(find.byType(TextField).first, 'Grooming');
      }
      if (typeLabel == 'Deworming') {
        // V1.5.0 Story 3.2：驱虫类型必填（AC4），先选一个。
        await tester.tap(find.byKey(const ValueKey('healthDewormDropdown')));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Internal (worms)').last);
        await tester.pumpAndSettle();
      }
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
    }

    testWidgets('AC67：Vaksin 筛选中改成 Obat Cacing 保存 → 筛选切到 Obat Cacing', (tester) async {
      final repo = await _pump(tester, ac64);
      await tester.tap(find.byKey(const ValueKey('healthCat_VACCINE')));
      await tester.pumpAndSettle();
      await saveWithType(tester, 'Deworming');
      expect(repo.lastDraft?.type, 'DEWORM');
      expect(find.text('DEWORMING · LATEST'), findsOneWidget);
      expect(find.byKey(const ValueKey('healthTile_RECORD_3')), findsOneWidget);
    });

    testWidgets('AC4.4：筛选中保存 Kontrol dokter（无对应分类卡）→ 清除筛选', (tester) async {
      final repo = await _pump(tester, ac64);
      await tester.tap(find.byKey(const ValueKey('healthCat_VACCINE')));
      await tester.pumpAndSettle();
      await saveWithType(tester, 'Vet visit');
      expect(repo.lastDraft?.type, 'VET_VISIT');
      expect(find.text('ALL RECORDS · LATEST'), findsOneWidget);
      expect(_tiles(), findsNWidgets(5));
    });

    testWidgets('AC68：带 focus 进入 → 无筛选、定位目标条', (tester) async {
      await _pumpRouted(tester, ac64, page: const HealthListPage(focusRecordId: 3));
      await tester.pump(const Duration(seconds: 3)); // 走完 2.2s 高亮
      expect(find.text('ALL RECORDS · LATEST'), findsOneWidget);
      expect(_tiles(), findsNWidgets(5));
      expect(find.byKey(const ValueKey('healthFilterClear')), findsNothing);
    });
  });

  group('V1.5.0 3.1 AC5 提醒落页参数', () {
    testWidgets('safety=PERMETHRIN,PREGNANT → 两个警示框在分类卡上方、怀孕在前；带 add 也不自动弹表单', (tester) async {
      await _pumpRouted(tester, mixed,
          page: const HealthListPage(presetAddType: 'DEWORM', safety: ['PERMETHRIN', 'PREGNANT', 'UNKNOWN']));
      final preg = find.byKey(const ValueKey('healthSafety_PREGNANT'));
      final perm = find.byKey(const ValueKey('healthSafety_PERMETHRIN'));
      expect(preg, findsOneWidget);
      expect(perm, findsOneWidget);
      expect(find.byKey(const ValueKey('healthSafety_UNKNOWN')), findsNothing);
      expect(find.text('Some medicines can harm unborn or nursing young.'), findsOneWidget);
      expect(find.textContaining('permethrin are deadly to cats'), findsOneWidget);
      expect(tester.getTopLeft(preg).dy, lessThan(tester.getTopLeft(perm).dy));
      expect(tester.getTopLeft(perm).dy, lessThan(tester.getTopLeft(find.byKey(const ValueKey('healthCat_VACCINE'))).dy));
      expect(find.byKey(const ValueKey('healthTypeDropdown')), findsNothing, reason: '有警示框不自动弹表单');
      // 底部按钮仍可开表单。
      await tester.tap(find.byKey(const ValueKey('healthAddBottom')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('healthTypeDropdown')), findsOneWidget);
    });

    testWidgets('无 safety + add → 照旧自动弹表单，预填自定义名透传给表单', (tester) async {
      await _pumpRouted(tester, mixed,
          page: const HealthListPage(presetAddType: 'CUSTOM', prefill: HealthRecordPrefill(customName: 'Grooming')));
      expect(find.byKey(const ValueKey('healthTypeDropdown')), findsOneWidget);
      expect(find.widgetWithText(TextField, 'Grooming'), findsOneWidget);
    });
  });
}
