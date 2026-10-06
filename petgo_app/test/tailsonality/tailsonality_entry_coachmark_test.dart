import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/profile/data/onboarding_mark_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/pet_insights_page.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_repository.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/coachmark_overlay.dart';

/// V1.3.2 Story 2.7 · L0：聚合页第二次入口引导（触发条件 / 高亮位置 / 关闭置位 / 点穿卡片 / 两键独立）。
void main() {
  late _FakeMarksRepo marksRepo;

  Future<ProviderContainer> pump(WidgetTester tester, {Set<String>? server, bool fetchFails = false,
      Set<String> localMarkBeforePage = const {}}) async {
    marksRepo = _FakeMarksRepo(server ?? {}, fails: fetchFails);
    final container = ProviderContainer(retry: (_, _) => null, overrides: [
      petProfileProvider.overrideWith((ref) async => const PetProfile(id: 1, name: 'Mochi', cardToken: 't', petType: 'CAT')),
      onboardingMarkRepositoryProvider.overrideWithValue(marksRepo),
      tailsonalityRepositoryProvider.overrideWithValue(_NoResultsRepo()),
    ]);
    addTearDown(container.dispose);
    // 模拟「本会话刚在成长档案页看完第一次引导」：快照拉回后本地置位。
    await container.read(onboardingMarksProvider.future);
    for (final k in localMarkBeforePage) {
      await container.read(onboardingMarksProvider.notifier).mark(k).catchError((_) {});
    }
    final router = GoRouter(initialLocation: PetInsightsRoutes.hub, routes: [
      GoRoute(path: PetInsightsRoutes.hub, builder: (c, s) => const PetInsightsPage()),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: router,
      ),
    ));
    await tester.pumpAndSettle();
    return container;
  }

  testWidgets('老账号（快照有 ktp_moved、无 tailsonality_entry）→ 弹；高亮框与 Tailsonality 卡同矩形', (tester) async {
    await pump(tester, server: {kOnboardingMarkKtpMoved});
    expect(find.byType(CoachmarkOverlay), findsOneWidget);
    expect(find.text('Tes kepribadian anabulmu ada di sini'), findsOneWidget);
    expect(find.text('Ketuk buat mulai'), findsOneWidget);
    final overlay = tester.widget<CoachmarkOverlay>(find.byType(CoachmarkOverlay));
    final card = tester.getRect(find.byKey(const ValueKey('insightTailsonality')));
    expect(overlay.spotlight, card);
  });

  testWidgets('新账号（快照与当前都无 ktp_moved）→ 弹（D-17）', (tester) async {
    await pump(tester, server: {});
    expect(find.byType(CoachmarkOverlay), findsOneWidget);
  });

  testWidgets('本会话刚本地置位 ktp_moved（快照无）→ 本次不弹，免连弹两层', (tester) async {
    await pump(tester, server: {}, localMarkBeforePage: {kOnboardingMarkKtpMoved});
    expect(find.byType(CoachmarkOverlay), findsNothing);
  });

  testWidgets('已有 tailsonality_entry → 不弹', (tester) async {
    await pump(tester, server: {kOnboardingMarkKtpMoved, kOnboardingMarkTailsonalityEntry});
    expect(find.byType(CoachmarkOverlay), findsNothing);
  });

  testWidgets('标记读取失败 → 不弹', (tester) async {
    await pump(tester, fetchFails: true);
    expect(find.byType(CoachmarkOverlay), findsNothing);
  });

  testWidgets('点「知道了」→ 蒙层消失；状态含 tailsonality_entry 且仍含 ktp_moved；只置本键', (tester) async {
    final c = await pump(tester, server: {kOnboardingMarkKtpMoved});
    await tester.tap(find.text('Oke, paham'));
    await tester.pumpAndSettle();
    expect(find.byType(CoachmarkOverlay), findsNothing);
    final marks = c.read(onboardingMarksProvider).asData!.value;
    expect(marks, containsAll([kOnboardingMarkKtpMoved, kOnboardingMarkTailsonalityEntry]));
    expect(marksRepo.marked, [kOnboardingMarkTailsonalityEntry]);
  });

  testWidgets('蒙层显示时点卡（聚光区可点穿）→ 蒙层先消失并置位，再执行卡动作（弹说明抽屉）', (tester) async {
    final c = await pump(tester, server: {});
    expect(find.byType(CoachmarkOverlay), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('insightTailsonality')));
    await tester.pumpAndSettle();
    expect(find.byType(CoachmarkOverlay), findsNothing);
    expect(find.byKey(const ValueKey('tsIntroSheet')), findsOneWidget);
    expect(c.read(onboardingMarksProvider).asData!.value, contains(kOnboardingMarkTailsonalityEntry));
  });

  test('源码（复审）：第一次引导的入口卡在蒙层显示时先关蒙层再进聚合页；第二次引导只在本页位于最前时弹', () {
    final growth = File('lib/features/profile/presentation/growth_archive_page.dart').readAsStringSync();
    final at = growth.indexOf('onOpenIdCard:');
    final block = growth.substring(at, growth.indexOf('onOpenHealth:', at));
    expect(block, contains('if (_coachmark != null) _dismissCoachmark();'));
    expect(block.indexOf('_dismissCoachmark'), lessThan(block.indexOf('context.push(PetInsightsRoutes.hub)')));
    final hub = File('lib/features/profile/presentation/pet_insights_page.dart').readAsStringSync();
    expect(hub, contains('ModalRoute.of(context)?.isCurrent'));
  });

  test('源码：插入用 rootOverlay: true；传了 anchorKey；padding: 0；只置本引导的键', () {
    final src = File('lib/features/profile/presentation/pet_insights_page.dart').readAsStringSync();
    expect(src, contains('Overlay.of(context, rootOverlay: true).insert(entry)'));
    expect(src, contains('anchorKey: _tailsonalityAnchor'));
    expect(src, contains('padding: 0'));
    expect(src, contains('.mark(kOnboardingMarkTailsonalityEntry)'));
    expect(src, isNot(contains('.mark(kOnboardingMarkKtpMoved)')));
  });
}

class _FakeMarksRepo implements OnboardingMarkRepository {
  _FakeMarksRepo(this.server, {this.fails = false});

  final Set<String> server;
  final bool fails;
  final List<String> marked = [];

  @override
  Future<Set<String>> fetchMarks() async {
    if (fails) throw Exception('network');
    return {...server};
  }

  @override
  Future<void> mark(String key) async => marked.add(key);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _NoResultsRepo implements TailsonalityRepository {
  @override
  Future<List<TailsonalityResult>> fetchResults() async => const [];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
