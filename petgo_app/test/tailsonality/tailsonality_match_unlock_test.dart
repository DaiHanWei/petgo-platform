import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
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
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_match_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_result_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_letter_compare.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_match_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 2026-10-09 配型改回付费 · L0：配型页整页上锁、单买配型 Rp3,000、从配型页买完整解读（含配型）、
/// 已买配型后完整解读只补差价。
void main() {
  TailsonalityResult result({bool unlocked = false, bool matchUnlocked = false, int? upgradePrice}) =>
      TailsonalityResult(
        token: 'abc',
        typeCode: 'ENTJ-H',
        letters: 'ENTJ',
        energy: 'H',
        questionSet: 'CAT',
        resultIndex: 2,
        unlocked: unlocked,
        matchUnlocked: matchUnlocked,
        upgradePrice: upgradePrice,
        contentVersion: 1,
        createdAt: DateTime.utc(2026, 9, 30),
      );

  const pricing = KeepsakePricing(
      ktpHd: 10000, passportSnapshot: 2000, boardingPass: 1000, tailsonality: 5000, tailsonalityMatch: 3000);

  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    SharedPreferences.setMockInitialValues({});
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<_Repo> pump(WidgetTester tester, Widget page,
      {required _Repo repo, KeepsakePricing price = pricing, String? owner = 'INFP'}) async {
    tester.view.physicalSize = const Size(420, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [
        petProfileProvider.overrideWith(
            (ref) async => const PetProfile(id: 1, name: 'Momo', cardToken: 't', petType: 'CAT', breed: 'Anggora')),
        tailsonalityOwnerTypeRepositoryProvider.overrideWithValue(_OwnerRepo(owner)),
        tailsonalityRepositoryProvider.overrideWithValue(repo),
        keepsakePricingProvider.overrideWith((ref) async => price),
        pawCoinProvider.overrideWith(_Paw.new),
      ],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: page,
      ),
    ));
    await tester.pumpAndSettle();
    return repo;
  }

  group('配型页锁态', () {
    testWidgets('已选类型、未解锁配型 → 整页上锁：不出配型卡 / 档位 / 字母对照；主按钮 Rp3.000，文字链完整解读 Rp5.000',
        (tester) async {
      await pump(tester, const TailsonalityMatchPage(token: 'abc'), repo: _Repo(result()));
      expect(find.byKey(const ValueKey('tsMatchLockedView')), findsOneWidget);
      expect(find.byKey(const ValueKey('tsMatchResultView')), findsNothing);
      expect(find.byType(TsMatchCard), findsNothing);
      expect(find.byType(TsLetterCompare), findsNothing);
      expect(find.text('Counterweight'), findsNothing, reason: 'ENTJ vs INFP 的档位是付费内容');
      expect(find.text('Seberapa cocok kamu sama Momo?'), findsOneWidget);
      expect(find.text('Buka kecocokan Rp3.000'), findsOneWidget);
      expect(find.text('Atau buka analisis lengkap Rp5.000, sudah termasuk kecocokan'), findsOneWidget);
      expect(find.byKey(const ValueKey('tsMatchBragCta')), findsNothing);
      expect(events.where((e) => e.$1 == 'tailsonality_match_unlock_viewed'), hasLength(1));
    });

    testWidgets('未选类型时先选（免费），不出锁态', (tester) async {
      await pump(tester, const TailsonalityMatchPage(token: 'abc'), repo: _Repo(result()), owner: null);
      expect(find.byKey(const ValueKey('tsMatchSelectorView')), findsOneWidget);
      expect(find.byKey(const ValueKey('tsMatchLockedView')), findsNothing);
    });

    testWidgets('锁态下仍可改类型（免费）', (tester) async {
      await pump(tester, const TailsonalityMatchPage(token: 'abc'), repo: _Repo(result()));
      await tester.tap(find.byKey(const ValueKey('tsMatchChangeType')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tsMatchSelectorView')), findsOneWidget);
    });

    testWidgets('旧后端无配型价 → 主按钮位显示重试，不猜价', (tester) async {
      await pump(tester, const TailsonalityMatchPage(token: 'abc'),
          repo: _Repo(result()),
          price: const KeepsakePricing(ktpHd: 10000, passportSnapshot: 2000, boardingPass: 1000, tailsonality: 5000));
      expect(find.byKey(const ValueKey('tsMatchUnlockPriceRetry')), findsOneWidget);
      expect(find.byKey(const ValueKey('tsMatchUnlockCta')), findsNothing);
    });

    testWidgets('PawCoin 单买配型 → 调 match-unlock、切结果视图、toast；埋点 product=match', (tester) async {
      final repo = await pump(tester, const TailsonalityMatchPage(token: 'abc'), repo: _Repo(result()));
      await tester.tap(find.byKey(const ValueKey('tsMatchUnlockCta')));
      await tester.pumpAndSettle();
      expect(find.text('Buka kecocokan'), findsOneWidget);
      expect(find.text('Bayar Rp3.000'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tsMatchPayConfirm')));
      await tester.pumpAndSettle();

      expect(repo.matchCalls, [HdPayChannel.pawcoin]);
      expect(repo.unlockCalls, isEmpty);
      expect(find.text('Kecocokan sudah kebuka!'), findsOneWidget);
      expect(find.byKey(const ValueKey('tsMatchResultView')), findsOneWidget);
      expect(find.text('Counterweight'), findsOneWidget);
      final init = events.where((e) => e.$1 == 'tailsonality_match_unlock_initiated').single.$2!;
      expect(init['product'], 'match');
      expect(init['price'], 3000);
    });

    testWidgets('文字链买完整解读 → 调 unlock（不是 match-unlock），配型随之解锁', (tester) async {
      final repo = await pump(tester, const TailsonalityMatchPage(token: 'abc'), repo: _Repo(result()));
      await tester.tap(find.byKey(const ValueKey('tsMatchFullUnlockLink')));
      await tester.pumpAndSettle();
      expect(find.text('Buka analisis lengkap'), findsOneWidget);
      expect(find.text('Bayar Rp5.000'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tsMatchPayConfirm')));
      await tester.pumpAndSettle();

      expect(repo.unlockCalls, [HdPayChannel.pawcoin]);
      expect(repo.matchCalls, isEmpty);
      expect(find.text('Hasil sudah kebuka!'), findsOneWidget);
      expect(find.byKey(const ValueKey('tsMatchResultView')), findsOneWidget);
      expect(events.where((e) => e.$1 == 'tailsonality_match_unlock_initiated').single.$2!['product'], 'full');
    });

    testWidgets('完整解读已解锁的结果：配型直接可看，无锁态', (tester) async {
      await pump(tester, const TailsonalityMatchPage(token: 'abc'),
          repo: _Repo(result(unlocked: true, matchUnlocked: true)));
      expect(find.byKey(const ValueKey('tsMatchResultView')), findsOneWidget);
      expect(find.byKey(const ValueKey('tsMatchLockedView')), findsNothing);
    });
  });

  group('结果页补差价', () {
    testWidgets('已单独买配型 → 购买按钮与抽屉都按服务端补差价 Rp2.000，抽屉说明改为补差文案', (tester) async {
      final repo = await pump(tester, const TailsonalityResultPage(token: 'abc'),
          repo: _Repo(result(matchUnlocked: true, upgradePrice: 2000)));
      expect(find.text('Buka Rp2.000'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tsUnlockCta')));
      await tester.pumpAndSettle();
      expect(find.text('Bayar Rp2.000'), findsOneWidget);
      expect(find.textContaining('tinggal bayar selisihnya'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tsPayConfirm')));
      await tester.pumpAndSettle();
      expect(repo.unlockCalls, [HdPayChannel.pawcoin]);
      expect(events.where((e) => e.$1 == 'tailsonality_unlock_initiated').single.$2!['price'], 2000);
    });

    testWidgets('没买过配型 → 原价 Rp5.000，抽屉说明里写明含配型', (tester) async {
      await pump(tester, const TailsonalityResultPage(token: 'abc'), repo: _Repo(result()));
      expect(find.text('Buka Rp5.000'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tsUnlockCta')));
      await tester.pumpAndSettle();
      expect(find.textContaining('kecocokan kamu sama Momo'), findsOneWidget);
    });
  });
}

class _Repo implements TailsonalityRepository {
  _Repo(this.current);

  TailsonalityResult current;
  final List<HdPayChannel> unlockCalls = [];
  final List<HdPayChannel> matchCalls = [];

  @override
  Future<TailsonalityResult> fetchResult(String token) async => current;

  @override
  Future<List<TailsonalityResult>> fetchResults() async => [current];

  @override
  Future<KeepsakePurchaseResult> unlock(String token, HdPayChannel channel) async {
    unlockCalls.add(channel);
    current = _with(current, unlocked: true);
    return const KeepsakePurchaseResult(unlocked: true, purchaseToken: 'kp');
  }

  @override
  Future<KeepsakePurchaseResult> unlockMatch(String token, HdPayChannel channel) async {
    matchCalls.add(channel);
    current = _with(current, unlocked: current.unlocked);
    return const KeepsakePurchaseResult(unlocked: true, purchaseToken: 'km');
  }

  /// 服务端口径：完整解读解锁或单买配型后 matchUnlocked 均为 true；完整解读解锁后补差价消失。
  static TailsonalityResult _with(TailsonalityResult r, {required bool unlocked}) => TailsonalityResult(
        token: r.token,
        typeCode: r.typeCode,
        letters: r.letters,
        energy: r.energy,
        questionSet: r.questionSet,
        resultIndex: r.resultIndex,
        unlocked: unlocked,
        unlockedAt: unlocked ? DateTime.utc(2026, 10, 9) : null,
        matchUnlocked: true,
        upgradePrice: unlocked ? null : 2000,
        contentVersion: r.contentVersion,
        createdAt: r.createdAt,
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _OwnerRepo implements TailsonalityOwnerTypeRepository {
  _OwnerRepo(this.owner);

  final String? owner;

  @override
  Future<String?> fetch() async => owner;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Paw extends PawCoinController {
  @override
  Future<PawCoinState> build() => Future.value(const PawCoinState(balance: 20000, items: []));
}
