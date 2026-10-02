import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/pet_insights_page.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_repository.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_routes.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.0 批次 A · Story 5.1（L0）：综合入口聚合页与 KTP 平移（FR-65 · AD-A17 / AD-A24）。
///
/// 本 story 含**一条安全攸关约束**（AC5：游客门控只升不降），所以这里钉得比别处狠一点：
/// 不只验"新路由拦得住"，还反向验"没有人为了放行它去动例外集合"。
void main() {
  final String routerSrc = File('lib/core/router/app_router.dart').readAsStringSync();

  group('AC5 🔴 安全攸关：游客门控只升不降', () {
    /// 新路由落在 `/profile/` 下 → 自动继承既有前缀门控，一行安全代码都不用写。
    test('两条新路径都在 /profile/ 前缀下', () {
      expect(PetInsightsRoutes.hub, startsWith('/profile/'));
      expect(PetInsightsRoutes.idCard, startsWith('/profile/'));
      expect(PetInsightsRoutes.ageCard, startsWith('/profile/'));
    });

    /// 🔴 **例外集合一字未改**。为放行某个子页把 `/profile/xxx` 塞进去，
    /// 等于把「默认拦截」这条安全默认反转 —— 路由表注释里的三条硬约束之一。
    test('门控例外集合仍然只有 /profile 一条', () {
      final block = RegExp(r'_controlledExactExceptions = \{(.*?)\}', dotAll: true)
          .firstMatch(routerSrc)!
          .group(1)!;
      expect(block.trim(), "'/profile'");
      expect(block, isNot(contains('pet-insights')));
      expect(block, isNot(contains('age-card')));
      expect(block, isNot(contains('tailsonality')));
    });

    /// 受控前缀集合本身也不该被动（比如有人把 /profile 挪出去"顺便"放行子页）。
    test('受控前缀集合仍含 /profile', () {
      final block = RegExp(r'_controlledLocations = \{(.*?)\}', dotAll: true)
          .firstMatch(routerSrc)!
          .group(1)!;
      expect(block, contains("'/profile'"));
    });

    /// 门控判定本身：游客访问新路由会被改写（`redirectWouldRewrite` 是路由表导出的
    /// 同一套判定，redirect 与"能不能 push"两处共用它）。
    test('游客访问聚合页 / 年龄卡页 → 判定为受控，会被改写', () {
      const guest = AuthState(status: AuthStatus.guest);
      expect(redirectWouldRewrite(guest, PetInsightsRoutes.hub), isTrue);
      expect(redirectWouldRewrite(guest, PetInsightsRoutes.idCard), isTrue);
      expect(redirectWouldRewrite(guest, PetInsightsRoutes.ageCard), isTrue);
      // V1.3.2 Story 1.2：护照页同样落在 /profile/ 下、自动受控。
      expect(PetInsightsRoutes.passport, startsWith('/profile/'));
      expect(redirectWouldRewrite(guest, PetInsightsRoutes.passport), isTrue);
      expect(redirectWouldRewrite(guest, PetInsightsRoutes.passportFor(focus: 'abc')), isTrue);
      // V1.3.2 Story 1.3：落章页与章详情页同样受控。
      expect(redirectWouldRewrite(guest, PetInsightsRoutes.passportNewStamp), isTrue);
      expect(redirectWouldRewrite(guest, PetInsightsRoutes.passportStampFor('abc')), isTrue);
      // V1.3.2 Story 2.3：Tailsonality 答题页与结果页同样落在 /profile/ 下、自动受控。
      expect(TailsonalityRoutes.quiz, startsWith('/profile/'));
      expect(TailsonalityRoutes.result('abc'), startsWith('/profile/'));
      expect(redirectWouldRewrite(guest, TailsonalityRoutes.quiz), isTrue);
      expect(redirectWouldRewrite(guest, TailsonalityRoutes.result('abc')), isTrue);
      // V1.3.2 Story 2.5：主人配型页同样受控。
      expect(TailsonalityRoutes.match('abc'), startsWith('/profile/'));
      expect(redirectWouldRewrite(guest, TailsonalityRoutes.match('abc')), isTrue);
      // V1.3.2 Story 2.6：结果列表页同样受控。
      expect(TailsonalityRoutes.results, startsWith('/profile/'));
      expect(redirectWouldRewrite(guest, TailsonalityRoutes.results), isTrue);
      // 旧路径同样受控 —— 重定向不能变成绕过门控的旁路（AC4 / AD-A17.6）。
      expect(redirectWouldRewrite(guest, '/profile/id-card'), isTrue);
    });

    test('登录用户访问这些路由不被改写', () {
      const user = AuthState(status: AuthStatus.authenticated, role: 'USER');
      expect(redirectWouldRewrite(user, PetInsightsRoutes.hub), isFalse);
      expect(redirectWouldRewrite(user, PetInsightsRoutes.idCard), isFalse);
      expect(redirectWouldRewrite(user, TailsonalityRoutes.quiz), isFalse);
    });
  });

  group('AC4/AC6 🔴 旧路径不断链，且迁移没有误伤邻居', () {
    test('旧路径仍在路由表里，且是重定向而不是页面', () {
      expect(routerSrc, contains("GoRoute(path: '/profile/id-card', redirect:"));
    });

    /// 🔴 `/profile/id-card`（本次迁移）与 `/profile/id-cards/create`、`/profile/id-cards/:id`
    /// （多卡子路由，**不受迁移影响**）只差一个字母。做前缀字符串替换会把后两条一起改掉。
    test('多卡子路由一字未动', () {
      expect(routerSrc, contains("'/profile/id-cards/create'"));
      expect(routerSrc, contains("'/profile/id-cards/:id'"));
      // 它们绝不该被挪到聚合页下面。
      expect(routerSrc, isNot(contains('pet-insights/id-cards')));
    });

    test('站内跳转已逐条改到新常量，没有残留旧字面量', () {
      for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
        if (!f.path.endsWith('.dart')) continue;
        if (f.path.endsWith('app_router.dart')) continue; // 重定向本体在这里
        expect(f.readAsStringSync(), isNot(contains("'/profile/id-card'")),
            reason: '${f.path} 还在跳旧路径');
      }
    });
  });

  group('AC2 🔴 KTP / 年龄卡 / 护照 / Tailsonality / 登机牌五张卡，不预埋尚未上线的卡（V1.3.2 Story 3.5 更新）', () {
    final String pageSrc =
        File('lib/features/profile/presentation/pet_insights_page.dart').readAsStringSync();

    /// 「即将推出」式占位一律不许留。护照（1.2）/ Tailsonality（2.3）/ 登机牌（3.5）已上线，从禁词里移除
    /// （Story 3.5 按新规则更新：登机牌不再是禁词）；「即将推出」禁词原样保留。
    test('源码里没有「即将推出」的任何痕迹', () {
      // 只看真正的代码：文档注释里**本来就会**提到这两样（那段话正是在写"为什么不占位"），
      // 扫全文会被自己的解释性注释弄红。
      final code = pageSrc
          .split('\n')
          .where((l) => !l.trimLeft().startsWith('///') && !l.trimLeft().startsWith('//'))
          .join('\n');
      for (final banned in [
        'comingSoon',
        'coming_soon',
      ]) {
        expect(code, isNot(contains(banned)), reason: '$banned：不占位');
      }
    });

    testWidgets('聚合页就是五张卡，按 C-8 顺序', (tester) async {
      await _pumpHub(tester, petType: 'CAT');
      const order = ['insightIdCard', 'insightAgeCard', 'insightPassport', 'insightTailsonality', 'insightBoardingPass'];
      for (final k in order) {
        expect(find.byKey(ValueKey(k)), findsOneWidget, reason: k);
      }
      expect(find.byType(InkWell), findsNWidgets(5));
      final rects = [for (final k in order) tester.getRect(find.byKey(ValueKey(k)))];
      // 阅读顺序：同行左→右、行间上→下。
      for (var i = 1; i < rects.length; i++) {
        final prev = rects[i - 1], cur = rects[i];
        expect(cur.top > prev.top || (cur.top == prev.top && cur.left > prev.left), isTrue, reason: order[i]);
      }
    });

    /// V1.3.2 Story 2.3：2+2 排布 —— 护照从整宽变回半宽（预期），与 Tailsonality 并排第二行；全物种可点。
    testWidgets('第二行护照 + Tailsonality 并排、全物种可点', (tester) async {
      await _pumpHub(tester, petType: 'OTHER');
      final ktp = tester.getRect(find.byKey(const ValueKey('insightIdCard')));
      final age = tester.getRect(find.byKey(const ValueKey('insightAgeCard')));
      final pass = tester.getRect(find.byKey(const ValueKey('insightPassport')));
      final ts = tester.getRect(find.byKey(const ValueKey('insightTailsonality')));
      expect(ktp.top, age.top, reason: '第一行两张并排');
      expect(pass.top, greaterThan(ktp.bottom), reason: '护照在第二行');
      expect(ts.top, pass.top, reason: 'Tailsonality 与护照同一行');
      expect(pass.width, closeTo(ktp.width, 1), reason: '2+2：护照回到半宽');
      expect(tester.widget<InkWell>(find.byKey(const ValueKey('insightTailsonality'))).onTap, isNotNull);
      final card = tester.widget<InkWell>(find.byKey(const ValueKey('insightPassport')));
      expect(card.onTap, isNotNull);

      await tester.tap(find.byKey(const ValueKey('insightPassport')));
      await tester.pumpAndSettle();
      expect(find.text('passport'), findsOneWidget);
    });

    /// V1.3.2 Story 3.5：第三行整宽登机牌卡（2+2+1）；全物种可点 → 登机牌列表。
    testWidgets('第三行登机牌整宽、可点进列表', (tester) async {
      await _pumpHub(tester, petType: 'OTHER');
      final ts = tester.getRect(find.byKey(const ValueKey('insightTailsonality')));
      final ktp = tester.getRect(find.byKey(const ValueKey('insightIdCard')));
      final bp = tester.getRect(find.byKey(const ValueKey('insightBoardingPass')));
      expect(bp.top, greaterThan(ts.bottom), reason: '第三行');
      expect(bp.width, greaterThan(ktp.width * 1.8), reason: '整宽');
      await tester.tap(find.byKey(const ValueKey('insightBoardingPass')));
      await tester.pumpAndSettle();
      expect(find.text('boarding-pass'), findsOneWidget);
    });

    test('登机牌路由落在 /profile/ 下（继承游客门控）', () {
      expect(PetInsightsRoutes.boardingPass, startsWith('/profile/'));
      expect(PetInsightsRoutes.boardingPassFor('a b'), '${PetInsightsRoutes.boardingPass}/a%20b');
    });

    testWidgets('页面标题与入口卡标题同源', (tester) async {
      await _pumpHub(tester, petType: 'CAT');
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.petInsightsTitle), findsOneWidget);
    });
  });

  group('V1.3.2 Story 2.3 · AC1.3 Tailsonality 卡分流', () {
    testWidgets('无结果 → 弹说明抽屉', (tester) async {
      await _pumpHub(tester, petType: 'CAT', tsResults: const []);
      await tester.tap(find.byKey(const ValueKey('insightTailsonality')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tsIntroSheet')), findsOneWidget);
    });

    // V1.3.2 Story 2.6 · AC4：有结果的去向由「最近一次结果页」改为结果列表页。
    testWidgets('有结果 → 进结果列表页', (tester) async {
      await _pumpHub(tester, petType: 'CAT', tsResults: [_tsResult('newest'), _tsResult('older')]);
      await tester.tap(find.byKey(const ValueKey('insightTailsonality')));
      await tester.pumpAndSettle();
      expect(find.text('results-list'), findsOneWidget);
      expect(find.text('result:newest'), findsNothing);
    });

    testWidgets('读列表期间双击只走一次', (tester) async {
      final repo = _FakeTsRepo([_tsResult('newest')], delay: const Duration(milliseconds: 200));
      await _pumpHub(tester, petType: 'CAT', repo: repo);
      await tester.tap(find.byKey(const ValueKey('insightTailsonality')));
      await tester.pump(const Duration(milliseconds: 50));
      await tester.tap(find.byKey(const ValueKey('insightTailsonality')));
      await tester.pumpAndSettle();
      expect(repo.calls, 1);
      expect(find.text('results-list'), findsOneWidget);
    });

    // 待确认 2.5（2026-10-02）按新规则更新：不再静默，给「加载失败，再试一次」轻提示。
    testWidgets('档案取失败且无结果 → 不抛未处理异常、不弹抽屉、给轻提示', (tester) async {
      await _pumpHub(tester, petType: null, profileFails: true);
      await tester.tap(find.byKey(const ValueKey('insightTailsonality')));
      await tester.pump();
      await tester.pump();
      expect(tester.takeException(), isNull);
      expect(find.byKey(const ValueKey('tsIntroSheet')), findsNothing);
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.detailNetworkError), findsOneWidget);
      await tester.pumpAndSettle(const Duration(seconds: 5));
    });

    testWidgets('列表读取失败 → 仍弹说明抽屉', (tester) async {
      await _pumpHub(tester, petType: 'OTHER', tsFails: true);
      await tester.tap(find.byKey(const ValueKey('insightTailsonality')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tsIntroSheet')), findsOneWidget);
    });
  });

  group('AC7 非猫狗：原地置灰，不跳不弹', () {
    testWidgets('猫 → 年龄卡可点，副文案是召唤语', (tester) async {
      await _pumpHub(tester, petType: 'CAT');
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));

      final card = tester.widget<InkWell>(find.byKey(const ValueKey('insightAgeCard')));
      expect(card.onTap, isNotNull);
      expect(find.text(l10n.ageCardUnavailableForSpecies), findsNothing);
      // bug 503/504：两卡各有专属副文案，不再复用共享键 timelineIdCardTapToView。
      expect(find.text(l10n.ageCardEntrySub), findsOneWidget);
      expect(find.text(l10n.idCardEntrySub), findsOneWidget);
      expect(find.text(l10n.timelineIdCardTapToView), findsNothing);
    });

    testWidgets('狗 → 同样可点', (tester) async {
      await _pumpHub(tester, petType: 'DOG');
      final card = tester.widget<InkWell>(find.byKey(const ValueKey('insightAgeCard')));
      expect(card.onTap, isNotNull);
    });

    /// 🔴 置灰即**彻底不可点**：onTap 为 null，点了不跳转、不弹层、不新开页。
    testWidgets('其他物种 → 年龄卡置灰不可点，换成适用范围提示', (tester) async {
      await _pumpHub(tester, petType: 'OTHER');
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));

      final card = tester.widget<InkWell>(find.byKey(const ValueKey('insightAgeCard')));
      expect(card.onTap, isNull);
      expect(find.text(l10n.ageCardUnavailableForSpecies), findsOneWidget);

      // 点一下，什么都不该发生（没有新页面、没有弹层）。
      await tester.tap(find.byKey(const ValueKey('insightAgeCard')));
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(find.byType(PetInsightsPage), findsOneWidget);
    });

    /// 身份证对**全物种**可用 —— 置灰只针对年龄换算。
    testWidgets('其他物种的身份证卡照常可点', (tester) async {
      await _pumpHub(tester, petType: 'OTHER');
      final card = tester.widget<InkWell>(find.byKey(const ValueKey('insightIdCard')));
      expect(card.onTap, isNotNull);
    });

    /// 档案还没取到时按「不是猫狗」保守处理：一个算不出结果的入口可点，
    /// 比它暂时灰着更糟。
    testWidgets('档案取不到 → 保守置灰', (tester) async {
      await _pumpHub(tester, petType: null, noProfile: true);
      final card = tester.widget<InkWell>(find.byKey(const ValueKey('insightAgeCard')));
      expect(card.onTap, isNull);
    });
  });

  group('AC8 页头三态：沿用既有判定，不另写一次', () {
    final String headerSrc = File(
            'lib/features/profile/presentation/widgets/diary_header.dart')
        .readAsStringSync();

    /// 入口条挂在既有的 `if (!readOnly)` 里 —— 游客态与访客态由那一个判定一起挡掉。
    /// 为它再写一次态判断，就会出现两处口径迟早走歧（AD-A24.4）。
    test('入口区仍只由一个 readOnly 分支控制', () {
      expect(headerSrc, contains('if (!readOnly) ...['));
      expect('readOnly'.allMatches(headerSrc).length, lessThanOrEqualTo(6),
          reason: '态判定散开就是 AD-A24.4 要防的事');
    });

    test('入口卡标题换成了综合入口名，key 保持不变', () {
      expect(headerSrc, contains('petInsightsTitle'));
      expect(headerSrc, contains("ValueKey('diaryIdCardButton')"));
      // 副文案沿用现成 key，不新写（AC1）。
      expect(headerSrc, contains('timelineIdCardTapToView'));
    });
  });
}

