import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_share_reward_repository.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_preview_page.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_skeleton.dart';
import 'package:tailtopia/features/keepsake/data/keepsake_repository.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_pricing.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/card_link.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_owner_type_repository.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_providers.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/share/result_share_card.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_result_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_result_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_canvas.dart';
import 'package:tailtopia/shared/card_render/card_qr.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';
import 'package:tailtopia/shared/media/image_lightbox.dart';
import '../keepsake/fake_share_reward_repos.dart';

/// V1.3.2 Story 4.1 · AC4 / AC5 / AC6：结果卡分享与大图（L0 部分）。

// V1.3.2 Story 4.5：领奖上报替身（每个用例重置）。
late FakeTailsonalityShareReward shareReward;

void main() {
  setUp(() => shareReward = FakeTailsonalityShareReward());
  TailsonalityResult result({bool unlocked = false}) => TailsonalityResult(
        token: 'abc',
        typeCode: 'ENTJ-H',
        letters: 'ENTJ',
        energy: 'H',
        questionSet: 'CAT',
        resultIndex: 1,
        unlocked: unlocked,
        contentVersion: 1,
        createdAt: DateTime.utc(2026, 9, 30),
      );

  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    SharedPreferences.setMockInitialValues({});
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    TailsonalityResultPage.captureForTest = null;
  });

  Future<void> pumpPage(WidgetTester tester, {bool unlocked = false, UserProfile? profile}) async {
    tester.view.physicalSize = const Size(420, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [
        tailsonalityShareRewardRepositoryProvider.overrideWithValue(shareReward),
        authControllerProvider.overrideWith(() => _FakeAuth(profile)),
        petProfileProvider.overrideWith(
            (ref) async => const PetProfile(id: 1, name: 'Momo', cardToken: 't', petType: 'CAT', breed: 'Anggora')),
        tailsonalityOwnerTypeRepositoryProvider.overrideWithValue(_OwnerRepo()),
        keepsakePricingProvider.overrideWith((ref) async =>
            const KeepsakePricing(ktpHd: 10000, passportSnapshot: 2000, boardingPass: 1000, tailsonality: 5000)),
        tailsonalityResultProvider('abc').overrideWith((ref) async => result(unlocked: unlocked)),
      ],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const TailsonalityResultPage(token: 'abc'),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> openViaMenu(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('tsResultMore')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('tsMenuShare')));
    await tester.pumpAndSettle();
  }

  group('AC4 结果卡预览', () {
    testWidgets('未解锁：⋯「Bagikan」→ 预览带水印、固定 9:16 无切换、码指向 /get', (tester) async {
      await pumpPage(tester);
      await openViaMenu(tester);

      final page = tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage));
      expect(page.watermarked, isTrue);
      expect(find.byType(CardWatermark), findsOneWidget);
      expect(find.byKey(const ValueKey('shareCardRatioToggle')), findsNothing);
      expect(find.byType(ResultShareCard), findsOneWidget);
      expect(find.byType(TsResultCardFace), findsOneWidget, reason: '主体段复用 2.4 的卡面，不另画');
      expect(tester.widget<CardQr>(find.byType(CardQr)).data, petDownloadUrl());
      expect(find.text('Pratinjau Kartu'), findsOneWidget);
    });

    testWidgets('已解锁：底部「Bagikan」→ 同一预览，无水印', (tester) async {
      await pumpPage(tester, unlocked: true);
      await tester.scrollUntilVisible(find.byKey(const ValueKey('tsResultShareCta')), 400,
          scrollable: find.descendant(of: find.byKey(const ValueKey('tsResultBody')), matching: find.byType(Scrollable)));
      await tester.tap(find.byKey(const ValueKey('tsResultShareCta')));
      await tester.pumpAndSettle();

      expect(find.byType(ResultShareCard), findsOneWidget);
      expect(tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).watermarked, isFalse);
      expect(find.byType(CardWatermark), findsNothing);
    });

    testWidgets('信息段：宠物名 + 主人昵称', (tester) async {
      await pumpPage(tester, profile: const UserProfile(nickname: 'Aurel', email: 'a@x.id'));
      await openViaMenu(tester);
      expect(find.text('Momo'), findsOneWidget);
      expect(find.text('Aurel'), findsOneWidget);
    });

    testWidgets('信息段：取不到昵称时整行不显示（不兜底邮箱、不写 Kamu）', (tester) async {
      await pumpPage(tester, profile: const UserProfile(email: 'a@x.id'));
      await openViaMenu(tester);
      expect(find.text('Momo'), findsOneWidget);
      expect(find.byKey(const ValueKey('resultShareCardOwner')), findsNothing);
      expect(find.textContaining('a@x.id'), findsNothing);
      expect(find.textContaining('Kamu'), findsNothing);
    });

    testWidgets('品牌段与帖子卡同高（骨架 15%）', (tester) async {
      await pumpPage(tester);
      await openViaMenu(tester);
      final card = tester.getRect(find.byType(ResultShareCard));
      final brand = tester.getRect(find.byKey(const ValueKey(ShareCardSkeleton.brandAreaKey)));
      expect(brand.height / card.height, closeTo(ShareCardMetrics.brandShare, 0.005));
    });
  });

  group('AC6 埋点', () {
    testWidgets('tailsonality_card_shared 只在分享成功回调时报；属性只有 role_code / is_unlocked', (tester) async {
      ShareCardPreviewPage.captureForTest = (_) async => Uint8List.fromList(const [1, 2, 3]);
      addTearDown(() => ShareCardPreviewPage.captureForTest = null);
      await pumpPage(tester);
      await openViaMenu(tester);

      // 出图 → 选单出现：此刻（未分享成功）不报。
      await tester.tap(find.byKey(const ValueKey('shareCardShareCta')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('cardShareImage')), findsOneWidget);
      expect(events.where((e) => e.$1 == 'tailsonality_card_shared'), isEmpty);

      // 系统面板成功回调（CardExport 只在 success 时调 onShared）。
      // Story 4.5：面板未回调成功（如取消）之前不上报领奖。
    expect(shareReward.calls, isEmpty);
    tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).onShared!('whatsapp');
    await tester.pump();
    expect(shareReward.calls, ['RESULT'], reason: '分享成功回调后上报一次，卡类型正确');
      final shared = events.where((e) => e.$1 == 'tailsonality_card_shared').single.$2!;
      expect(shared, {'role_code': 'ENTJ-H', 'is_unlocked': false});
    });
  });

  group('Story 4.5 领奖提示', () {
    Future<void> shareOnce(WidgetTester tester) async {
      await pumpPage(tester);
      await openViaMenu(tester);
      tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).onShared!('whatsapp');
      await tester.pump();
      await tester.pump();
    }

    testWidgets('返回 >0 → 提示「+N PawCoin」', (tester) async {
      shareReward.coins = 25;
      await shareOnce(tester);
      expect(find.text('+25 PawCoin 🎉'), findsOneWidget);
      await tester.pump(const Duration(seconds: 5));
    });

    testWidgets('返回 0 → 静默（不告知原因）', (tester) async {
      shareReward.coins = 0;
      await shareOnce(tester);
      expect(find.textContaining('PawCoin'), findsNothing);
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('上报失败 → 当 0、不报错给用户', (tester) async {
      shareReward.fail = true;
      await shareOnce(tester);
      expect(shareReward.calls, ['RESULT']);
      expect(find.byType(SnackBar), findsNothing);
      expect(tester.takeException(), isNull);
    });
  });

  group('AC5 点卡图看大图', () {
    /// 返回「截图那一刻，被截的 boundary 里有没有水印」。
    /// ⚠️ 必须在截图回调里当场数：灯箱打开后 Hero 会把源卡面换成占位，事后再找就找不到了。
    Future<bool?> tapCard(WidgetTester tester) async {
      bool? captured;
      TailsonalityResultPage.captureForTest = (k) async {
        captured = find.descendant(of: find.byKey(k), matching: find.byType(CardWatermark)).evaluate().isNotEmpty;
        return _png;
      };
      await tester.tap(find.byKey(const ValueKey('tsResultCardTap')));
      await tester.pump();
      for (var i = 0; i < 30; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      return captured;
    }

    testWidgets('未解锁：截含水印的外层 → 内存图灯箱，source=tailsonality_result', (tester) async {
      await pumpPage(tester);
      // 截的那一层把水印框在里面（大图也得带水印）。
      expect(await tapCard(tester), isTrue);
      expect(find.byType(ImageLightbox), findsOneWidget);
      final lb = tester.widget<ImageLightbox>(find.byType(ImageLightbox));
      expect(lb.images, hasLength(1));
      expect(lb.heroTagPrefix, 'tailsonality_result_abc');
      expect(events.where((e) => e.$1 == 'lightbox_opened').single.$2, {'source': 'tailsonality_result'});
    });

    testWidgets('已解锁：截内层（无水印）', (tester) async {
      await pumpPage(tester, unlocked: true);
      expect(await tapCard(tester), isFalse);
      expect(find.byType(ImageLightbox), findsOneWidget);
    });
  });

  testWidgets('ResultShareCard 按 9:16 画布出卡：主体段在上、品牌段贴底', (tester) async {
    tester.view.physicalSize = const Size(1080, 1920);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Center(
        child: SizedBox(
          width: 1080,
          height: 1920,
          child: ResultShareCard(result: result(), canvas: CardCanvas.story, petName: 'Momo'),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    final main = tester.getRect(find.byKey(const ValueKey('resultShareCardMain')));
    final brand = tester.getRect(find.byKey(const ValueKey(ShareCardSkeleton.brandAreaKey)));
    expect(main.top, lessThan(brand.top));
    expect(brand.height, closeTo(1920 * 0.15, 1));
    expect(find.byKey(const ValueKey('resultShareCardOwner')), findsNothing);
  });
}

final Uint8List _png = Uint8List.fromList(const [
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, //
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
  0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
  0x42, 0x60, 0x82,
]);

class _FakeAuth extends AuthController {
  _FakeAuth(this.profile);

  final UserProfile? profile;

  @override
  AuthState build() => AuthState(status: AuthStatus.authenticated, role: 'USER', profile: profile);

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

class _OwnerRepo implements TailsonalityOwnerTypeRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => Future<String?>.value();
}
