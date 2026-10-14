import 'dart:collection';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/pet_passport/data/pet_passport_repository.dart';
import 'package:tailtopia/features/pet_passport/domain/pet_passport.dart';
import 'package:tailtopia/features/place/domain/place_checkin_result.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/place/presentation/place_checkin_success_page.dart';
import 'package:tailtopia/features/place/presentation/place_stamp_ceremony_page.dart';
import 'package:tailtopia/features/place/presentation/widgets/place_stamp_view.dart';
import 'package:tailtopia/features/profile/presentation/milestone_instant_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 签到盖章动画 · Task 3 · L0：全屏动画页的时序 / 兜底 / 只导航一次 / 返回栈。
///
/// Rive 组件以 [_FakeCeremony] 注入（测试环境没有原生库）：由用例决定何时回调 complete / error / 不回调。
void main() {
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  List<Map<String, Object>?> ceremonyEvents() =>
      [for (final e in events) if (e.$1 == 'place_stamp_ceremony_played') e.$2];

  testWidgets('completeHoldsThenReplacesWithSuccess', (tester) async {
    final h = _Harness();
    await h.pump(tester);
    expect(find.byType(PlaceStampCeremonyPage), findsOneWidget);
    expect(h.fake.instances, 1);
    expect(h.fake.lastRequest!.reduceMotion, isFalse);

    h.fake.lastRequest!.onComplete();
    await tester.pump(const Duration(milliseconds: 390));
    expect(find.byType(PlaceStampCeremonyPage), findsOneWidget, reason: '最终帧停 400ms');
    expect(find.byType(PlaceCheckinSuccessPage), findsNothing);

    await tester.pump(const Duration(milliseconds: 20)); // 过 400ms → pushReplacement 成功页
    await tester.pump(const Duration(milliseconds: 150));
    // spec §2 拍板 A：成功页以 300ms 淡入盖上动画最终帧（不是先淡成空白页再走平台转场）。
    final route = ModalRoute.of(tester.element(find.byType(PlaceCheckinSuccessPage)))! as PageRoute<void>;
    expect(route.settings, isA<CustomTransitionPage<void>>());
    expect(route.transitionDuration, const Duration(milliseconds: 300));
    // 进场淡入是最外层 FadeTransition（内层是被钉在终态的平台转场）。
    final fades = tester.widgetList<FadeTransition>(
        find.ancestor(of: find.byType(PlaceCheckinSuccessPage), matching: find.byType(FadeTransition)));
    expect(fades.any((f) => f.opacity.value > 0.0 && f.opacity.value < 1.0), isTrue, reason: '0.3s 淡入进行中');
    expect(find.byType(PlaceStampCeremonyPage), findsOneWidget, reason: '淡入期间动画最终帧仍垫在下面');

    await tester.pumpAndSettle();
    expect(find.byType(PlaceStampCeremonyPage), findsNothing);
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(h.successArgs.single.ceremonyPlayed, isTrue);
    expect(ceremonyEvents(), [
      {'result': 'completed'}
    ]);
  });

  testWidgets('errorGoesStraightToSuccessWithSmallStamp', (tester) async {
    final h = _Harness();
    await h.pump(tester);
    h.fake.lastRequest!.onError(StateError('rive failed'));
    await tester.pumpAndSettle();
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(h.successArgs.single.ceremonyPlayed, isFalse);
    // 成功页按「未播动画」处理：默认平台转场 + 保留原 0.36s 小落章。
    final route = ModalRoute.of(tester.element(find.byType(PlaceCheckinSuccessPage)))!;
    expect(route.settings, isA<MaterialPage<void>>());
    expect(
        find.ancestor(of: find.byType(PlaceStampView), matching: find.byType(TweenAnimationBuilder<double>)),
        findsOneWidget);
    expect(ceremonyEvents(), [
      {'result': 'failed'}
    ]);
  });

  testWidgets('nullFaceSkips', (tester) async {
    final h = _Harness(noFace: true);
    await h.pump(tester);
    expect(h.fake.instances, 0, reason: '章面取不到 → 不播');
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(h.successArgs.single.ceremonyPlayed, isFalse);
    expect(ceremonyEvents(), [
      {'result': 'failed'}
    ]);
  });

  testWidgets('faceLoaderGetsUrlAndType', (tester) async {
    final h = _Harness(stampImageUrl: 'https://cdn.example/stamp.png');
    await h.pump(tester);
    expect(h.faceRequests.single, ('https://cdn.example/stamp.png', PlaceType.cafe));
    expect(h.fake.lastRequest!.stampPng, h.face);
  });

  testWidgets('watchdogAfter4s', (tester) async {
    final h = _Harness();
    await h.pump(tester);
    await tester.pump(const Duration(milliseconds: 3900));
    expect(find.byType(PlaceStampCeremonyPage), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 150));
    await tester.pumpAndSettle();
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(h.successArgs.single.ceremonyPlayed, isFalse);
    expect(ceremonyEvents(), [
      {'result': 'timeout'}
    ]);
  });

  testWidgets('navigatesOnlyOnce：complete 贴着看门狗到达 → 只走一次', (tester) async {
    final h = _Harness();
    await h.pump(tester);
    // 看门狗在组件挂上前一帧起算：3950ms 时 complete 先到、紧接着跨过 4s。
    await tester.pump(const Duration(milliseconds: 3950));
    h.fake.lastRequest!.onComplete();
    h.fake.lastRequest!.onComplete(); // 重复事件
    h.fake.lastRequest!.onError(StateError('late')); // 迟到的失败
    await tester.pump(const Duration(milliseconds: 100)); // 跨过 4s 看门狗时刻
    await _settle(tester);
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(h.successArgs, hasLength(1));
    expect(h.successArgs.single.ceremonyPlayed, isTrue);
    expect(ceremonyEvents(), hasLength(1));
  });

  testWidgets('navigatesOnlyOnce：看门狗先到，迟到的 complete / error 被忽略', (tester) async {
    final h = _Harness();
    await h.pump(tester);
    await tester.pump(const Duration(seconds: 4));
    final req = h.fake.lastRequest!;
    req.onComplete();
    req.onError(StateError('late'));
    await tester.pumpAndSettle();
    expect(h.successArgs, hasLength(1));
    expect(h.successArgs.single.ceremonyPlayed, isFalse);
    expect(ceremonyEvents(), [
      {'result': 'timeout'}
    ]);
  });

  testWidgets('backDisabledDuringCeremony', (tester) async {
    final h = _Harness();
    await h.pump(tester);
    await tester.binding.handlePopRoute();
    await tester.pump(const Duration(seconds: 1));
    expect(find.byType(PlaceStampCeremonyPage), findsOneWidget, reason: '系统返回键在播放期间不生效');
    expect(find.text('DETAIL'), findsNothing);
    // 播放中点击无效：真点一下动画区域，组件收不到。
    await tester.tap(find.byKey(const ValueKey('fakeCeremony')), warnIfMissed: false);
    await tester.pump();
    expect(h.fake.taps, 0);
    expect(find.byType(PlaceStampCeremonyPage), findsOneWidget);
  });

  testWidgets('successBackReturnsToPlaceDetail', (tester) async {
    final h = _Harness();
    await h.pump(tester);
    h.fake.lastRequest!.onComplete();
    await _settle(tester);
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byType(PlaceCheckinSuccessPage), findsNothing);
    expect(find.byType(PlaceStampCeremonyPage), findsNothing, reason: '不回到动画页');
    expect(find.text('DETAIL'), findsOneWidget);
  });

  testWidgets('reduceMotionTriggersSkip', (tester) async {
    final h = _Harness(disableAnimations: true);
    await h.pump(tester);
    expect(h.fake.lastRequest!.reduceMotion, isTrue, reason: '系统减少动态效果 → 组件触发 skip');
    h.fake.lastRequest!.onComplete();
    await tester.pump(const Duration(milliseconds: 390));
    expect(find.byType(PlaceStampCeremonyPage), findsOneWidget, reason: '收到 complete 后同样停 400ms');
    await _settle(tester);
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(h.successArgs.single.ceremonyPlayed, isTrue);
    expect(ceremonyEvents(), [
      {'result': 'reduce_motion'}
    ]);
  });

  testWidgets('stampImpact → mediumImpact 触感', (tester) async {
    final calls = <MethodCall>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (c) async {
      calls.add(c);
      return null;
    });
    addTearDown(() =>
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    final h = _Harness();
    await h.pump(tester);
    h.fake.lastRequest!.onImpact();
    await tester.pump();
    expect(
        calls.where((c) => c.method == 'HapticFeedback.vibrate' && c.arguments == 'HapticFeedbackType.mediumImpact'),
        hasLength(1));
  });

  testWidgets('切后台再回来：不重播、不卡住，最终只进成功页一次', (tester) async {
    final h = _Harness();
    await h.pump(tester);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 1));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(h.fake.instances, 1, reason: '不重新挂载 / 重播');
    h.fake.lastRequest!.onComplete();
    await _settle(tester);
    expect(h.successArgs, hasLength(1));
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(ceremonyEvents(), hasLength(1));
  });

  testWidgets('从未回调（切后台期间事件丢失）→ 看门狗兜底，仍只进一次', (tester) async {
    final h = _Harness();
    await h.pump(tester);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 5));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(h.successArgs, hasLength(1));
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
  });

  group('成功页 ceremonyPlayed', () {
    testWidgets('true → 不播 0.36s 小落章，直接终态', (tester) async {
      await _pumpSuccessOnly(tester, ceremonyPlayed: true);
      expect(find.byKey(const ValueKey('placeCheckinNewStamp')), findsOneWidget);
      expect(
          find.ancestor(of: find.byType(PlaceStampView), matching: find.byType(TweenAnimationBuilder<double>)),
          findsNothing);
      expect(find.text('Momo dapat cap baru'), findsOneWidget);
    });

    for (final played in [true, false]) {
      testWidgets('护照里有这枚章：ceremonyPlayed=$played → 章详情${played ? '直接终态' : '0.36s 淡入'}', (tester) async {
        final passport = PetPassport.fromJson({
          'petName': 'Momo',
          'passportNo': 'TT02P2600128',
          'currentVersionUnlocked': false,
          'stamps': [
            {
              'placeToken': 'p' * 32,
              'placeName': 'Kopi Kucing',
              'placeType': 'CAFE',
              'placeStatus': 'ACTIVE',
              'visitCount': 1,
              'firstVisitDate': '2026-09-30',
              'addressText': 'Jl. Kemang 1',
            },
          ],
        });
        await _pumpSuccessOnly(tester, ceremonyPlayed: played, passport: passport, settle: false);
        await tester.pump(); // 护照到手 → 占位换成章详情
        await tester.pump(const Duration(milliseconds: 16));
        final detail = find.byKey(const ValueKey('placeCheckinStampDetail'));
        expect(detail, findsOneWidget);
        final fade = tester.widget<FadeTransition>(
            find.ancestor(of: detail, matching: find.byType(FadeTransition)).first);
        if (played) {
          expect(fade.opacity.value, 1.0, reason: '播过盖章动画 → 不再叠 0.36s 淡入');
        } else {
          expect(fade.opacity.value, lessThan(1.0), reason: '未播动画 → 保持原 0.36s 淡入');
        }
        await tester.pumpAndSettle();
      });
    }

    testWidgets('false（默认）→ 保留小落章', (tester) async {
      await _pumpSuccessOnly(tester, ceremonyPlayed: false);
      expect(
          find.ancestor(of: find.byType(PlaceStampView), matching: find.byType(TweenAnimationBuilder<double>)),
          findsOneWidget);
    });
  });
}

