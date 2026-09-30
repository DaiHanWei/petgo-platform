import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_repository.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_quiz_layout.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_quiz_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_routes.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/tailsonality_intro_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 2.3 · L0：说明抽屉、答题页（分页 / 禁用 / 返回 / 占位图 / 间距）、提交与失败保留、埋点、不存进度。
void main() {
  group('布局常量', () {
    test('3 页 × 6 题，图片题在每页末尾，覆盖 18 题', () {
      expect(kTsQuizPages, hasLength(3));
      for (final p in kTsQuizPages) {
        expect(p, hasLength(6));
        expect(p.last, startsWith('P'));
      }
      expect(kTsQuizPages.expand((p) => p).toSet(), hasLength(18));
    });

    test('题套映射与后端一致：OTHER / 未知 → GENERAL', () {
      expect(tsQuestionSetFor('CAT'), 'CAT');
      expect(tsQuestionSetFor('DOG'), 'DOG');
      expect(tsQuestionSetFor('OTHER'), 'GENERAL');
      expect(tsQuestionSetFor(null), 'GENERAL');
    });
  });

  group('说明抽屉（AC2）', () {
    Future<void> pumpSheet(WidgetTester tester, PetProfile pet, {Locale locale = const Locale('id')}) async {
      final router = GoRouter(routes: [
        GoRoute(
          path: '/',
          builder: (c, s) => Scaffold(
            body: Builder(
              builder: (ctx) => TextButton(
                key: const ValueKey('open'),
                onPressed: () => showTailsonalityIntroSheet(ctx, pet),
                child: const Text('open'),
              ),
            ),
          ),
        ),
        GoRoute(path: TailsonalityRoutes.quiz, builder: (c, s) => const Scaffold(body: Text('quiz-page'))),
      ]);
      await tester.pumpWidget(MaterialApp.router(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: router,
      ));
      await tester.tap(find.byKey(const ValueKey('open')));
      await tester.pumpAndSettle();
    }

    testWidgets('名字 · 品种；海报缺失回落占位不崩；点开始进答题页', (tester) async {
      await pumpSheet(tester, const PetProfile(id: 1, name: 'Momo', cardToken: 't', petType: 'CAT', breed: 'Anggora'));
      expect(find.text('Tes kepribadian Momo · Anggora'), findsOneWidget);
      expect(find.text('18 pertanyaan · ± 3 menit'), findsOneWidget);
      expect(find.byKey(const ValueKey('tsIntroPosterPlaceholder')), findsOneWidget, reason: '素材未入库 → 占位');
      await tester.tap(find.byKey(const ValueKey('tsIntroStart')));
      await tester.pumpAndSettle();
      expect(find.text('quiz-page'), findsOneWidget);
      expect(find.byKey(const ValueKey('tsIntroSheet')), findsNothing);
    });

    testWidgets('无品种：猫狗回落物种名，OTHER 省略整段', (tester) async {
      await pumpSheet(tester, const PetProfile(id: 1, name: 'Momo', cardToken: 't', petType: 'DOG'));
      expect(find.text('Tes kepribadian Momo · Anjing'), findsOneWidget);
    });

    testWidgets('OTHER 且无品种 → 只有名字', (tester) async {
      await pumpSheet(tester, const PetProfile(id: 1, name: 'Kiki', cardToken: 't', petType: 'OTHER', breed: '  '));
      expect(find.text('Tes kepribadian Kiki'), findsOneWidget);
    });
  });

  group('答题页（AC3 / AC4 / AC5 / AC7）', () {
    late _FakeRepo repo;
    late List<(String, Map<String, Object>?)> events;

    setUp(() {
      repo = _FakeRepo();
      events = [];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    Future<void> pumpQuiz(WidgetTester tester, {String petType = 'CAT', bool petFails = false}) async {
      tester.view.physicalSize = const Size(900, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final router = GoRouter(initialLocation: '/start', routes: [
        GoRoute(
          path: '/start',
          builder: (c, s) => Scaffold(
            body: TextButton(onPressed: () => c.push(TailsonalityRoutes.quiz), child: const Text('go')),
          ),
        ),
        GoRoute(
            path: TailsonalityRoutes.quiz,
            builder: (c, s) => const TailsonalityQuizPage(minGenerating: Duration(milliseconds: 10))),
        GoRoute(
            path: TailsonalityRoutes.resultPattern,
            builder: (c, s) => Scaffold(body: Text('result:${s.pathParameters['token']}'))),
      ]);
      await tester.pumpWidget(ProviderScope(
        retry: (_, _) => null,
        overrides: [
          petProfileProvider.overrideWith((ref) async {
            if (petFails) throw Exception('profile');
            return PetProfile(id: 1, name: 'Momo', cardToken: 't', petType: petType);
          }),
          tailsonalityRepositoryProvider.overrideWithValue(repo),
        ],
        child: MaterialApp.router(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          routerConfig: router,
        ),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.text('go'));
      await tester.pumpAndSettle();
    }

    FilledButton primary(WidgetTester tester) => tester.widget<FilledButton>(find.byKey(const ValueKey('tsQuizPrimary')));

    Future<void> answerPage(WidgetTester tester, int page, int option) async {
      for (final q in kTsQuizPages[page]) {
        final key = q.startsWith('P') ? 'tsImageOption_${q.toLowerCase()}_$option' : 'tsOption_${q}_$option';
        await tester.ensureVisible(find.byKey(ValueKey(key)));
        await tester.tap(find.byKey(ValueKey(key)));
        await tester.pump();
      }
    }

    testWidgets('第 1 页题号 1–6、{pet} 已替换、进度 1 / 3；未答满禁用；图片题缺图回落占位且标签在', (tester) async {
      await pumpQuiz(tester);
      expect(events.map((e) => e.$1), contains('tailsonality_started'));
      expect(find.text('1 / 3'), findsOneWidget);
      expect(find.textContaining('1. Ada orang asing masuk rumah, reaksi pertama Momo'), findsOneWidget);
      expect(find.textContaining('{pet}'), findsNothing);
      expect(find.byKey(const ValueKey('tsQuestionStem_P1')), findsOneWidget);
      expect(find.textContaining('6. '), findsOneWidget);
      expect(find.byKey(const ValueKey('tsImagePlaceholder_p1_0')), findsOneWidget);
      expect(find.text('Jagain pintu depan'), findsOneWidget);
      expect(primary(tester).onPressed, isNull);

      for (final q in kTsQuizPages[0].take(5)) {
        await tester.tap(find.byKey(ValueKey('tsOption_${q}_0')));
      }
      await tester.pump();
      expect(primary(tester).onPressed, isNull, reason: '图片题没答');
      await tester.ensureVisible(find.byKey(const ValueKey('tsImageOption_p1_2')));
      await tester.tap(find.byKey(const ValueKey('tsImageOption_p1_2')));
      await tester.pump();
      expect(primary(tester).onPressed, isNotNull);
    });

    testWidgets('翻页：题号续 7–12、回上一页答案保留；OTHER 用通用题套', (tester) async {
      await pumpQuiz(tester, petType: 'OTHER');
      expect(find.textContaining('Kamu masukin tangan ke area Momo'), findsOneWidget, reason: 'GENERAL.Q1');
      await answerPage(tester, 0, 1);
      await tester.tap(find.byKey(const ValueKey('tsQuizPrimary')));
      await tester.pumpAndSettle();
      expect(find.text('2 / 3'), findsOneWidget);
      expect(find.textContaining('7. '), findsOneWidget);
      expect(find.byKey(const ValueKey('tsQuestion_Q6')), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('tsQuizBack')));
      await tester.pumpAndSettle();
      expect(find.text('1 / 3'), findsOneWidget);
      expect(primary(tester).onPressed, isNotNull, reason: '已答保留');
      expect(find.byType(AlertDialog), findsNothing);
    });

    testWidgets('档案取失败 → 给重试，不无限转圈；返回仍可退出', (tester) async {
      await pumpQuiz(tester, petFails: true);
      expect(find.byKey(const ValueKey('tsQuizPetUnavailable')), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
      await tester.tap(find.byKey(const ValueKey('tsQuizBack')));
      await tester.pumpAndSettle();
      expect(find.text('go'), findsOneWidget);
    });

    testWidgets('第 1 页：一题未答返回直接退出；有答案返回弹确认，取消留下、确认退出', (tester) async {
      await pumpQuiz(tester);
      await tester.tap(find.byKey(const ValueKey('tsQuizBack')));
      await tester.pumpAndSettle();
      expect(find.text('go'), findsOneWidget, reason: '无答案直接退出');

      await tester.tap(find.text('go'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('tsOption_Q1_0')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('tsQuizBack')));
      await tester.pumpAndSettle();
      expect(find.text('Keluar dari tes?'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('confirmSheetCancel')));
      await tester.pumpAndSettle();
      expect(find.text('1 / 3'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('tsQuizBack')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmSheetConfirm')));
      await tester.pumpAndSettle();
      expect(find.text('go'), findsOneWidget);

      // 再进来从第 1 题开始（不保存进度）。
      await tester.tap(find.text('go'));
      await tester.pumpAndSettle();
      expect(primary(tester).onPressed, isNull);
    });

    testWidgets('滚到底：最后一个选项下沿与吸底按钮上沿 ≥12px', (tester) async {
      tester.view.physicalSize = const Size(400, 800);
      await pumpQuiz(tester);
      tester.view.physicalSize = const Size(400, 800);
      await tester.pumpAndSettle();
      final pos = tester.state<ScrollableState>(find.descendant(
              of: find.byKey(const ValueKey('tsQuizList')), matching: find.byType(Scrollable)))
          .position;
      expect(pos.maxScrollExtent, greaterThan(0), reason: '小屏上内容需滚动');
      // ListView 懒构建，maxScrollExtent 是估算值：反复跳到底直到稳定。
      for (var i = 0; i < 5 && pos.pixels < pos.maxScrollExtent; i++) {
        pos.jumpTo(pos.maxScrollExtent);
        await tester.pumpAndSettle();
      }
      expect(pos.pixels, pos.maxScrollExtent);
      final lastOption = tester.getBottomLeft(find.byKey(const ValueKey('tsImageOption_p1_3'))).dy;
      final button = tester.getTopLeft(find.byKey(const ValueKey('tsQuizBottomBar'))).dy;
      expect(button - lastOption, greaterThanOrEqualTo(12));
    });

    testWidgets('提交：body 恰 18 键且值为原始下标；成功发 completed{role_code} 并替换到结果页', (tester) async {
      await pumpQuiz(tester, petType: 'DOG');
      await answerPage(tester, 0, 0);
      await tester.tap(find.byKey(const ValueKey('tsQuizPrimary')));
      await tester.pumpAndSettle();
      await answerPage(tester, 1, 3);
      await tester.tap(find.byKey(const ValueKey('tsQuizPrimary')));
      await tester.pumpAndSettle();
      await answerPage(tester, 2, 2);
      expect(find.text('Lihat Hasil'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tsQuizPrimary')));
      await tester.pump();
      expect(find.byKey(const ValueKey('tsGeneratingView')), findsOneWidget);
      expect(find.text('Menghitung 5 dimensi kepribadian Momo'), findsOneWidget);
      await tester.pumpAndSettle();

      final body = repo.bodies.single;
      expect(body.keys.toSet(), kTsQuizPages.expand((p) => p).toSet());
      expect(body['Q1'], 0);
      expect(body['P2'], 3);
      expect(body['Q15'], 2);
      expect(find.text('result:tok-1'), findsOneWidget);
      final completed = events.where((e) => e.$1 == 'tailsonality_completed').single;
      expect(completed.$2, {'role_code': 'ENTJ-H'});
      // 不带宠物名 / 品种 / 题目文本。
      expect(events.expand((e) => e.$2?.values ?? const <Object>[]), isNot(contains('Momo')));
      // 返回键回到进入答题前的页面（pushReplacement）。
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.text('go'), findsOneWidget);
    });

    testWidgets('失败：回第 3 页答案保留、给提示；重试提交同一份答案', (tester) async {
      repo.failNext = true;
      await pumpQuiz(tester);
      for (var p = 0; p < 3; p++) {
        await answerPage(tester, p, 1);
        await tester.tap(find.byKey(const ValueKey('tsQuizPrimary')));
        await tester.pumpAndSettle();
      }
      expect(find.text('3 / 3'), findsOneWidget);
      expect(find.byKey(const ValueKey('tsQuizSubmitFailed')), findsOneWidget);
      expect(find.text('Coba lagi'), findsOneWidget);
      expect(events.where((e) => e.$1 == 'tailsonality_completed'), isEmpty);

      await tester.tap(find.byKey(const ValueKey('tsQuizPrimary')));
      await tester.pumpAndSettle();
      expect(repo.bodies, hasLength(2));
      expect(repo.bodies[0], repo.bodies[1]);
      expect(find.text('result:tok-2'), findsOneWidget);
    });
  });

  test('🔴 C-5 源码扫描：答题页与分页常量不引用本地存储 / 进度字样', () {
    for (final path in [
      'lib/features/tailsonality/presentation/tailsonality_quiz_page.dart',
      'lib/features/tailsonality/domain/tailsonality_quiz_layout.dart',
    ]) {
      final src = File(path).readAsStringSync();
      for (final banned in ['SharedPreferences', 'prefs', 'Prefs', 'draft', 'Draft', 'secure_storage']) {
        expect(src.contains(banned), isFalse, reason: '$path 含 $banned');
      }
    }
  });
}

class _FakeRepo implements TailsonalityRepository {
  final List<Map<String, int>> bodies = [];
  bool failNext = false;

  @override
  Future<TailsonalityResult> submit(Map<String, int> answers) async {
    bodies.add(Map.of(answers));
    if (failNext) {
      failNext = false;
      throw Exception('boom');
    }
    return TailsonalityResult(
      token: 'tok-${bodies.length}',
      typeCode: 'ENTJ-H',
      letters: 'ENTJ',
      energy: 'H',
      questionSet: 'CAT',
      resultIndex: bodies.length,
      unlocked: false,
      contentVersion: 1,
      createdAt: DateTime.utc(2026, 9, 30),
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