/// 挂一个只有聚合页的最小路由环境。
Future<void> _pumpHub(
  WidgetTester tester, {
  required String? petType,
  bool noProfile = false,
  List<TailsonalityResult> tsResults = const [],
  bool tsFails = false,
  bool profileFails = false,
  _FakeTsRepo? repo,
}) async {
  // retry 关掉：Riverpod 3 默认对失败 provider 定时重试，测试结束时会留下挂起的 Timer。
  final container = ProviderContainer(retry: (_, _) => null, overrides: [
    petProfileProvider.overrideWith((ref) async {
      if (profileFails) throw Exception('profile');
      return noProfile ? null : PetProfile(id: 1, name: 'Mochi', cardToken: 'tok', petType: petType);
    }),
    tailsonalityRepositoryProvider.overrideWithValue(repo ?? _FakeTsRepo(tsResults, fails: tsFails)),
  ]);
  addTearDown(container.dispose);

  final router = GoRouter(
    initialLocation: PetInsightsRoutes.hub,
    routes: [
      GoRoute(path: PetInsightsRoutes.hub, builder: (c, s) => const PetInsightsPage()),
      GoRoute(
          path: PetInsightsRoutes.idCard,
          builder: (c, s) => const Scaffold(body: Text('ktp'))),
      GoRoute(
          path: PetInsightsRoutes.ageCard,
          builder: (c, s) => const Scaffold(body: Text('age'))),
      GoRoute(
          path: PetInsightsRoutes.boardingPass,
          builder: (c, s) => const Scaffold(body: Text('boarding-pass'))),
      GoRoute(
          path: PetInsightsRoutes.passport,
          builder: (c, s) => const Scaffold(body: Text('passport'))),
      GoRoute(
          path: TailsonalityRoutes.quiz,
          builder: (c, s) => const Scaffold(body: Text('quiz'))),
      GoRoute(
          path: TailsonalityRoutes.results,
          builder: (c, s) => const Scaffold(body: Text('results-list'))),
      GoRoute(
          path: TailsonalityRoutes.resultPattern,
          builder: (c, s) => Scaffold(body: Text('result:${s.pathParameters['token']}'))),
    ],
  );
  addTearDown(router.dispose);

  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      routerConfig: router,
    ),
  ));
  await tester.pumpAndSettle();
}

TailsonalityResult _tsResult(String token) => TailsonalityResult(
      token: token,
      typeCode: 'ENTJ-H',
      letters: 'ENTJ',
      energy: 'H',
      questionSet: 'CAT',
      resultIndex: 1,
      unlocked: false,
      contentVersion: 1,
      createdAt: DateTime.utc(2026, 9, 30),
    );

class _FakeTsRepo implements TailsonalityRepository {
  _FakeTsRepo(this.results, {this.fails = false, this.delay = Duration.zero});

  final List<TailsonalityResult> results;
  final bool fails;
  final Duration delay;
  int calls = 0;

  @override
  Future<List<TailsonalityResult>> fetchResults() async {
    calls++;
    if (delay > Duration.zero) await Future<void>.delayed(delay);
    if (fails) throw Exception('network');
    return results;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
