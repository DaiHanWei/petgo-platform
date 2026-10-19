import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/network/problem_detail.dart';
import 'package:tailtopia/features/pet_slot/presentation/add_pet_flow.dart';
import 'package:tailtopia/features/pet_slot/presentation/pet_readonly_banner.dart';
import 'package:tailtopia/features/pet_slot/presentation/pet_readonly_block.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/health_record_repository.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/newbie_task_repository.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/domain/health_list_item.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/newbie_tasks.dart';
import 'package:tailtopia/features/profile/presentation/health_list_page.dart';
import 'package:tailtopia/features/profile/presentation/milestone_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'pet_slot_fakes.dart';

/// V1.4.0 multi-pet Story 2.4 · AC5（UI 稿 B4 / E1 ~ E3）：只读拦截 helper、续租去向、健康记录页 / 里程碑页接入。
void main() {
  late AppLocalizations l10n;
  late List<(String, Map<String, Object>?)> events;

  setUpAll(() async {
    await initializeDateFormatting('id');
    l10n = await AppLocalizations.delegate.load(const Locale('id'));
  });
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  final boba = pet(2, 'Boba', readOnly: true);
  final orenji = pet(1, 'Orenji');

  /// 挂一个能拿到 WidgetRef 的页面；[current] 为当前宠物 id。返回进入名额管理页时的完整 location。
  Future<List<String>> pumpHost(
    WidgetTester tester, {
    required List<PetListItem> pets,
    required int current,
    required void Function(BuildContext context, WidgetRef ref) onTap,
  }) async {
    final slotLocations = <String>[];
    final router = GoRouter(routes: [
      GoRoute(
        path: '/',
        builder: (_, _) => Consumer(builder: (context, ref, _) {
          ref.watch(petListProvider);
          ref.watch(effectivePetIdProvider);
          return Scaffold(
            body: Center(
              child: TextButton(
                  key: const ValueKey('tap'), onPressed: () => onTap(context, ref), child: const Text('tap')),
            ),
          );
        }),
      ),
      GoRoute(
        path: kPetSlotsRoute,
        builder: (_, s) {
          slotLocations.add(s.uri.toString());
          return const Scaffold(body: Text('slots-page'));
        },
      ),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        petListProvider.overrideWith((ref) async => pets),
        effectivePetIdProvider.overrideWithValue(AsyncData(current)),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('tap')));
    await tester.pumpAndSettle();
    return slotLocations;
  }

  group('guardPetWritable / E1', () {
    testWidgets('当前宠物只读 → 返回 false 并弹 E1（标题 / 正文带宠物名，主按钮 Perpanjang slot / 次按钮 Nanti saja）',
        (tester) async {
      final results = <bool>[];
      await pumpHost(tester, pets: [orenji, boba], current: 2,
          onTap: (context, ref) => results.add(guardPetWritable(context, ref)));
      expect(results, [false]);
      expect(find.text(l10n.petReadonlyBlockTitle('Boba')), findsOneWidget);
      expect(find.text(l10n.petReadonlyBlockBody('Boba')), findsOneWidget);
      expect(find.text(l10n.petSlotRenewSheetTitle), findsOneWidget);
      expect(find.text(l10n.pushSoftGuideLater), findsOneWidget);
      // E1 不放改选入口（2026-10-15 拍板）。
      expect(find.text(l10n.petSlotChangeSelection), findsNothing);
      await tester.tap(find.byKey(const ValueKey('petReadonlyBlockLater')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petReadonlyBlockTitle('Boba')), findsNothing);
      expect(events.where((e) => e.$1 == 'pet_readonly_renew_tapped'), isEmpty);
    });

    testWidgets('正常宠物 → 返回 true、不弹', (tester) async {
      final results = <bool>[];
      await pumpHost(tester, pets: [orenji, boba], current: 1,
          onTap: (context, ref) => results.add(guardPetWritable(context, ref)));
      expect(results, [true]);
      expect(find.text(l10n.petReadonlyBlockTitle('Orenji')), findsNothing);
    });

    testWidgets('E1「Perpanjang slot」→ 名额管理页 ?action=renew + pet_readonly_renew_tapped{source: block_sheet}',
        (tester) async {
      final locs = await pumpHost(tester, pets: [orenji, boba], current: 2,
          onTap: (context, ref) => guardPetWritable(context, ref));
      await tester.tap(find.byKey(const ValueKey('petReadonlyBlockRenew')));
      await tester.pumpAndSettle();
      expect(locs, ['$kPetSlotsRoute?action=renew']);
      expect(events.where((e) => e.$1 == 'pet_readonly_renew_tapped').map((e) => e.$2), [
        {'source': 'block_sheet'},
      ]);
    });
  });

  group('handlePetReadOnly（后端兜底）', () {
    testWidgets('pet-read-only → true + 弹 E1（名字取扩展成员 petId 对应项）', (tester) async {
      final results = <bool>[];
      await pumpHost(tester, pets: [orenji, boba], current: 1, onTap: (context, ref) {
        results.add(handlePetReadOnly(context, ref,
            ProblemDetail.fromJson({'type': 'https://petgo/errors/pet-read-only', 'status': 409, 'petId': 2})!));
      });
      expect(results, [true]);
      expect(find.text(l10n.petReadonlyBlockTitle('Boba')), findsOneWidget);
    });

    testWidgets('其它 type → false、不弹', (tester) async {
      final results = <bool>[];
      await pumpHost(tester, pets: [orenji, boba], current: 1, onTap: (context, ref) {
        results.add(handlePetReadOnly(
            context, ref, ProblemDetail.fromJson({'type': 'https://petgo/errors/validation', 'status': 422})!));
      });
      expect(results, [false]);
      expect(find.byKey(const ValueKey('petReadonlyBlockRenew')), findsNothing);
    });
  });

  testWidgets('B4 横幅「Perpanjang」→ ?action=renew + pet_readonly_renew_tapped{source: banner}', (tester) async {
    final locs = <String>[];
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, _) => const Scaffold(body: PetReadonlyBanner())),
      GoRoute(
          path: kPetSlotsRoute,
          builder: (_, s) {
            locs.add(s.uri.toString());
            return const Scaffold(body: Text('slots'));
          }),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp.router(
        routerConfig: router,
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();
    expect(events.where((e) => e.$1 == 'pet_readonly_banner_shown'), hasLength(1));
    await tester.tap(find.byKey(const ValueKey('petReadonlyBannerRenew')));
    await tester.pumpAndSettle();
    expect(locs, ['$kPetSlotsRoute?action=renew']);
    expect(events.where((e) => e.$1 == 'pet_readonly_renew_tapped').single.$2, {'source': 'banner'});
  });

  group('E3 健康记录页', () {
    Future<_HealthRepo> pumpHealth(WidgetTester tester, List<PetListItem> pets, int current) async {
      await tester.binding.setSurfaceSize(const Size(500, 2000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final repo = _HealthRepo([
        HealthListItem(kind: 'RECORD', id: 1, editable: true, type: 'VACCINE', eventDate: DateTime(2026, 9, 2)),
        HealthListItem(kind: 'RECORD', id: 2, editable: true, type: 'VACCINE', eventDate: DateTime(2026, 8, 2)),
        HealthListItem(kind: 'RECORD', id: 3, editable: true, type: 'DEWORM', eventDate: DateTime(2026, 8, 14)),
      ]);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          petListProvider.overrideWith((ref) async => pets),
          effectivePetIdProvider.overrideWithValue(AsyncData(current)),
          healthRecordRepositoryProvider.overrideWithValue(repo),
        ],
        child: const MaterialApp(
          locale: Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: HealthListPage(),
        ),
      ));
      await tester.pumpAndSettle();
      return repo;
    }

    testWidgets('多宠：标题带宠物名；分类卡仍显示最近日期（条数口径未采用，code review #5）', (tester) async {
      await pumpHealth(tester, [orenji, boba], 1);
      expect(find.text(l10n.petSlotHealthTitleNamed('Orenji')), findsOneWidget);
      expect(find.text(l10n.petReadonlyHealthCount(2)), findsNothing);
      expect(find.text('2 Sep 2026'), findsWidgets);
      expect(find.text(l10n.healthCategoryEmpty), findsNWidgets(4));
      expect(find.byKey(const ValueKey('healthAddTopLocked')), findsNothing);
    });

    testWidgets('只读：已有记录可打开查看（AD-8），保存 / 删除时才弹 E1', (tester) async {
      await pumpHealth(tester, [orenji, boba], 2);
      await tester.tap(find.byKey(const ValueKey('healthTile_RECORD_1')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petReadonlyBlockTitle('Boba')), findsNothing, reason: '查看不拦');
      expect(find.text(l10n.healthSave), findsOneWidget);
      await tester.tap(find.text(l10n.healthSave));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petReadonlyBlockTitle('Boba')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petReadonlyBlockLater')));
      await tester.pumpAndSettle();
      await tester.tap(find.text(l10n.healthDelete));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petReadonlyBlockTitle('Boba')), findsOneWidget);
    });

    testWidgets('只 1 只：标题仍是 healthListTitle', (tester) async {
      await pumpHealth(tester, [orenji], 1);
      expect(find.text(l10n.healthListTitle), findsOneWidget);
    });

    testWidgets('只读：「+」/「Tambah catatan」带锁，点了弹 E1、不开表单；分类卡点击同样拦', (tester) async {
      await pumpHealth(tester, [orenji, boba], 2);
      expect(find.byKey(const ValueKey('healthAddTopLocked')), findsOneWidget);
      expect(find.descendant(of: find.byKey(const ValueKey('healthAddBottom')), matching: find.byIcon(Icons.lock_outline)),
          findsOneWidget);
      for (final key in ['healthAddTop', 'healthAddBottom', 'healthCat_VACCINE']) {
        await tester.tap(find.byKey(ValueKey(key)));
        await tester.pumpAndSettle();
        expect(find.text(l10n.petReadonlyBlockTitle('Boba')), findsOneWidget, reason: key);
        await tester.tap(find.byKey(const ValueKey('petReadonlyBlockLater')));
        await tester.pumpAndSettle();
      }
      expect(find.text(l10n.healthAddTitle), findsOneWidget, reason: '只有吸底按钮文案，表单没开');
    });
  });

  group('E2 里程碑页', () {
    Future<void> pumpMilestones(WidgetTester tester, List<PetListItem> pets, int current) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          petListProvider.overrideWith((ref) async => pets),
          effectivePetIdProvider.overrideWithValue(AsyncData(current)),
          milestoneListProvider.overrideWith((ref, _) async => const MilestoneList(
                petName: 'Boba',
                completedCount: 0,
                totalCount: 1,
                groups: [
                  MilestoneGroup(level: MilestoneLevel.s, completedCount: 0, totalCount: 1, items: [
                    MilestoneItem(
                        code: 'C-S6',
                        title: 'x',
                        level: MilestoneLevel.s,
                        trigger: MilestoneTrigger.userCheckin,
                        completed: false),
                  ]),
                ],
              )),
          newbieTasksProvider.overrideWith((ref) async =>
              const NewbieTasks(items: [], completedCount: 6, total: 6, lulusPemulaUnlocked: true)),
        ],
        child: const MaterialApp(
          locale: Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: MilestoneListPage(),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('只读：Header 头像带锁；点未完成徽章弹 E1（不弹打卡抽屉）', (tester) async {
      await pumpMilestones(tester, [orenji, boba], 2);
      expect(find.byKey(const ValueKey('milestoneHeaderLock')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('milestoneBadge_C-S6')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petReadonlyBlockTitle('Boba')), findsOneWidget);
      expect(find.byKey(const ValueKey('milestoneCheckedIn')), findsNothing);
    });

    testWidgets('正常宠物：无锁标，点未完成徽章照旧弹详情抽屉', (tester) async {
      await pumpMilestones(tester, [orenji, boba], 1);
      expect(find.byKey(const ValueKey('milestoneHeaderLock')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('milestoneBadge_C-S6')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('milestoneCheckedIn')), findsOneWidget);
    });
  });
}

class _HealthRepo implements HealthRecordRepository {
  _HealthRepo(this.items);

  final List<HealthListItem> items;

  @override
  Future<List<HealthListItem>> list({int? petId}) async => items;

  @override
  Future<void> create(HealthRecordDraft draft, {int? petId}) async {}

  @override
  Future<void> update(int id, HealthRecordDraft draft, {int? petId}) async {}

  @override
  Future<void> delete(int id, {int? petId}) async {}
}
