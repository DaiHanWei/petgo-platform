import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/growth/presentation/growth_labels.dart';
import 'package:tailtopia/features/profile/data/account_milestone_repository.dart';
import 'package:tailtopia/features/profile/data/milestone_celebration_reporter.dart';
import 'package:tailtopia/features/profile/data/newbie_task_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_account_codes.dart';
import 'package:tailtopia/features/profile/domain/milestone_celebration_copy.dart';
import 'package:tailtopia/features/profile/domain/milestone_compose_preset.dart';
import 'package:tailtopia/features/profile/domain/newbie_tasks.dart';
import 'package:tailtopia/features/profile/presentation/account_milestones_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：V1.5.0 onboarding-recall Story 3.3 —— Lulus Pemula 账号级庆祝（文案 / 账号级编码 / E4 分享预设 /
/// 毕业通知落点 / 账号级页补弹 + 回报 / 成长值明细）。视觉 E1 ~ E4 还原度为 L2。
class _Repo implements AccountMilestoneRepository {
  _Repo(this.data);
  AccountMilestones data;
  final List<List<String>> reported = [];

  @override
  Future<AccountMilestones> fetch() async => data;

  @override
  Future<int> reportCelebrated(List<String> codes) async {
    reported.add(codes);
    data = AccountMilestones(items: [
      for (final i in data.items)
        AccountMilestoneEntry(flagKey: i.flagKey, code: i.code, completed: i.completed, completedAt: i.completedAt),
    ], completedCount: data.completedCount, totalCount: data.totalCount);
    return 1;
  }
}

AccountMilestones _withLulus({required bool pending}) => AccountMilestones(items: [
      const AccountMilestoneEntry(flagKey: 'FIRST_FOLLOW_USER', code: 'C-S19', completed: true),
      const AccountMilestoneEntry(flagKey: 'FIRST_COLLECT_CONTENT', code: 'C-S20', completed: true),
      AccountMilestoneEntry(
          flagKey: 'LULUS_PEMULA',
          code: 'C-S16',
          completed: true,
          completedAt: DateTime.utc(2026, 11, 15, 3),
          pendingCelebration: pending),
    ], completedCount: 3, totalCount: 3);

const _doneTasks = NewbieTasks(items: [], completedCount: 7, total: 7, lulusPemulaUnlocked: true, hasPet: false);

void main() {
  test('Lulus 三编码并入账号级，兄弟编码一组', () {
    for (final c in ['C-S16', 'D-S16', 'G-S9']) {
      expect(isAccountMilestone(c), isTrue);
      expect(isLulusPemulaCode(c), isTrue);
      expect(accountSiblings(c), {'C-S16', 'D-S16', 'G-S9'});
    }
    expect(isLulusPemulaCode('C-S19'), isFalse);
  });

  test('庆祝文案三物种同一句、不带宠物名（ID / EN 逐字）', () {
    for (final c in ['C-S16', 'D-S16', 'G-S9']) {
      final id = localizedMilestoneCelebration(c, const Locale('id'), 'Orenji');
      expect(id.title, 'Lulus Pemula! 🎓');
      expect(id.body, 'Semua Tugas Pemula beres! Selamat datang resmi di geng TailTopia.');
      final en = localizedMilestoneCelebration(c, const Locale('en'), 'Orenji');
      expect(en.title, 'Beginner graduate! 🎓');
      expect(en.body, 'All Beginner Tasks done! Welcome to the TailTopia gang, officially.');
      expect(en.body, isNot(contains('Orenji')));
    }
  });

  test('E4：没有正常宠物 → 预选 Moment、不预选宠物、预填无宠物句', () async {
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    const item = MilestoneItem(
        code: 'G-S9', title: 't', level: MilestoneLevel.s, trigger: MilestoneTrigger.systemAuto, completed: true);
    final r = milestoneComposePreset(
        item: item, petId: 0, petName: '', accountPets: const [], l10n: l10n, locale: const Locale('id'));
    expect(r.moment, isTrue);
    expect(r.petIds, isEmpty);
    expect(r.text, startsWith('Yeay, aku baru buka tonggak "'));
    expect(r.text, endsWith('"! 🎉'));
  });

  test('毕业通知落点：MILESTONE_NODE / MILESTONE_SM_NODE + Lulus 编码 → 分发路由优先补弹；其余不变', () {
    expect(DeepLinkRoutes.pushPayloadToLocation('MILESTONE_NODE', 'C-S16'), '/me/milestones-entry?celebrate=lulus');
    expect(DeepLinkRoutes.pushPayloadToLocation('MILESTONE_SM_NODE', 'G-S9'), '/me/milestones-entry?celebrate=lulus');
    expect(DeepLinkRoutes.pushPayloadToLocation('MILESTONE_NODE', 'C-L1'), DeepLinkRoutes.milestoneList);
    expect(DeepLinkRoutes.pushPayloadToLocation('MILESTONE_SM_NODE', 'C-S19'), DeepLinkRoutes.notificationsCenter);
  });

  test('成长值明细 NEWBIE_GRADUATED：文案 + 毕业帽图标', () async {
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(GrowthLabels.ruleName(l10n, 'NEWBIE_GRADUATED'), 'Lulus Pemula');
    expect(GrowthLabels.ruleIcon('NEWBIE_GRADUATED'), Icons.school_rounded);
    final e = GrowthLedgerEntry(ruleCode: 'NEWBIE_GRADUATED', delta: 20, createdAt: DateTime.utc(2026, 11, 15));
    expect(GrowthLabels.ledgerTitle(l10n, e), 'Lulus Pemula');
  });

  Future<_Repo> pumpAccountPage(WidgetTester tester, {required bool pending, Set<String> local = const {}}) async {
    final repo = _Repo(_withLulus(pending: pending));
    tester.view.physicalSize = const Size(1080, 4000);
    tester.view.devicePixelRatio = 2.75;
    addTearDown(tester.view.reset);
    final container = ProviderContainer(overrides: [
      accountMilestoneRepositoryProvider.overrideWithValue(repo),
      newbieTasksProvider.overrideWith((ref) async => _doneTasks),
    ]);
    addTearDown(container.dispose);
    if (local.isNotEmpty) container.read(locallyCelebratedMilestonesProvider.notifier).add(local.toList());
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        locale: Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: AccountMilestonesPage(celebrate: 'lulus'),
      ),
    ));
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    return repo;
  }

  testWidgets('账号级页：Lulus 待补弹 → 弹一次（E4 布局无宠物行），关闭后回报账号端点', (tester) async {
    final repo = await pumpAccountPage(tester, pending: true);
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsOneWidget);
    expect(find.text('Lulus Pemula! 🎓'), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneCelebrationPetRow')), findsNothing);
    expect(repo.reported, isEmpty, reason: '展示成功（关闭）后才回报');
    await tester.pump(const Duration(seconds: 2));
    await tester.tap(find.byKey(const ValueKey('milestoneCelebrationClose')));
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pump(const Duration(milliseconds: 600));
    expect(repo.reported, [
      ['C-S16']
    ]);
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsNothing);
  });

  testWidgets('账号级页：已庆祝 / 本机已弹过 → 不弹', (tester) async {
    await pumpAccountPage(tester, pending: false);
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsNothing);
  });

  testWidgets('账号级页：本机已弹过 Lulus → 不再弹', (tester) async {
    await pumpAccountPage(tester, pending: true, local: kLulusPemulaCodes);
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsNothing);
  });
}
