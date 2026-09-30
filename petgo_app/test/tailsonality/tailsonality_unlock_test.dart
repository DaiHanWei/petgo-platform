import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/keepsake/data/keepsake_repository.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_pricing.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_purchase_result.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_owner_type_repository.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_repository.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_result_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_locked_analysis.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_unlocked_analysis.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';

/// V1.3.2 Story 3.2 · L0：锁态购买入口（AC4）、已解锁付费区（AC5）、挽留弹窗（AC7）、App 埋点（AC8）。
void main() {
  TailsonalityResult result({bool unlocked = false}) => TailsonalityResult(
        token: 'abc',
        typeCode: 'ENTJ-H',
        letters: 'ENTJ',
        energy: 'H',
        questionSet: 'CAT',
        resultIndex: 2,
        unlocked: unlocked,
        contentVersion: 1,
        createdAt: DateTime.utc(2026, 9, 30),
      );

  const pricing = KeepsakePricing(ktpHd: 10000, passportSnapshot: 2000, boardingPass: 1000, tailsonality: 5000);

  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    SharedPreferences.setMockInitialValues({});
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<_Repo> pumpPage(
    WidgetTester tester, {
    bool unlocked = false,
    Future<KeepsakePricing> Function()? price,
    int balance = 20000,
    _Repo? repo,
    Future<PawCoinState> Function()? paw,
  }) async {
    tester.view.physicalSize = const Size(420, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final r = repo ?? _Repo(result(unlocked: unlocked));
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [
        petProfileProvider.overrideWith((ref) async =>
            const PetProfile(id: 1, name: 'Momo', cardToken: 't', petType: 'CAT', breed: 'Anggora')),
        tailsonalityOwnerTypeRepositoryProvider.overrideWithValue(_OwnerRepo()),
        tailsonalityRepositoryProvider.overrideWithValue(r),
        keepsakePricingProvider.overrideWith((ref) => (price ?? () async => pricing)()),
        pawCoinProvider.overrideWith(() => _Paw(balance, paw)),
      ],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: TextButton(
                key: const ValueKey('open'),
                onPressed: () => Navigator.of(context)
                    .push(MaterialPageRoute<void>(builder: (_) => const TailsonalityResultPage(token: 'abc'))),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pumpAndSettle();
    return r;
  }

  Future<void> back(WidgetTester tester) async {
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
  }

  group('锁态购买入口（AC4）', () {
    testWidgets('价格来自服务端：Buka Rp5.000；已解锁不显示', (tester) async {
      await pumpPage(tester);
      expect(find.descendant(of: find.byType(TsLockedAnalysis), matching: find.text('Buka Rp5.000')), findsOneWidget);
      expect(tester.widget<FilledButton>(find.byKey(const ValueKey('tsUnlockCta'))).onPressed, isNotNull);
    });

    testWidgets('取价中「…」禁用；失败显示重试', (tester) async {
      final c = Completer<KeepsakePricing>();
      await pumpPage(tester, price: () => c.future);
      expect(find.text('…'), findsOneWidget);
      expect(tester.widget<FilledButton>(find.byKey(const ValueKey('tsUnlockCta'))).onPressed, isNull);
      c.completeError(Exception('x'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tsUnlockPriceRetry')), findsOneWidget);
      expect(find.byKey(const ValueKey('tsUnlockCta')), findsNothing);
    });

    testWidgets('PawCoin 成功 → 切已解锁态：去水印、三段顺序、toast、埋点（不在 App 报 unlocked）', (tester) async {
      final repo = await pumpPage(tester);
      expect(find.byType(CardWatermark), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tsUnlockCta')));
      await tester.pumpAndSettle();
      expect(find.text('Buka analisis lengkap'), findsOneWidget);
      expect(find.textContaining('Deep dive khusus Momo'), findsOneWidget);
      expect(find.text('Bayar Rp5.000'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tsPayConfirm')));
      await tester.pumpAndSettle();

      expect(repo.unlockCalls, [HdPayChannel.pawcoin]);
      expect(find.text('Hasil sudah kebuka!'), findsOneWidget);
      expect(find.byType(TsLockedAnalysis), findsNothing);
      expect(find.byType(TsUnlockedAnalysis), findsOneWidget);
      expect(find.byType(CardWatermark), findsNothing);
      final deep = tester.getTopLeft(find.byKey(const ValueKey('tsUnlocked_deepRead'))).dy;
      final dims = tester.getTopLeft(find.byKey(const ValueKey('tsUnlocked_dimensions'))).dy;
      final energy = tester.getTopLeft(find.byKey(const ValueKey('tsUnlocked_energy'))).dy;
      expect(deep < dims && dims < energy, isTrue);
      expect(find.text('Khusus buat ENTJ-H'), findsOneWidget);
      for (final c in ['E', 'N', 'T', 'J']) {
        expect(find.byKey(ValueKey('tsPoleActive_$c')), findsOneWidget, reason: c);
        expect(find.byKey(ValueKey('tsUnlockedDim_$c')), findsOneWidget, reason: c);
      }
      expect(find.byKey(const ValueKey('tsPole_I')), findsOneWidget);
      expect(find.text('High'), findsOneWidget);
      expect(find.textContaining('{pet}'), findsNothing);
      // 已解锁页底部不放任何主 CTA。
      expect(find.byType(FilledButton), findsNothing);

      final initiated = events.where((e) => e.$1 == 'tailsonality_unlock_initiated').single.$2!;
      expect(initiated, {'role_code': 'ENTJ-H', 'price': 5000, 'result_index': 2, 'method': 'PAWCOIN'});
      final viewed = events.where((e) => e.$1 == 'tailsonality_unlock_viewed').single.$2!;
      expect(viewed, {'role_code': 'ENTJ-H', 'price': 5000, 'result_index': 2});
      expect(events.where((e) => e.$1 == 'tailsonality_unlocked'), isEmpty);
    });

    testWidgets('409 keepsake-already-unlocked → 静默刷新为已解锁，不提示余额不足', (tester) async {
      final repo = _Repo(result())..error = _problem(409, 'keepsake-already-unlocked');
      await pumpPage(tester, repo: repo);
      await tester.tap(find.byKey(const ValueKey('tsUnlockCta')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('tsPayConfirm')));
      await tester.pumpAndSettle();
      expect(find.text('PawCoin tidak cukup. Silakan isi ulang dulu.'), findsNothing);
      expect(find.text('Hasil sudah kebuka!'), findsNothing);
      expect(find.byType(TsUnlockedAnalysis), findsOneWidget);
    });

    testWidgets('409 pawcoin-insufficient → 余额不足文案，保持锁态', (tester) async {
      final repo = _Repo(result())
        ..error = _problem(409, 'pawcoin-insufficient')
        ..unlockOnError = false;
      await pumpPage(tester, repo: repo);
      await tester.tap(find.byKey(const ValueKey('tsUnlockCta')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('tsPayConfirm')));
      await tester.pumpAndSettle();
      expect(find.text('PawCoin tidak cukup. Silakan isi ulang dulu.'), findsOneWidget);
      expect(find.byType(TsLockedAnalysis), findsOneWidget);
    });

    testWidgets('关掉抽屉不发起、不报 initiated', (tester) async {
      final repo = await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('tsUnlockCta')));
      await tester.pumpAndSettle();
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();
      expect(repo.unlockCalls, isEmpty);
      expect(events.where((e) => e.$1 == 'tailsonality_unlock_initiated'), isEmpty);
      expect(find.byType(TsLockedAnalysis), findsOneWidget);
    });
  });

  group('挽留弹窗（AC7）', () {
    testWidgets('未解锁 + 未记录 → 返回弹出；Nanti aja → 退出、已记录、报 abandoned；再进同一结果返回直接退出',
        (tester) async {
      await pumpPage(tester);
      await back(tester);
      expect(find.byKey(const ValueKey('tsRetentionDialog')), findsOneWidget);
      expect(find.text('Yakin keluar?'), findsOneWidget);
      expect(find.textContaining('badge kepribadian Momo'), findsOneWidget);
      expect(tester.getSize(find.byKey(const ValueKey('tsRetainLater'))).height, greaterThanOrEqualTo(44));
      expect(tester.getSize(find.byKey(const ValueKey('tsRetainUnlock'))).height, greaterThanOrEqualTo(44));
      await tester.tap(find.byKey(const ValueKey('tsRetainLater')));
      await tester.pumpAndSettle();
      expect(find.byType(TailsonalityResultPage), findsNothing);
      expect(events.where((e) => e.$1 == 'tailsonality_paywall_abandoned').single.$2, {'result_index': 2});
      expect((await AppPrefs.create()).tailsonalityRetentionShown('abc'), isTrue);

      await tester.tap(find.byKey(const ValueKey('open')));
      await tester.pumpAndSettle();
      await back(tester);
      expect(find.byKey(const ValueKey('tsRetentionDialog')), findsNothing);
      expect(find.byType(TailsonalityResultPage), findsNothing);
    });

    testWidgets('Unlock → 关弹窗、留在页面、直接进购买抽屉', (tester) async {
      await pumpPage(tester);
      await back(tester);
      await tester.tap(find.byKey(const ValueKey('tsRetainUnlock')));
      await tester.pumpAndSettle();
      expect(find.byType(TailsonalityResultPage), findsOneWidget);
      expect(find.byKey(const ValueKey('tsPayConfirm')), findsOneWidget);
    });

    testWidgets('购买流程进行中（余额还在读、抽屉未弹）点返回 → 直接退出，不卡住', (tester) async {
      final paw = Completer<PawCoinState>();
      await pumpPage(tester, paw: () => paw.future);
      await tester.tap(find.byKey(const ValueKey('tsUnlockCta')));
      await tester.pump();
      await back(tester);
      expect(find.byType(TailsonalityResultPage), findsNothing);
      expect(find.byKey(const ValueKey('tsRetentionDialog')), findsNothing);
      paw.complete(const PawCoinState(balance: 0, items: []));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tsPayConfirm')), findsNothing);
    });

    testWidgets('已解锁 → 返回不弹', (tester) async {
      await pumpPage(tester, unlocked: true);
      await back(tester);
      expect(find.byKey(const ValueKey('tsRetentionDialog')), findsNothing);
      expect(find.byType(TailsonalityResultPage), findsNothing);
    });
  });

  test('AppPrefs：挽留记录只留最近 50 个', () async {
    SharedPreferences.setMockInitialValues({});
    final p = await AppPrefs.create();
    for (var i = 0; i < 60; i++) {
      await p.markTailsonalityRetentionShown('t$i');
    }
    expect(p.tailsonalityRetentionShown('t59'), isTrue);
    expect(p.tailsonalityRetentionShown('t10'), isTrue);
    expect(p.tailsonalityRetentionShown('t9'), isFalse);
    final raw = (await SharedPreferences.getInstance()).getStringList(AppPrefs.kTailsonalityRetentionShown)!;
    expect(raw, hasLength(50));
  });
}

DioException _problem(int status, String slug) {
  final opts = RequestOptions(path: '/x');
  return DioException(
    requestOptions: opts,
    response: Response(
        requestOptions: opts,
        statusCode: status,
        data: {'type': 'https://petgo/errors/$slug', 'status': status, 'title': 'x'}),
  );
}

class _Repo implements TailsonalityRepository {
  _Repo(this.current);

  TailsonalityResult current;
  final List<HdPayChannel> unlockCalls = [];
  DioException? error;

  /// 出错时服务端是否确已解锁（already-unlocked 场景）。
  bool unlockOnError = true;

  @override
  Future<TailsonalityResult> fetchResult(String token) async => current;

  @override
  Future<List<TailsonalityResult>> fetchResults() async => [current];

  @override
  Future<KeepsakePurchaseResult> unlock(String token, HdPayChannel channel) async {
    unlockCalls.add(channel);
    if (error != null) {
      if (unlockOnError) current = _unlocked(current);
      throw error!;
    }
    current = _unlocked(current);
    return const KeepsakePurchaseResult(unlocked: true, purchaseToken: 'kp');
  }

  static TailsonalityResult _unlocked(TailsonalityResult r) => TailsonalityResult(
        token: r.token,
        typeCode: r.typeCode,
        letters: r.letters,
        energy: r.energy,
        questionSet: r.questionSet,
        resultIndex: r.resultIndex,
        unlocked: true,
        unlockedAt: DateTime.utc(2026, 10, 1),
        contentVersion: r.contentVersion,
        createdAt: r.createdAt,
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _OwnerRepo implements TailsonalityOwnerTypeRepository {
  @override
  Future<String?> fetch() async => null;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Paw extends PawCoinController {
  _Paw(this.balance, [this.load]);

  final int balance;
  final Future<PawCoinState> Function()? load;

  @override
  Future<PawCoinState> build() => load?.call() ?? Future.value(PawCoinState(balance: balance, items: const []));
}
