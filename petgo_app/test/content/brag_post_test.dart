import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
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
