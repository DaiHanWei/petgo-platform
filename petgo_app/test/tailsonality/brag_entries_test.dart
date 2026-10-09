import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/presentation/brag_post_entry.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_preview_page.dart';
import 'package:tailtopia/features/keepsake/data/keepsake_repository.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_pricing.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/domain/media_upload_use_case.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_owner_type_repository.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_providers.dart';
import 'package:tailtopia/features/tailsonality/data/ts_remote_art.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_match_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_result_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_match_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';

/// V1.3.2 Story 4.4 · AC3：Tailsonality 三处「Pamer di postingan」→ 截卡 → BragPostEntry → 发帖页（带图带字、Momen）。
void main() {
  final cardPng = Uint8List.fromList(img.encodePng(img.Image(width: 30, height: 40)));
  final jpeg = Uint8List.fromList(img.encodeJpg(img.Image(width: 30, height: 40)));

  TailsonalityResult result({bool unlocked = false}) => TailsonalityResult(
        token: 'abc',
        typeCode: 'ENTJ-H',
        letters: 'ENTJ',
        energy: 'H',
        questionSet: 'CAT',
        resultIndex: 1,
        unlocked: unlocked,
        matchUnlocked: true,
        contentVersion: 1,
        createdAt: DateTime.utc(2026, 9, 30),
      );

  late Uint8List? prepared;
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    prepared = null;
    BragPostEntry.prepareForTest = (png) async {
      prepared = png;
      return jpeg;
    };
    // 配型卡按需下载：装成「下到了」，预览才会打开。
    TsRemoteArt.debugLoader = (_) async => Uint8List.fromList(const [1, 2, 3]);
  });
  tearDown(() {
    TsRemoteArt.debugReset();
    TsRemoteArt.debugLoader = (_) async => null;
    BragPostEntry.prepareForTest = null;
    TailsonalityResultPage.captureForTest = null;
    TailsonalityMatchPage.captureForTest = null;
  });

  List common({bool unlocked = false}) => [
        authControllerProvider.overrideWith(_Auth.new),
        mediaUploadUseCaseProvider.overrideWithValue(_Media()),
        contentRepositoryProvider.overrideWithValue(_Repo()),
        petProfileProvider.overrideWith(
            (ref) async => const PetProfile(id: 1, name: 'Momo', cardToken: 't', petType: 'CAT', breed: 'Anggora')),
        tailsonalityOwnerTypeRepositoryProvider.overrideWithValue(_OwnerRepo('INFP')),
        keepsakePricingProvider.overrideWith((ref) async =>
            const KeepsakePricing(ktpHd: 10000, passportSnapshot: 2000, boardingPass: 1000, tailsonality: 5000)),
        tailsonalityResultProvider('abc').overrideWith((ref) async => result(unlocked: unlocked)),
      ];

  Future<void> pump(WidgetTester tester, Widget home, {bool unlocked = false}) async {
    tester.view.physicalSize = const Size(420, 2600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [...common(unlocked: unlocked)],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: home,
      ),
    ));
    await tester.pumpAndSettle();
  }

  void expectCompose(WidgetTester tester, String text) {
    expect(find.byType(PublishComposePage), findsOneWidget);
    expect(tester.widget<TextField>(find.byKey(const ValueKey('publishText'))).controller!.text, text);
    expect(prepared, cardPng, reason: '发帖用的是本页截的 3:4 卡图');
  }

  testWidgets('结果页 ⋯「Pamer di postingan」：未解锁截含水印外层 → 发帖页预填', (tester) async {
    bool? watermarked;
    TailsonalityResultPage.captureForTest = (k) async {
      watermarked = find.descendant(of: find.byKey(k), matching: find.byType(CardWatermark)).evaluate().isNotEmpty;
      return cardPng;
    };
    await pump(tester, const TailsonalityResultPage(token: 'abc'));
    await tester.tap(find.byKey(const ValueKey('tsResultMore')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('tsMenuBrag')));
    await tester.pumpAndSettle();
    expect(watermarked, isTrue, reason: '未解锁发帖图同样带水印（否则发个帖就拿到干净卡）');
    expectCompose(tester, 'Aku baru tes kepribadian anabulku, yuk lihat Tailsonality-nya apa!');
  });

  testWidgets('结果页已解锁：截内层（无水印）', (tester) async {
    bool? watermarked;
    TailsonalityResultPage.captureForTest = (k) async {
      watermarked = find.descendant(of: find.byKey(k), matching: find.byType(CardWatermark)).evaluate().isNotEmpty;
      return cardPng;
    };
    await pump(tester, const TailsonalityResultPage(token: 'abc'), unlocked: true);
    await tester.tap(find.byKey(const ValueKey('tsResultMore')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('tsMenuBrag')));
    await tester.pumpAndSettle();
    expect(watermarked, isFalse);
    expect(find.byType(PublishComposePage), findsOneWidget);
  });

  testWidgets('配型页底部主按钮 → 发帖页（配型文案）；点击即报 brag_post_tapped', (tester) async {
    final events = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    addTearDown(() => Analytics.debugCaptureSink = null);
    TailsonalityMatchPage.captureForTest = () async => cardPng;
    await pump(tester, const TailsonalityMatchPage(token: 'abc'));
    await tester.tap(find.byKey(const ValueKey('tsMatchBragCta')));
    await tester.pumpAndSettle();
    expectCompose(tester, 'Aku tes kecocokan aku sama anabulku, lihat kartu hasilnya!');
    // 待确认 4.12（2026-10-02）。
    expect(events.where((e) => e.$1 == 'brag_post_tapped').single.$2, {'source': 'tailsonality_match'});
  });

  testWidgets('配型卡预览：有 1:1 卡图 → 主操作 Pamer（FilledButton）+ 分享降为次按钮；点主操作进发帖页', (tester) async {
    TailsonalityMatchPage.captureForTest = () async => cardPng;
    await pump(tester, const TailsonalityMatchPage(token: 'abc'));
    await tester.tap(find.byKey(const ValueKey('tsMatchCardTap')));
    await tester.pumpAndSettle();
    expect(find.byType(ShareCardPreviewPage), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).key, const ValueKey('matchPreviewBrag'));
    expect(tester.widget<OutlinedButton>(find.byType(OutlinedButton)).key, const ValueKey('shareCardShareCta'));
    expect(find.text('Pamer di postingan'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('matchPreviewBrag')));
    await tester.pumpAndSettle();
    expectCompose(tester, 'Aku tes kecocokan aku sama anabulku, lihat kartu hasilnya!');
  });

  /// code-review 修复：按钮吸底常驻，卡在列表首项 —— 滚远后点「Pamer」须先滚回顶部再截，截不到给轻提示。
  testWidgets('配型页滚到底再点 Pamer：截图时卡已回到屏上', (tester) async {
    tester.view.physicalSize = const Size(420, 700);
    bool? cardOnScreen;
    TailsonalityMatchPage.captureForTest = () async {
      cardOnScreen = find.byType(TsMatchCard).hitTestable().evaluate().isNotEmpty;
      return cardPng;
    };
    await pump(tester, const TailsonalityMatchPage(token: 'abc'));
    tester.view.physicalSize = const Size(420, 700);
    await tester.pumpAndSettle();
    await tester.drag(find.byKey(const ValueKey('tsMatchResultView')), const Offset(0, -3000));
    await tester.pumpAndSettle();
    expect(find.byType(TsMatchCard).hitTestable(), findsNothing, reason: '前提：卡已滚出屏');
    await tester.tap(find.byKey(const ValueKey('tsMatchBragCta')));
    await tester.pumpAndSettle();
    expect(cardOnScreen, isTrue);
    expect(find.byType(PublishComposePage), findsOneWidget);
  });

  testWidgets('截不到卡图 → 轻提示、不进发帖页', (tester) async {
    TailsonalityMatchPage.captureForTest = () async => null;
    await pump(tester, const TailsonalityMatchPage(token: 'abc'));
    await tester.tap(find.byKey(const ValueKey('tsMatchBragCta')));
    await tester.pumpAndSettle();
    expect(find.byType(PublishComposePage), findsNothing);
    expect(find.text('Gagal membuat kartu. Coba lagi ya.'), findsOneWidget);
    await tester.pump(const Duration(seconds: 5));
  });
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
      status: AuthStatus.authenticated, role: 'USER', profile: UserProfile(nickname: 'Aurel', hasPetProfile: true));

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

class _Media implements MediaUploadUseCase {
  @override
  Future<OssUploadResult> uploadBytes({required MediaScope scope, required Uint8List bytes}) async =>
      const OssUploadResult(objectKey: 'k', publicUrl: 'https://cdn/x.jpg');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Repo implements ContentRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _OwnerRepo implements TailsonalityOwnerTypeRepository {
  _OwnerRepo(this.value);

  final String? value;

  @override
  Future<String?> fetch() async => value;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

