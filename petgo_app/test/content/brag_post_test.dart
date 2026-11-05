import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/brag_post_image.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/brag_post_entry.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/domain/media_upload_use_case.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/utils/image_processor.dart';

/// V1.3.2 Story 4.4：一键发帖炫耀（发帖页预填 + 卡图转 JPEG）。
void main() {
  Uint8List png(int w, int h) =>
      Uint8List.fromList(img.encodePng(img.Image(width: w, height: h)..clear(img.ColorRgb8(255, 255, 255))));

  bool isJpeg(Uint8List b) => b.length > 3 && b[0] == 0xFF && b[1] == 0xD8;

  group('AC2.2 bragPostImageSync（纯函数）', () {
    test('1080×1440 PNG → JPEG，尺寸不变', () {
      final out = bragPostImageSync(png(1080, 1440));
      expect(isJpeg(out), isTrue);
      final d = img.decodeJpg(out)!;
      expect((d.width, d.height), (1080, 1440));
    });

    test('1080×1920（过高）→ 补边到比例 0.75，不裁卡面', () {
      final d = img.decodeJpg(bragPostImageSync(png(1080, 1920)))!;
      expect(d.height, 1920);
      expect(d.width / d.height, closeTo(kFeedRatioMin, 0.001));
    });

    test('2000×800（过宽）→ 补边到比例 1.78', () {
      final d = img.decodeJpg(bragPostImageSync(png(2000, 800)))!;
      expect(d.width, 2000);
      expect(d.width / d.height, closeTo(kFeedRatioMax, 0.005));
      expect(d.width / d.height, lessThanOrEqualTo(kFeedRatioMax));
    });

    test('输出不含 EXIF', () {
      final out = bragPostImageSync(png(1080, 1920));
      expect(img.decodeJpg(out)!.exif.isEmpty, isTrue);
      // APP1「Exif」段标记不应出现。
      final exifTag = [0x45, 0x78, 0x69, 0x66, 0x00, 0x00];
      var found = false;
      for (var i = 0; i + exifTag.length <= out.length && !found; i++) {
        var hit = true;
        for (var j = 0; j < exifTag.length; j++) {
          if (out[i + j] != exifTag[j]) {
            hit = false;
            break;
          }
        }
        found = hit;
      }
      expect(found, isFalse);
    });

    test('解不开 → ImageProcessingException', () {
      expect(() => bragPostImageSync(Uint8List.fromList(const [1, 2, 3])), throwsA(isA<ImageProcessingException>()));
    });
  });

  group('AC1 发帖页预填', () {
    late _Media media;
    late ProviderContainer container;

    Uint8List jpeg() => Uint8List.fromList(img.encodeJpg(img.Image(width: 30, height: 40)));

    Future<void> pumpOpener(WidgetTester tester, {String? text, List<Uint8List>? images}) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      media = _Media();
      container = ProviderContainer(overrides: [
        mediaUploadUseCaseProvider.overrideWithValue(media),
        contentRepositoryProvider.overrideWithValue(_OkRepo()),
        authControllerProvider.overrideWith(_Auth.new),
        // V1.4.2 Story 3.1：里程碑来源以 Diary 打开，宠物 chips 读这里（不打网络）。
        petListProvider.overrideWith((ref) async => const [
              PetListItem(id: 1, name: 'Orenji'),
              PetListItem(id: 2, name: 'Kopi'),
            ]),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Builder(
            builder: (context) => Scaffold(
              body: Column(children: [
                TextButton(
                  key: const ValueKey('openPrefilled'),
                  onPressed: () => PublishComposePage.open(context,
                      preset: ContentType.daily, initialText: text, initialImages: images),
                  child: const Text('prefilled'),
                ),
                TextButton(
                  key: const ValueKey('openPlain'),
                  onPressed: () => PublishComposePage.open(context),
                  child: const Text('plain'),
                ),
              ]),
            ),
          ),
        ),
      ));
    }

    Future<void> open(WidgetTester tester, String key) async {
      await tester.tap(find.byKey(ValueKey(key)));
      await tester.pumpAndSettle();
    }

    String fieldText(WidgetTester tester) =>
        tester.widget<TextField>(find.byKey(const ValueKey('publishText'))).controller!.text;

    PublishController controller() => container.read(publishControllerProvider);

    testWidgets('预填文字 + 1 张图：不经相册（pickMultiAndProcess 0 次）、uploadBytes 1 次；类型 Momen', (tester) async {
      await pumpOpener(tester, text: 'Halo', images: [jpeg()]);
      await open(tester, 'openPrefilled');
      expect(fieldText(tester), 'Halo');
      final c = controller();
      expect(c.text, 'Halo', reason: '文字框与 controller 必须一致');
      expect(c.items, hasLength(1));
      expect(c.type, ContentType.daily);
      expect(media.picks, 0);
      expect(media.uploads, 1);
    });

    testWidgets('超 1000 字截断；超 9 张丢弃', (tester) async {
      await pumpOpener(tester, text: 'a' * 1200, images: List.generate(12, (_) => jpeg()));
      await open(tester, 'openPrefilled');
      expect(fieldText(tester).length, kMaxPostTextLength);
      expect(controller().text.length, kMaxPostTextLength);
      expect(controller().items, hasLength(kMaxImages));
      expect(media.uploads, kMaxImages);
    });

    testWidgets('关闭即清空：带预填打开 → 关闭 → 不带参数再开 → 文字与图片都为空', (tester) async {
      await pumpOpener(tester, text: 'Halo', images: [jpeg()]);
      await open(tester, 'openPrefilled');
      await tester.tap(find.byKey(const ValueKey('publishClose')));
      await tester.pumpAndSettle();
      await open(tester, 'openPlain');
      expect(fieldText(tester), isEmpty);
      expect(controller().text, isEmpty);
      expect(controller().items, isEmpty);
    });

    testWidgets('BragPostEntry.open：转换后带图带字打开发帖页', (tester) async {
      BragPostEntry.prepareForTest = (_) async => jpeg();
      addTearDown(() => BragPostEntry.prepareForTest = null);
      await pumpOpener(tester);
      final ctx = tester.element(find.byKey(const ValueKey('openPlain')));
      BragPostEntry.open(ctx, cardPng: png(10, 10), text: 'Pamer');
      await tester.pumpAndSettle();
      expect(fieldText(tester), 'Pamer');
      expect(controller().items, hasLength(1));
      expect(controller().type, ContentType.daily);
      expect(media.picks, 0);
    });

    testWidgets('BragPostEntry.open：转换失败 → 轻提示、不打开发帖页', (tester) async {
      BragPostEntry.prepareForTest = (_) async => throw const ImageProcessingException('x');
      addTearDown(() => BragPostEntry.prepareForTest = null);
      await pumpOpener(tester);
      final ctx = tester.element(find.byKey(const ValueKey('openPlain')));
      BragPostEntry.open(ctx, cardPng: png(10, 10), text: 'Pamer');
      await tester.pumpAndSettle();
      expect(find.byType(PublishComposePage), findsNothing);
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.shareCardExportError), findsOneWidget);
      await tester.pump(const Duration(seconds: 5));
    });

    // ---- V1.4.2 social-topic Story 3.1 · AC3 / AC4（D-21 · G-6）----

    PublishComposePage page(WidgetTester tester) => tester.widget<PublishComposePage>(find.byType(PublishComposePage));

    testWidgets('回归：open 默认参数 → Moment、不预选宠物、无来源（4 个老来源调用点不改）', (tester) async {
      BragPostEntry.prepareForTest = (_) async => jpeg();
      addTearDown(() => BragPostEntry.prepareForTest = null);
      await pumpOpener(tester);
      final ctx = tester.element(find.byKey(const ValueKey('openPlain')));
      BragPostEntry.open(ctx, cardPng: png(10, 10), text: 'Pamer');
      await tester.pumpAndSettle();
      expect(page(tester).preset, ContentType.daily);
      expect(page(tester).initialPetIds, isNull);
      expect(page(tester).entrySource, isNull);
      expect(page(tester).milestoneCode, isNull);
      expect(page(tester).initialImages, hasLength(1));
    });

    testWidgets('openPrepared：Diary + 预选宠物 + 来源透传；不带 milestoneCode', (tester) async {
      await pumpOpener(tester);
      final ctx = tester.element(find.byKey(const ValueKey('openPlain')));
      final j = jpeg();
      BragPostEntry.openPrepared(ctx,
          jpeg: j,
          text: 'Orenji baru membuka tonggak',
          preset: ContentType.growthMoment,
          petIds: const [2],
          source: BragPostSource.milestoneCelebration);
      await tester.pumpAndSettle();
      expect(page(tester).preset, ContentType.growthMoment);
      expect(page(tester).initialPetIds, [2]);
      expect(page(tester).entrySource, BragPostSource.milestoneCelebration);
      expect(page(tester).milestoneCode, isNull, reason: '🔴 绝不传 milestoneCode（否则新帖被关联成打卡内容）');
      expect(page(tester).initialImages!.single, same(j));
      expect(controller().type, ContentType.growthMoment);
      expect(controller().petIds, [2]);
      expect(fieldText(tester), 'Orenji baru membuka tonggak');
      expect(controller().items, hasLength(1));
    });

    testWidgets('open 带新参数 = prepare + openPrepared', (tester) async {
      BragPostEntry.prepareForTest = (_) async => jpeg();
      addTearDown(() => BragPostEntry.prepareForTest = null);
      await pumpOpener(tester);
      final ctx = tester.element(find.byKey(const ValueKey('openPlain')));
      BragPostEntry.open(ctx,
          cardPng: png(10, 10),
          text: 'x',
          preset: ContentType.growthMoment,
          petIds: const [1, 2],
          source: BragPostSource.milestoneCelebration);
      await tester.pumpAndSettle();
      expect(page(tester).preset, ContentType.growthMoment);
      expect(page(tester).initialPetIds, [1, 2]);
      expect(page(tester).entrySource, BragPostSource.milestoneCelebration);
    });

    testWidgets('prepare 成功 → 返回 JPEG、不开发布页', (tester) async {
      final j = jpeg();
      BragPostEntry.prepareForTest = (_) async => j;
      addTearDown(() => BragPostEntry.prepareForTest = null);
      await pumpOpener(tester);
      final ctx = tester.element(find.byKey(const ValueKey('openPlain')));
      final out = await BragPostEntry.prepare(ctx, png(10, 10));
      await tester.pumpAndSettle();
      expect(out, same(j));
      expect(find.byType(PublishComposePage), findsNothing);
      expect(BragPostEntry.busy, isFalse);
    });

    testWidgets('prepare 失败 → 轻提示（抬高 extraBottom）、返回 null、不开发布页', (tester) async {
      BragPostEntry.prepareForTest = (_) async => throw const ImageProcessingException('x');
      addTearDown(() => BragPostEntry.prepareForTest = null);
      await pumpOpener(tester);
      final ctx = tester.element(find.byKey(const ValueKey('openPlain')));
      Uint8List? out = Uint8List(1);
      BragPostEntry.prepare(ctx, png(10, 10), toastExtraBottom: 96).then((v) => out = v);
      await tester.pumpAndSettle();
      expect(out, isNull);
      expect(find.byType(PublishComposePage), findsNothing);
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.shareCardExportError), findsOneWidget);
      expect(BragPostEntry.busy, isFalse);
      await tester.pump(const Duration(seconds: 5));
    });

    testWidgets('prepare 进行中再调 → 直接 null（_busy 防重入）', (tester) async {
      final gate = Completer<Uint8List>();
      BragPostEntry.prepareForTest = (_) => gate.future;
      addTearDown(() => BragPostEntry.prepareForTest = null);
      await pumpOpener(tester);
      final ctx = tester.element(find.byKey(const ValueKey('openPlain')));
      final first = BragPostEntry.prepare(ctx, png(10, 10));
      expect(BragPostEntry.busy, isTrue);
      expect(await BragPostEntry.prepare(ctx, png(10, 10)), isNull);
      gate.complete(jpeg());
      expect(await first, isNotNull);
      expect(BragPostEntry.busy, isFalse);
    });
  });

  group('AC5 reportTap', () {
    late List<(String, Map<String, Object>?)> events;
    setUp(() {
      events = [];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    test('不带 extra → 属性只有 source', () {
      BragPostEntry.reportTap(BragPostSource.boardingPass);
      expect(events.single.$1, 'brag_post_tapped');
      expect(events.single.$2, {'source': 'boarding_pass'});
    });

    test('里程碑来源带 code / level → 合并进属性', () {
      BragPostEntry.reportTap(BragPostSource.milestoneCelebration, extra: {'code': 'C-S1', 'level': 'S'});
      expect(events.single.$1, 'brag_post_tapped');
      expect(events.single.$2, {'code': 'C-S1', 'level': 'S', 'source': 'milestone_celebration'});
    });

    test('BragPostSource 值域：原 4 个 + milestone_celebration', () {
      expect(BragPostSource.values.map((s) => s.wire), [
        'tailsonality_result',
        'tailsonality_match',
        'match_preview',
        'boarding_pass',
        'milestone_celebration',
      ]);
    });
  });
}

class _Media implements MediaUploadUseCase {
  int picks = 0;
  int uploads = 0;

  @override
  Future<List<Uint8List>> pickMultiAndProcess({required int limit, BuildContext? context}) async {
    picks++;
    return const [];
  }

  @override
  Future<OssUploadResult> uploadBytes({required MediaScope scope, required Uint8List bytes}) async {
    uploads++;
    return const OssUploadResult(objectKey: 'k', publicUrl: 'https://cdn/x.jpg');
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _OkRepo implements ContentRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
      status: AuthStatus.authenticated, role: 'USER', profile: UserProfile(nickname: 'Aurel', hasPetProfile: true));

  @override
  Future<void> ensureRestored() => Future<void>.value();
}
