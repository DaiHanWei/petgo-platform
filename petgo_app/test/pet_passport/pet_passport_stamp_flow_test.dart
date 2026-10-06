import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/presentation/widgets/place_stamp_view.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/pet_passport/data/pet_passport_repository.dart';
import 'package:tailtopia/features/pet_passport/domain/new_stamp_args.dart';
import 'package:tailtopia/features/pet_passport/domain/pet_passport.dart';
import 'package:tailtopia/features/pet_passport/presentation/pet_passport_new_stamp_page.dart';
import 'package:tailtopia/features/pet_passport/presentation/pet_passport_page.dart';
import 'package:tailtopia/features/pet_passport/presentation/pet_passport_stamp_page.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/profile/presentation/pet_insights_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 1.3 · L0：B4 整页落章 + B5 / B6 章详情。
void main() {
  const newStamp = NewStampArgs(
    placeToken: 'kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk',
    placeName: 'Kopi Kucing',
    placeType: PlaceType.cafe,
    stampCount: 4,
  );

  PassportStamp stamp(String token, {bool available = true, int visits = 1, String? address}) => PassportStamp(
        placeToken: token,
        placeName: 'Tempat $token',
        placeType: PlaceType.park,
        available: available,
        visitCount: visits,
        firstVisitDate: DateTime(2026, 9, 1),
        addressText: address,
      );

  final passport = PetPassport(petName: 'Momo', passportNo: 'TT02P2600128', stamps: [
    stamp('a' * 32, address: 'Jl. A 1'),
    stamp('b' * 32, available: false, visits: 3),
    stamp('k' * 32),
  ]);

  Future<GoRouter> pumpApp(WidgetTester tester, {required String initial, Object? extra,
      bool disableAnimations = false}) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(400, 1200);
    addTearDown(tester.view.reset);
    final router = GoRouter(initialLocation: '/start', routes: [
      GoRoute(
        path: '/start',
        builder: (c, s) => Scaffold(
          body: Builder(builder: (ctx) {
            return TextButton(
                key: const ValueKey('go'),
                onPressed: () => ctx.push(initial, extra: extra),
                child: const Text('start'));
          }),
        ),
      ),
      GoRoute(
          path: PetInsightsRoutes.passport,
          builder: (c, s) => PetPassportPage(focus: s.uri.queryParameters['focus'])),
      GoRoute(
        path: PetInsightsRoutes.passportNewStamp,
        redirect: (c, s) => s.extra is NewStampArgs ? null : PetInsightsRoutes.passport,
        builder: (c, s) => PetPassportNewStampPage(args: s.extra! as NewStampArgs),
      ),
      GoRoute(
          path: PetInsightsRoutes.passportStamp,
          builder: (c, s) => PetPassportStampPage(placeToken: s.pathParameters['placeToken']!)),
      GoRoute(path: '/places/:token', builder: (c, s) => Scaffold(body: Text('place ${s.uri}'))),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [petPassportProvider.overrideWith((ref) async => passport)],
      child: MediaQuery(
        data: MediaQueryData(disableAnimations: disableAnimations, size: const Size(400, 1200)),
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    // 2026-10-06 起有章时默认纵览：沿用原用例口径，先切到单章页（纵览相关用例再自行切回）。
    final toSingle = find.byKey(const ValueKey('passportToggleSingle'));
    if (toSingle.evaluate().isNotEmpty) {
      await tester.tap(toSingle);
      await tester.pumpAndSettle();
    }
    return router;
  }

  group('B4 整页落章（AC1）', () {
    testWidgets('两行文案无分母；「Lihat Paspor」替换路由后停在新章，返回不回 B4', (tester) async {
      await pumpApp(tester, initial: PetInsightsRoutes.passportNewStamp, extra: newStamp);

      expect(find.text('Cap baru! Kopi Kucing'), findsOneWidget);
      final count = tester.widget<Text>(find.byKey(const ValueKey('newStampCount'))).data!;
      expect(count, '4 cap terkumpul');
      expect(count.contains('/'), isFalse, reason: '🔴 无分母');

      await tester.tap(find.byKey(const ValueKey('newStampViewPassport')));
      await tester.pumpAndSettle();
      expect(find.byType(PetPassportPage), findsOneWidget);
      expect(find.text('Cap 3/3'), findsOneWidget, reason: '停在新章（第 3 枚）');

      // 返回：回到进入 B4 之前那页，不再看一次落章。
      final nav = tester.state<NavigatorState>(find.byType(Navigator).first);
      nav.pop();
      await tester.pumpAndSettle();
      expect(find.byType(PetPassportNewStampPage), findsNothing);
      expect(find.byKey(const ValueKey('go')), findsOneWidget);
    });

    testWidgets('disableAnimations：首帧即完整可读（章面 + 两行文案），无动画节点', (tester) async {
      await pumpApp(tester,
          initial: PetInsightsRoutes.passportNewStamp, extra: newStamp, disableAnimations: true);
      expect(find.byKey(const ValueKey('newStampAnimation')), findsNothing);
      // 章面在首帧就在：可能是占位图，也可能是已入缓存的默认章图（同一测试进程里前面的用例读过素材时）——
      // 只认「有一枚章面」，不认具体是哪种（原先只认占位图，随用例执行顺序时好时坏）。
      expect(find.byType(PlaceStampView), findsOneWidget);
      expect(find.byKey(const ValueKey('newStampTitle')), findsOneWidget);
      expect(find.byKey(const ValueKey('newStampCount')), findsOneWidget);
    });

    testWidgets('extra 缺失 → 回护照页，不崩', (tester) async {
      await pumpApp(tester, initial: PetInsightsRoutes.passportNewStamp);
      expect(tester.takeException(), isNull);
      expect(find.byType(PetPassportPage), findsOneWidget);
    });

    test('🔴 源码：NewStampArgs / passportNewStamp 只在成功页 isNewStamp 分支里', () {
      final src = File('lib/features/place/presentation/place_checkin_success_page.dart').readAsStringSync();
      // Story 1.5 起底部是一行 Row：「Lihat Paspor」只在 `if (r.isNewStamp) ...[ … ]` 分支里。
      final branch = src.indexOf('if (r.isNewStamp) ...[');
      final elseNull = src.indexOf('const SizedBox(width: 10),', branch);
      expect(branch, greaterThan(0));
      for (final needle in ['passportNewStamp', 'NewStampArgs(']) {
        final at = src.indexOf(needle);
        expect(at, greaterThan(branch), reason: '$needle 必须在 isNewStamp 分支内');
        expect(at, lessThan(elseNull), reason: '$needle 必须在 isNewStamp 分支内');
        expect(needle.allMatches(src).length, 1);
      }
    });
  });

  group('B5 / B6 章详情（AC3 / AC4）', () {
    testWidgets('B2 点章本体 → 进 B5', (tester) async {
      await pumpApp(tester, initial: PetInsightsRoutes.passport);
      await tester.tap(find.byKey(const ValueKey('passportStampTap')));
      await tester.pumpAndSettle();
      expect(find.byType(PetPassportStampPage), findsOneWidget);
    });

    testWidgets('ACTIVE：地址 +「Lihat tempat」→ 场所详情（from=passport）；内页局部「Halaman 1」', (tester) async {
      await pumpApp(tester, initial: PetInsightsRoutes.passportStampFor('a' * 32));

      expect(find.text('Jl. A 1'), findsOneWidget);
      expect(find.text('Halaman 1'), findsOneWidget);
      expect(find.byKey(const ValueKey('passportStampBook')), findsOneWidget);
      expect(find.byKey(const ValueKey('passportStampUnavailable')), findsNothing);

      await tester.tap(find.byKey(const ValueKey('passportSeePlace')));
      await tester.pumpAndSettle();
      expect(find.text('place /places/${'a' * 32}?from=passport'), findsOneWidget);
    });

    testWidgets('UNAVAILABLE（B6）：无地址、无「Lihat tempat」，出两行替换文案；×N 照常', (tester) async {
      await pumpApp(tester, initial: PetInsightsRoutes.passportStampFor('b' * 32));

      expect(find.byKey(const ValueKey('passportSeePlace')), findsNothing);
      expect(find.byKey(const ValueKey('passportStampAddress')), findsNothing);
      expect(find.text('Tempat tidak ditemukan'), findsOneWidget);
      expect(find.text('Tempat ini sudah tidak terdaftar'), findsOneWidget);
      expect(find.text('Halaman 2'), findsOneWidget);
      expect(find.text('x3'), findsWidgets);
      expect(find.text('3 kunjungan'), findsOneWidget);
    });

    testWidgets('找不到章 → 空态，不崩', (tester) async {
      await pumpApp(tester, initial: PetInsightsRoutes.passportStampFor('zzz'));
      expect(tester.takeException(), isNull);
      expect(find.byKey(const ValueKey('passportStampMissing')), findsOneWidget);
    });
  });

  test('路由表：new-stamp 与 stamps/:placeToken 已注册、都在 /profile/ 下且受控', () {
    final src = File('lib/core/router/app_router.dart').readAsStringSync();
    expect(src, contains('PetInsightsRoutes.passportNewStamp'));
    expect(src, contains('PetInsightsRoutes.passportStamp'));
    const guest = AuthState(status: AuthStatus.guest);
    expect(redirectWouldRewrite(guest, PetInsightsRoutes.passportNewStamp), isTrue);
  });
}
