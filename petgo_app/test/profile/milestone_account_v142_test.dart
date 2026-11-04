import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/profile/data/account_celebration_pets.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/milestone_celebration_reporter.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/newbie_task_repository.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/celebration_pet.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_account_codes.dart';
import 'package:tailtopia/features/profile/domain/milestone_badge_assets.dart';
import 'package:tailtopia/features/profile/domain/milestone_catchup.dart';
import 'package:tailtopia/features/profile/domain/milestone_celebration_copy.dart';
import 'package:tailtopia/features/profile/domain/newbie_tasks.dart';
import 'package:tailtopia/features/profile/presentation/milestone_list_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_badge.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_celebration.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_species_avatar.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.2 social-topic Story 2.3（FR-154 · R-4 · UI B1–B4）：账号级里程碑（第一次关注 / 第一次收藏）
/// 的 App 展示与补庆祝（L0）。视觉还原度（B1–B4 ≥ 95%）为 L2，本地模拟器验。
const _friendCodes = ['C-S19', 'D-S19', 'G-S12'];
const _saveCodes = ['C-S20', 'D-S20', 'G-S13'];

MilestoneItem _ms(String code, MilestoneLevel level, {bool celebrated = false}) => MilestoneItem(
      code: code,
      title: code,
      level: level,
      trigger: MilestoneTrigger.systemAuto,
      completed: true,
      completedAt: DateTime(2026, 6, 1),
      celebratedAt: celebrated ? DateTime.utc(2026, 6, 2) : null,
    );

PetListItem _pet(int id, String name, {String status = PetListItem.statusActive, String petType = 'CAT'}) =>
    PetListItem(id: id, name: name, petType: petType, status: status);

/// V1.4.2 Story 3.2：庆祝层屏外预挂了里程碑卡（同样画宠物头像 / 名字 / 标题）→ 庆祝页可见内容的断言限定在内容区内。
Finder _inView(Finder f) => find.descendant(of: find.byKey(const ValueKey('milestoneCelebration')), matching: f);

