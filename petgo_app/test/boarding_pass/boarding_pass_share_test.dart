import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/presentation/brag_post_entry.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/domain/media_upload_use_case.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/boarding_pass/data/boarding_pass_repository.dart';
import 'package:tailtopia/features/boarding_pass/domain/boarding_pass.dart';
import 'package:tailtopia/features/boarding_pass/presentation/boarding_pass_detail_page.dart';
import 'package:tailtopia/features/boarding_pass/presentation/share/boarding_pass_share_card.dart';
import 'package:tailtopia/features/boarding_pass/presentation/widgets/boarding_pass_card.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_preview_page.dart';
import 'package:tailtopia/features/keepsake/data/keepsake_repository.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_pricing.dart';
import 'package:tailtopia/features/profile/domain/card_link.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_qr.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';

/// V1.3.2 Story 4.3：登机牌卡分享（L0 部分）。
void main() {
  Map<String, dynamic> detailJson({bool unlocked = false, String status = 'ACTIVE'}) => {
        'placeToken': 'p' * 32,
        'passenger': 'Momo',
        'breed': 'Anggora',
        'placeName': 'Taman Menteng',
        'passportNo': 'TT02P2600128',
        'lastVisitDate': '2026-09-28',
        'firstVisitDate': '2026-09-01',
        'visitCount': 3,
        'seat': '02A',
        'placeType': 'PARK',
        'placeStatus': status,
        'unlocked': unlocked,
      };

  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pumpDetail(WidgetTester tester, {bool unlocked = false, String status = 'ACTIVE'}) async {
    tester.view.physicalSize = const Size(400, 1200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [
        boardingPassRepositoryProvider.overrideWithValue(
            _Repo(BoardingPassDetail.fromJson(detailJson(unlocked: unlocked, status: status)))),
        keepsakePricingProvider.overrideWith((ref) async =>
            const KeepsakePricing(ktpHd: 10000, passportSnapshot: 2000, boardingPass: 1000, tailsonality: 5000)),
        authControllerProvider.overrideWith(_Auth.new),
        mediaUploadUseCaseProvider.overrideWithValue(_Media()),
        contentRepositoryProvider.overrideWithValue(_ContentRepo()),
      ],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: BoardingPassDetailPage(placeToken: 'p' * 32),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> openShare(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('boardingPassShare')));
    await tester.pumpAndSettle();
  }

  testWidgets('顶栏分享按钮两态都有、热区 ≥44；底部 CTA 位不动', (tester) async {
    await pumpDetail(tester);
    expect(find.byKey(const ValueKey('boardingPassShare')), findsOneWidget);
    expect(tester.getSize(find.byKey(const ValueKey('boardingPassShare'))).height, greaterThanOrEqualTo(44));
    expect(find.byKey(const ValueKey('boardingPassUnlockCta')), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await pumpDetail(tester, unlocked: true);
    expect(find.byKey(const ValueKey('boardingPassShare')), findsOneWidget);
    expect(find.byKey(const ValueKey('boardingPassUnlockCta')), findsNothing);
  });

  testWidgets('未解锁 → 预览带水印；卡面复用整张登机牌、信息段两行、码指向 /get', (tester) async {
    await pumpDetail(tester);
    await openShare(tester);
    expect(find.byType(BoardingPassShareCard), findsOneWidget);
    expect(tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).watermarked, isTrue);
    // 预览页上只有整卡外层那一层水印（卡内不再叠一层）。
    expect(find.byType(CardWatermark), findsOneWidget);
    expect(find.descendant(of: find.byType(BoardingPassShareCard), matching: find.byType(CardWatermark)), findsNothing);
    expect(find.descendant(of: find.byType(BoardingPassShareCard), matching: find.byType(BoardingPassCard)),
        findsOneWidget);
    expect(find.text('Momo · Taman Menteng'), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassShareCardMeta'))).data, endsWith(' · 3×'));
    expect(tester.widget<CardQr>(find.byType(CardQr)).data, petDownloadUrl());
    expect(find.byKey(const ValueKey('shareCardRatioToggle')), findsNothing);
    // PASSPORT 12 位在卡面上完整。
    expect(
        tester
            .widget<Text>(find.descendant(
                of: find.byType(BoardingPassShareCard), matching: find.byKey(const ValueKey('boardingPassPassportNo'))))
            .data,
        'TT02P2600128');
  });

  testWidgets('已解锁 → 无水印', (tester) async {
    await pumpDetail(tester, unlocked: true);
    await openShare(tester);
    expect(tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).watermarked, isFalse);
    expect(find.byType(CardWatermark), findsNothing);
  });

  testWidgets('场所已下架：照常可分享、场所名照常显示', (tester) async {
    await pumpDetail(tester, unlocked: true, status: 'UNAVAILABLE');
    await openShare(tester);
    expect(find.text('Momo · Taman Menteng'), findsOneWidget);
  });

  testWidgets('passport_card_shared：分享成功回调才报；stamp_count = 当前总章数（登机牌列表条目数）', (tester) async {
    ShareCardPreviewPage.captureForTest = (_) async => Uint8List.fromList(const [1, 2, 3]);
    addTearDown(() => ShareCardPreviewPage.captureForTest = null);
    await pumpDetail(tester);
    await openShare(tester);
    await tester.tap(find.byKey(const ValueKey('shareCardShareCta')));
    await tester.pumpAndSettle();
    expect(events.where((e) => e.$1 == 'passport_card_shared'), isEmpty);

    tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).onShared!('other');
    expect(events.where((e) => e.$1 == 'passport_card_shared').single.$2, {'stamp_count': 2});
  });

  group('Story 4.4 · B3c「Pamer di postingan」', () {
    tearDown(() {
      BoardingPassDetailPage.captureForTest = null;
      BragPostEntry.prepareForTest = null;
    });

    testWidgets('未解锁 B3b 不出；已解锁 B3c 吸底出 → 截卡 → 发帖页（带宠物名与场所名）', (tester) async {
      await pumpDetail(tester);
      expect(find.byKey(const ValueKey('boardingPassBragCta')), findsNothing);

      final png = Uint8List.fromList(img.encodePng(img.Image(width: 30, height: 46)));
      Uint8List? prepared;
      BoardingPassDetailPage.captureForTest = () async => png;
      BragPostEntry.prepareForTest = (p) async {
        prepared = p;
        return Uint8List.fromList(img.encodeJpg(img.Image(width: 30, height: 46)));
      };
      await tester.pumpWidget(const SizedBox());
      await pumpDetail(tester, unlocked: true);
      expect(find.byKey(const ValueKey('boardingPassBragCta')), findsOneWidget);
      expect(find.byKey(const ValueKey('boardingPassUnlockCta')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('boardingPassBragCta')));
      await tester.pumpAndSettle();
      expect(prepared, png);
      expect(find.byType(PublishComposePage), findsOneWidget);
      expect(tester.widget<TextField>(find.byKey(const ValueKey('publishText'))).controller!.text,
          'Momo udah sampai di Taman Menteng! Ini boarding pass-nya ✈️');
    });
  });

}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');

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

class _ContentRepo implements ContentRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Repo implements BoardingPassRepository {
  _Repo(this.current);

  final BoardingPassDetail current;

  @override
  Future<BoardingPassList> list() async => BoardingPassList.fromJson({
        'petName': 'Momo',
        'passportNo': 'TT02P2600128',
        'items': [
          {'placeToken': 'a', 'placeName': 'A', 'placeStatus': 'ACTIVE', 'visitCount': 1},
          {'placeToken': 'b', 'placeName': 'B', 'placeStatus': 'ACTIVE', 'visitCount': 2},
        ],
      });

  @override
  Future<BoardingPassDetail> detail(String placeToken) async => current;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