// ===== fakes & harness =====

/// 停留后的导航按墙钟 Timer 触发，期间可能没有帧可 settle：先推过 400ms 停留再 settle（含 300ms 淡入）。
Future<void> _settle(WidgetTester tester) async {
  await tester.pump(const Duration(milliseconds: 750));
  await tester.pumpAndSettle();
}

class _FakeCeremony {
  int instances = 0;
  int taps = 0;
  StampCeremonyRequest? lastRequest;

  Widget build(BuildContext context, StampCeremonyRequest request) => _FakeCeremonyWidget(this, request);
}

class _FakeCeremonyWidget extends StatefulWidget {
  const _FakeCeremonyWidget(this.owner, this.request);

  final _FakeCeremony owner;
  final StampCeremonyRequest request;

  @override
  State<_FakeCeremonyWidget> createState() => _FakeCeremonyWidgetState();
}

class _FakeCeremonyWidgetState extends State<_FakeCeremonyWidget> {
  @override
  void initState() {
    super.initState();
    widget.owner.instances++;
    widget.owner.lastRequest = widget.request;
  }

  @override
  void didUpdateWidget(covariant _FakeCeremonyWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    widget.owner.lastRequest = widget.request;
  }

  @override
  Widget build(BuildContext context) => GestureDetector(
        key: const ValueKey('fakeCeremony'),
        behavior: HitTestBehavior.opaque,
        onTap: () => widget.owner.taps++,
        child: const SizedBox.expand(),
      );
}