void main() {
  // ===== T1 账号级编码判定 =====
  group('T1 kAccountMilestoneCodes', () {
    test('恰 6 个，== kMilestoneBadgeKeys 中 first_friend / first_save 的 key 集合', () {
      expect(kAccountMilestoneCodes, hasLength(6));
      final fromBadges = {
        for (final e in kMilestoneBadgeKeys.entries)
          if (e.value == 'first_friend' || e.value == 'first_save') e.key,
      };
      expect(kAccountMilestoneCodes, fromBadges);
    });

    test('🔴 精确匹配，不按后缀：G-S19 / G-S20 / C-S12 不是账号级', () {
      for (final c in [..._friendCodes, ..._saveCodes]) {
        expect(isAccountMilestone(c), isTrue, reason: c);
      }
      for (final c in ['G-S19', 'G-S20', 'C-S12', 'D-S13', 'C-S18', '', 'c-s19']) {
        expect(isAccountMilestone(c), isFalse, reason: c);
      }
    });

    test('源码扫描：判定文件不做字符串切分 / 正则', () {
      final code = File('lib/features/profile/domain/milestone_account_codes.dart')
          .readAsLinesSync()
          .where((l) => !l.trimLeft().startsWith('//'))
          .join('\n');
      for (final banned in ['substring(', 'split(', 'endsWith(', 'startsWith(', 'RegExp(']) {
        expect(code, isNot(contains(banned)), reason: banned);
      }
    });

    test('与后端 AccountMilestoneFlag 同源：后端映射表登记的就是这 6 个编码', () {
      final f = File('../petgo-backend/src/main/java/com/tailtopia/profile/domain/MilestoneAutoCompleteMap.java');
      if (!f.existsSync()) return; // 单独 checkout App 时跳过
      final src = f.readAsStringSync();
      for (final c in kAccountMilestoneCodes) {
        expect(src, contains('"$c"'), reason: c);
      }
    });
  });

  // ===== AC1 徽章映射与回落 =====
  group('AC1 徽章映射（按完整编码）', () {
    tearDown(() => MilestoneBadgeAssets.debugOverride = null);

    test('C/D-S19、G-S12 → first_friend；C/D-S20、G-S13 → first_save', () {
      for (final c in _friendCodes) {
        expect(milestoneBadgeKeyOf(c), 'first_friend', reason: c);
      }
      for (final c in _saveCodes) {
        expect(milestoneBadgeKeyOf(c), 'first_save', reason: c);
      }
    });

    testWidgets('素材未到（空集）→ 回落；放进 first_friend.webp → 出图', (tester) async {
      MilestoneBadgeAssets.debugOverride = const {};
      await tester.pumpWidget(const MaterialApp(
          home: Scaffold(body: MilestoneBadge(code: 'G-S12', size: 64, level: MilestoneLevel.s))));
      expect(find.byIcon(Icons.emoji_events_rounded), findsOneWidget);
      expect(find.byType(Image), findsNothing);

      MilestoneBadgeAssets.debugOverride = {'assets/milestone/first_friend.webp'};
      for (final c in _friendCodes) {
        await tester.pumpWidget(MaterialApp(home: Scaffold(body: MilestoneBadge(code: c, size: 64))));
        final img = tester.widget<Image>(find.byKey(ValueKey('milestoneBadgeArt_$c')));
        expect((img.image as AssetImage).assetName, 'assets/milestone/first_friend.webp', reason: c);
      }
      // first_save 仍未到 → 回落（两枚素材互不牵连）。
      await tester.pumpWidget(
          const MaterialApp(home: Scaffold(body: MilestoneBadge(code: 'C-S20', size: 64))));
      expect(find.byType(Image), findsNothing);
    });
  });

  // ===== AC2 文案 =====
  group('AC2 文案（不含 {name}）', () {
    test('6 条 id / en 四字段与 PRD §7 一致，均不含 {name}', () {
      for (final c in _friendCodes) {
        final copy = kMilestoneCelebrationCopy[c]!;
        expect(copy.titleId, 'Teman pertama! 🤝', reason: c);
        expect(copy.bodyId, 'Kamu mulai mengikuti seseorang — makin seru bareng sesama pecinta anabul.', reason: c);
        expect(copy.titleEn, 'First friend! 🤝', reason: c);
        expect(copy.bodyEn, 'You followed someone for the first time — more fun with fellow pet lovers.', reason: c);
      }
      for (final c in _saveCodes) {
        final copy = kMilestoneCelebrationCopy[c]!;
        expect(copy.titleId, 'Simpan pertama! 🔖', reason: c);
        expect(copy.bodyId, 'Postingan pertama sudah tersimpan — buka lagi kapan saja.', reason: c);
        expect(copy.titleEn, 'First save! 🔖', reason: c);
        expect(copy.bodyEn, 'Your first saved post — come back to it anytime.', reason: c);
      }
      for (final c in kAccountMilestoneCodes) {
        final copy = kMilestoneCelebrationCopy[c]!;
        for (final s in [copy.titleId, copy.bodyId, copy.titleEn, copy.bodyEn]) {
          expect(s, isNot(contains('{name}')), reason: c);
        }
      }
    });

    test('localizedMilestoneCelebration 传了宠物名也不拼名', () {
      expect(localizedMilestoneCelebration('D-S19', const Locale('id'), 'Momo').title, 'Teman pertama! 🤝');
      expect(localizedMilestoneCelebration('G-S13', const Locale('en'), 'Momo').body,
          'Your first saved post — come back to it anytime.');
    });
  });

  // ===== AC3 resolveCatchup =====
  group('AC3 两枚同时待庆祝只弹一条', () {
    test('两枚账号级同时未庆祝 → 弹一条、报两枚', () {
      final r = resolveCatchup([_ms('C-S19', MilestoneLevel.s), _ms('C-S20', MilestoneLevel.s)]);
      expect(r.toCelebrate!.code, 'C-S19');
      expect(r.codesToReport, ['C-S19', 'C-S20']);
    });

    test('同时有未庆祝 M → 弹 M、报三枚', () {
      final r = resolveCatchup([
        _ms('C-S19', MilestoneLevel.s),
        _ms('C-M8', MilestoneLevel.m),
        _ms('C-S20', MilestoneLevel.s),
      ]);
      expect(r.toCelebrate!.code, 'C-M8');
      expect(r.codesToReport.toSet(), {'C-S19', 'C-M8', 'C-S20'});
    });

    test('关注 / 收藏按钮不加任何庆祝调用（源码扫描）', () {
      for (final path in [
        'lib/features/social/presentation/widgets/follow_button.dart',
        'lib/features/collect/presentation/collect_button.dart',
      ]) {
        final src = File(path).readAsStringSync();
        for (final banned in ['showMilestoneCelebration', 'showInstantMilestoneCelebration', 'milestone_celebration']) {
          expect(src, isNot(contains(banned)), reason: '$path: $banned');
        }
      }
    });
  });

  // ===== AC6 庆祝页 B1 / B2 =====
  group('AC6 庆祝页 B1 / B2', () {
    setUp(StartupPopupLedger.instance.resetForTest);

    Future<void> open(
      WidgetTester tester,
      MilestoneItem item, {
      List<CelebrationPet> pets = const [],
      Locale locale = const Locale('en'),
      List<MilestoneItem>? collection,
    }) async {
      await tester.binding.setSurfaceSize(const Size(500, 1400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      // V1.4.2 Story 3.2：庆祝页弹出时取 ProviderContainer（D-21）→ 宿主需在 ProviderScope 下。
      await tester.pumpWidget(ProviderScope(child: MaterialApp(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: Builder(
            builder: (context) => Center(
              child: ElevatedButton(
                onPressed: () => showMilestoneCelebration(context, item,
                    petName: 'Momo',
                    path: MilestoneCelebrationPath.catchup,
                    collection: collection ?? [item, _ms('C-S1', MilestoneLevel.s, celebrated: true)],
                    accountPets: pets),
                child: const Text('go'),
              ),
            ),
          ),
        ),
      )));
      await tester.tap(find.text('go'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
    }

    String collectionLabel(Locale l) => lookupAppLocalizations(l).milestoneCelebrateCollection('Momo').toUpperCase();

    testWidgets('B1：账号级 + 1 宠 → emoji 胶囊 + KOLEKSI，标题「Teman pertama! 🤝」无宠物名（id）', (tester) async {
      const id = Locale('id');
      await open(tester, _ms('C-S19', MilestoneLevel.s),
          pets: const [CelebrationPet(name: 'Momo', petType: 'CAT')], locale: id);
      expect(_inView(find.text('Teman pertama! 🤝')), findsOneWidget);
      expect(_inView(find.text('Kamu mulai mengikuti seseorang — makin seru bareng sesama pecinta anabul.')), findsOneWidget,
          reason: '标题 / 正文不含宠物名');
      expect(_inView(find.text('🐱')), findsOneWidget);
      expect(_inView(find.text('Momo · 1 Juni 2026')), findsOneWidget);
      expect(find.textContaining(collectionLabel(id)), findsOneWidget);
      expect(find.byKey(const ValueKey('milestoneCelebrationPetRow')), findsNothing);
    });

    testWidgets('B2：账号级 + 3 宠 → 3 个头像 + 名字、无 KOLEKSI、胶囊只有日期（en）', (tester) async {
      const en = Locale('en');
      await open(tester, _ms('D-S20', MilestoneLevel.s), pets: const [
        CelebrationPet(name: 'Momo', petType: 'CAT'),
        CelebrationPet(name: 'Bobby', petType: 'DOG'),
        CelebrationPet(name: 'Kiki', petType: 'OTHER'),
      ]);
      expect(_inView(find.text('First save! 🔖')), findsOneWidget);
      expect(_inView(find.byType(PetSpeciesAvatar)), findsNWidgets(3));
      expect(_inView(find.byType(PetSpeciesAvatar)), findsNWidgets(3));
      for (final n in ['Momo', 'Bobby', 'Kiki']) {
        expect(_inView(find.text(n)), findsOneWidget);
      }
      final avatar = tester.widget<PetSpeciesAvatar>(_inView(find.byType(PetSpeciesAvatar)).first);
      expect(avatar.size, 40);
      final ring = tester.widget<Container>(find.ancestor(
          of: _inView(find.byType(PetSpeciesAvatar)).first, matching: find.byType(Container)).first);
      final deco = ring.decoration! as BoxDecoration;
      expect(deco.color, AppColors.splashInk);
      expect(ring.padding, const EdgeInsets.all(2));
      expect(deco.boxShadow!.single.color, Colors.white.withValues(alpha: 0.25));
      final row = tester.widget<Wrap>(find.byKey(const ValueKey('milestoneCelebrationPetRow')));
      expect(row.spacing, 12);
      // 胶囊只写日期。
      final chip = find.byKey(const ValueKey('milestoneCelebrationDateChip'));
      expect(find.descendant(of: chip, matching: find.text('1 June 2026')), findsOneWidget);
      expect(find.descendant(of: chip, matching: find.byType(Text)), findsOneWidget);
      expect(find.descendant(of: chip, matching: find.text('🐶')), findsNothing, reason: '胶囊无物种 emoji');
      expect(find.textContaining(collectionLabel(en)), findsNothing);
    });

    testWidgets('B2：5 宠上限（传 6 只只画 5 只）', (tester) async {
      await open(tester, _ms('G-S12', MilestoneLevel.s), pets: [
        for (var i = 0; i < 6; i++) CelebrationPet(name: 'P$i', petType: 'CAT'),
      ]);
      expect(_inView(find.byType(PetSpeciesAvatar)), findsNWidgets(5));
      expect(_inView(find.text('P5')), findsNothing);
    });

    testWidgets('B2：名字超长单行省略', (tester) async {
      await open(tester, _ms('C-S19', MilestoneLevel.s), pets: const [
        CelebrationPet(name: 'Supercalifragilistic Momo', petType: 'CAT'),
        CelebrationPet(name: 'Bobby', petType: 'DOG'),
      ]);
      final t = tester.widget<Text>(_inView(find.text('Supercalifragilistic Momo')));
      expect(t.maxLines, 1);
      expect(t.overflow, TextOverflow.ellipsis);
      expect(t.style!.fontSize, 11);
    });

    for (final locale in const [Locale('id'), Locale('en')]) {
      testWidgets('非账号级编码（多宠也不变）→ 现有行为（${locale.languageCode}）', (tester) async {
        await open(tester, _ms('C-S5', MilestoneLevel.s), locale: locale, pets: const [
          CelebrationPet(name: 'Momo', petType: 'CAT'),
          CelebrationPet(name: 'Bobby', petType: 'DOG'),
        ]);
        expect(find.byKey(const ValueKey('milestoneCelebrationPetRow')), findsNothing);
        expect(_inView(find.text('🐱')), findsOneWidget);
        expect(find.textContaining('Momo · 1 '), findsOneWidget);
        expect(find.textContaining(collectionLabel(locale)), findsOneWidget);
      });
    }
  });

  // ===== readAccountCelebrationPets =====
  group('readAccountCelebrationPets', () {
    test('只取 ACTIVE、保持列表顺序、最多 5 只', () async {
      final c = ProviderContainer(overrides: [
        petListProvider.overrideWith((ref) async => [
              _pet(1, 'A'),
              _pet(2, 'RO', status: PetListItem.statusReadOnly),
              for (var i = 3; i <= 8; i++) _pet(i, 'P$i'),
            ]),
      ]);
      addTearDown(c.dispose);
      final pets = await readAccountCelebrationPets(c);
      expect(pets.map((p) => p.name), ['A', 'P3', 'P4', 'P5', 'P6']);
    });

    test('读失败 → 空表（退化为 B1）', () async {
      final c = ProviderContainer(overrides: [
        petListProvider.overrideWith((ref) async => throw StateError('boom')),
      ]);
      addTearDown(c.dispose);
      expect(await readAccountCelebrationPets(c), isEmpty);
    });
  });

  // ===== AC5 列表页补庆祝按账号 =====
  group('AC5 补庆祝按账号', () {
    tearDown(() => Analytics.debugCaptureSink = null);

    MilestoneList listOf(List<MilestoneItem> s) => MilestoneList(
          petName: 'Momo',
          completedCount: s.length,
          totalCount: 35,
          groups: [MilestoneGroup(level: MilestoneLevel.s, completedCount: s.length, totalCount: 20, items: s)],
        );

    Future<(ProviderContainer, _CountingRepo, List<String>)> pumpList(
      WidgetTester tester, {
      required _CountingRepo repo,
      int petId = 1,
      List<PetListItem>? pets,
      int Function()? statsCounter,
      GlobalKey<NavigatorState>? navKey,
      bool coverBeforeData = false,
    }) async {
      final seen = <String>[];
      Analytics.debugCaptureSink = (e, p) {
        if (e == 'milestone_celebration_shown') seen.add(p?['code'] as String);
      };
      final container = ProviderContainer(overrides: [
        effectivePetIdProvider.overrideWithValue(AsyncData(petId)),
        milestoneRepositoryProvider.overrideWithValue(repo),
        petListProvider.overrideWith((ref) async => pets ?? [_pet(1, 'Momo'), _pet(2, 'Bobby', petType: 'DOG')]),
        newbieTasksProvider.overrideWith((ref) async =>
            const NewbieTasks(items: [], completedCount: 6, total: 6, lulusPemulaUnlocked: true)),
        archiveStatsProvider.overrideWith((ref, id) async {
          repo.statsFetches++;
          return const ArchiveStats(
              happyMomentCount: 0, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 35);
        }),
      ]);
      addTearDown(container.dispose);
      await tester.binding.setSurfaceSize(const Size(500, 2600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          navigatorKey: navKey,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const MilestoneListPage(),
        ),
      ));
      // 数据回来之前就被别的页盖住（模拟「去发布 → 发到社区」后新发布页叠在里程碑列表上）。
      if (coverBeforeData) {
        navKey!.currentState!.push(MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Text('cover'))));
      }
      await tester.pump();
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      return (container, repo, seen);
    }

    testWidgets('两枚账号级未庆祝 → 补弹一次（B2 多宠）、回报两枚；回报后整族失效（另一只宠物的列表与角标也重拉）',
        (tester) async {
      final repo = _CountingRepo(listOf([_ms('C-S19', MilestoneLevel.s), _ms('C-S20', MilestoneLevel.s)]));
      final (container, _, seen) = await pumpList(tester, repo: repo);
      expect(seen, ['C-S19']);
      expect(find.byKey(const ValueKey('milestoneCelebrationPetRow')), findsOneWidget, reason: '2 只 ACTIVE → B2');
      // 另一只宠物的列表与角标缓存在场（档案 Tab 读过）。
      final sub2 = container.listen(milestoneListProvider(2), (_, _) {});
      final subStats = container.listen(archiveStatsProvider(2), (_, _) {});
      addTearDown(sub2.close);
      addTearDown(subStats.close);
      await tester.pump();
      final fetches2 = repo.fetchesFor(2);
      final stats = repo.statsFetches;

      await tester.tap(find.byKey(const ValueKey('milestoneCelebrationClose')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pump();
      expect(repo.reported, [
        ['C-S19', 'C-S20'],
      ]);
      expect(repo.fetchesFor(2), greaterThan(fetches2), reason: '整族失效：其他宠物的列表也重拉');
      expect(repo.statsFetches, greaterThan(stats), reason: '档案头未庆祝角标也重拉');
    });

    testWidgets('账号级 + 未庆祝 M → 弹 M、报三枚', (tester) async {
      final repo = _CountingRepo(MilestoneList(
        petName: 'Momo',
        completedCount: 3,
        totalCount: 35,
        groups: [
          MilestoneGroup(
              level: MilestoneLevel.m, completedCount: 1, totalCount: 2, items: [_ms('C-M8', MilestoneLevel.m)]),
          MilestoneGroup(
              level: MilestoneLevel.s,
              completedCount: 2,
              totalCount: 20,
              items: [_ms('C-S19', MilestoneLevel.s), _ms('C-S20', MilestoneLevel.s)]),
        ],
      ));
      final (_, _, seen) = await pumpList(tester, repo: repo);
      expect(seen, ['C-M8']);
      expect(find.byKey(const ValueKey('milestoneCelebrationPetRow')), findsNothing, reason: 'M 级不是账号级');
      await tester.tap(find.byKey(const ValueKey('milestoneCelebrationClose')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(repo.reported.single.toSet(), {'C-M8', 'C-S19', 'C-S20'});
    });

    testWidgets('复审 3-2 (b)：本页被盖住时不补弹；盖住的页关掉、本页重新成为栈顶 → 照常补弹一次', (tester) async {
      final nav = GlobalKey<NavigatorState>();
      final repo = _CountingRepo(listOf([_ms('C-S5', MilestoneLevel.s)]));
      final (_, _, seen) = await pumpList(tester, repo: repo, navKey: nav, coverBeforeData: true);
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.text('cover'), findsOneWidget);
      expect(seen, isEmpty, reason: '被盖住不弹');
      expect(find.byKey(const ValueKey('milestoneCelebration')), findsNothing);
      nav.currentState!.pop();
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(seen, ['C-S5'], reason: '重新成为栈顶 → 补弹');
      expect(find.byKey(const ValueKey('milestoneCelebration')), findsOneWidget);
    });

    testWidgets('账号级 + 1 只 ACTIVE（另一只只读）→ B1', (tester) async {
      final repo = _CountingRepo(listOf([_ms('C-S19', MilestoneLevel.s)]));
      await pumpList(tester,
          repo: repo, pets: [_pet(1, 'Momo'), _pet(2, 'Bobby', status: PetListItem.statusReadOnly)]);
      expect(find.byKey(const ValueKey('milestoneCelebrationPetRow')), findsNothing);
      expect(_inView(find.text('🐱')), findsOneWidget);
    });

    /// 🔴 AC5.3（复审 CONFIRMED-1）：走真实路径 —— 猫列表补弹 C-S19、回报在途（未落库），
    /// 切到狗宠物、新列表页读到 D-S19 仍未庆祝 → 不得再弹（本地集合按同 flag 三物种全集记）。
    testWidgets('混养账号：猫列表补弹 C-S19、回报在途 → 切到狗列表读到 D-S19 未庆祝也不弹', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final inFlight = Completer<void>();
      addTearDown(() {
        if (!inFlight.isCompleted) inFlight.complete();
      });
      final repo = _CountingRepo(listOf([_ms('C-S19', MilestoneLevel.s)]),
          byPet: {2: listOf([_ms('D-S19', MilestoneLevel.s)])}, report: () => inFlight.future);
      final container = ProviderContainer(overrides: [
        milestoneRepositoryProvider.overrideWithValue(repo),
        petListProvider.overrideWith((ref) async => [_pet(1, 'Momo'), _pet(2, 'Bobby', petType: 'DOG')]),
        newbieTasksProvider.overrideWith((ref) async =>
            const NewbieTasks(items: [], completedCount: 6, total: 6, lulusPemulaUnlocked: true)),
      ]);
      addTearDown(container.dispose);
      await container.read(currentPetIdProvider.notifier).select(1);
      final seen = <String>[];
      Analytics.debugCaptureSink = (e, p) {
        if (e == 'milestone_celebration_shown') seen.add(p?['code'] as String);
      };
      await tester.binding.setSurfaceSize(const Size(500, 2600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      Widget page(Key k) => UncontrolledProviderScope(
            container: container,
            child: MaterialApp(
              localizationsDelegates: AppLocalizations.localizationsDelegates,
              supportedLocales: AppLocalizations.supportedLocales,
              home: MilestoneListPage(key: k),
            ),
          );
      Future<void> settle() async {
        for (var i = 0; i < 4; i++) {
          await tester.pump();
        }
        await tester.pump(const Duration(milliseconds: 400));
      }

      await tester.pumpWidget(page(const ValueKey('cat')));
      await settle();
      expect(seen, ['C-S19']);
      await tester.tap(find.byKey(const ValueKey('milestoneCelebrationClose')));
      await settle();
      expect(repo.reported, [
        ['C-S19'],
      ], reason: '回报已发出，但尚未落库（in-flight）');

      await container.read(currentPetIdProvider.notifier).select(2);
      await tester.pumpWidget(page(const ValueKey('dog')));
      await settle();
      expect(repo.fetchesFor(2), greaterThan(0), reason: '狗列表确实读到了 D-S19 未庆祝');
      expect(seen, ['C-S19'], reason: 'D-S19 与 C-S19 是同一次账号级庆祝，不得再弹');
    });

    testWidgets('重温防重入：连点两次已完成的账号级徽章只弹一个庆祝页', (tester) async {
      final repo = _CountingRepo(listOf([_ms('C-S19', MilestoneLevel.s, celebrated: true)]));
      final (_, _, seen) = await pumpList(tester, repo: repo);
      expect(seen, isEmpty);
      final badge = find.byKey(const ValueKey('milestoneBadge_C-S19'));
      await tester.tap(badge);
      await tester.tap(badge, warnIfMissed: false);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(seen, ['C-S19']);
      expect(find.byKey(const ValueKey('milestoneCelebration')), findsOneWidget);
    });
  });

  group('本地已庆祝集合按三物种全集记 / 撤', () {
    test('accountSiblings / expandAccountSiblings', () {
      expect(accountSiblings('D-S19'), {'C-S19', 'D-S19', 'G-S12'});
      expect(accountSiblings('G-S13'), {'C-S20', 'D-S20', 'G-S13'});
      expect(accountSiblings('C-M8'), {'C-M8'});
      expect(expandAccountSiblings(['C-S19', 'C-M8']), {'C-S19', 'D-S19', 'G-S12', 'C-M8'});
    });

    test('回报失败 → 撤回整个全集（下次照常补弹）', () async {
      final fail = Completer<void>();
      final repo = _CountingRepo(
          const MilestoneList(petName: 'Momo', completedCount: 0, totalCount: 0, groups: []),
          report: () => fail.future);
      final c = ProviderContainer(overrides: [
        milestoneRepositoryProvider.overrideWithValue(repo),
        petListProvider.overrideWith((ref) async => const <PetListItem>[]),
      ]);
      addTearDown(c.dispose);
      final notifier = c.read(locallyCelebratedMilestonesProvider.notifier);
      notifier.report(['C-S19', 'C-M8']);
      expect(c.read(locallyCelebratedMilestonesProvider), {'C-S19', 'D-S19', 'G-S12', 'C-M8'});
      fail.completeError(StateError('500'));
      await Future<void>.delayed(Duration.zero);
      await Future<void>.delayed(Duration.zero);
      expect(c.read(locallyCelebratedMilestonesProvider), isEmpty);
    });
  });
}

/// 按 petId 计数拉取次数的假仓库。
class _CountingRepo implements MilestoneRepository {
  _CountingRepo(this.list, {this.byPet = const {}, this.report});

  final MilestoneList list;

  /// 按 petId 返回不同清单（未登记的取 [list]）。
  final Map<int, MilestoneList> byPet;

  /// 回报的完成时机（默认立即成功）。
  final Future<void> Function()? report;
  final _fetches = <int?, int>{};
  int statsFetches = 0;
  final reported = <List<String>>[];

  int fetchesFor(int petId) => _fetches[petId] ?? 0;

  @override
  Future<MilestoneList> getMilestones({int? petId}) async {
    _fetches[petId] = (_fetches[petId] ?? 0) + 1;
    return byPet[petId] ?? list;
  }

  @override
  Future<void> reportCelebrations(List<String> codes, {int? petId}) async {
    reported.add(codes);
    if (report != null) await report!();
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