class _Harness {
  _Harness({bool noFace = false, this.stampImageUrl, this.disableAnimations = false})
      : face = noFace ? null : Uint8List.fromList([1, 2, 3]);

  final Uint8List? face;
  final String? stampImageUrl;
  final bool disableAnimations;
  final fake = _FakeCeremony();
  final faceRequests = <(String?, PlaceType?)>[];
  /// 进入成功页的次数：go_router 重建会重复调 builder，按 extra 对象身份去重 = 真实导航次数。
  final _successArgs = LinkedHashSet<PlaceCheckinSuccessArgs>.identity();
  List<PlaceCheckinSuccessArgs> get successArgs => _successArgs.toList();

  Future<void> pump(WidgetTester tester) async {
    final args = PlaceCheckinSuccessArgs(
      petName: 'Momo',
      result: PlaceCheckinResult(
        checkinToken: 'c' * 32,
        placeToken: 'tok',
        placeName: 'Kopi Kucing',
        placeType: PlaceType.cafe,
        visitDate: DateTime(2026, 9, 30),
        isNewStamp: true,
        visitCount: 1,
        stampImageUrl: stampImageUrl,
      ),
    );
    final router = GoRouter(routes: [
      GoRoute(
        path: '/',
        builder: (c, s) => Scaffold(
          body: Center(
            child: TextButton(
              onPressed: () => c.push(PlaceStampCeremonyPage.routeFor('tok'), extra: args),
              child: const Text('DETAIL'),
            ),
          ),
        ),
      ),
      GoRoute(
        path: PlaceStampCeremonyPage.routePattern,
        builder: (c, s) => PlaceStampCeremonyPage(
          args: s.extra! as PlaceCheckinSuccessArgs,
          ceremonyBuilder: fake.build,
          faceLoader: ({String? url, PlaceType? type}) async {
            faceRequests.add((url, type));
            return face;
          },
        ),
      ),
      GoRoute(
        path: PlaceCheckinSuccessPage.routePattern,
        pageBuilder: (c, s) {
          final a = s.extra! as PlaceCheckinSuccessArgs;
          _successArgs.add(a);
          return PlaceCheckinSuccessPage.pageFor(s, a);
        },
      ),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        petPassportProvider.overrideWith((ref) async => throw StateError('offline')),
        milestoneInstantPollDelaysProvider.overrideWithValue(const []),
      ],
      child: MediaQuery(
        data: MediaQueryData(disableAnimations: disableAnimations),
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('DETAIL'));
    // 只推进到动画组件挂上（不 settle：看门狗 / 停留计时从此刻起由各用例自己推进）。
    for (var i = 0; i < 30 && fake.instances == 0; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    if (face == null) await tester.pumpAndSettle();
  }
}

Future<void> _pumpSuccessOnly(WidgetTester tester,
    {required bool ceremonyPlayed, PetPassport? passport, bool settle = true}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      petPassportProvider.overrideWith((ref) async => passport ?? (throw StateError('offline'))),
      milestoneInstantPollDelaysProvider.overrideWithValue(const []),
    ],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
      home: PlaceCheckinSuccessPage(
        args: PlaceCheckinSuccessArgs(
          petName: 'Momo',
          ceremonyPlayed: ceremonyPlayed,
          result: PlaceCheckinResult(
            checkinToken: 'c' * 32,
            placeToken: 'p' * 32,
            placeName: 'Kopi Kucing',
            placeType: PlaceType.cafe,
            visitDate: DateTime(2026, 9, 30),
            isNewStamp: true,
            visitCount: 1,
          ),
        ),
      ),
    ),
  ));
  if (settle) await tester.pumpAndSettle();
}
