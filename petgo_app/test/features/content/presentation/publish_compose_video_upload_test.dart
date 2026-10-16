import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/content/video/video_cover_service.dart';
import 'package:tailtopia/features/content/video/video_picker.dart';
import 'package:tailtopia/features/content/video/video_post_repository.dart';
import 'package:tailtopia/features/content/video/video_spec.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _Repo implements ContentRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _PetRepo implements ProfileRepository {
  @override
  Future<PetProfile?> getMyProfile() async => const PetProfile(id: 1, name: 'Oyen', cardToken: 'T');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(nickname: 'Official', hasPetProfile: false, onboardingCompleted: true, vipLevel: 10),
      );

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

class _Cover extends VideoCoverService {
  const _Cover(this.bytes);
  final Uint8List? bytes;
  @override
  Future<Uint8List?> defaultCover(VideoDraft draft) async => bytes;
}

class _VideoRepo implements VideoPostRepository {
  bool failUpload = false;
  Completer<void>? gate;
  Object? publishError;
  int uploads = 0;

  @override
  Future<VideoUploadPolicy> requestPolicy() async =>
      const VideoUploadPolicy(host: 'https://h', key: 'video/1/k.mp4', fields: {});

  @override
  Future<void> uploadVideo(VideoUploadPolicy policy, String path,
      {required void Function(int sent, int total) onProgress, required CancelToken cancelToken}) async {
    uploads++;
    onProgress(42, 100);
    final g = gate;
    if (g != null) {
      await Future.any([
        g.future,
        cancelToken.whenCancel.then((_) =>
            throw DioException.requestCancelled(requestOptions: RequestOptions(path: '/'), reason: 'cancel')),
      ]);
    }
    if (failUpload) throw DioException(requestOptions: RequestOptions(path: '/'), type: DioExceptionType.connectionError);
  }

  @override
  Future<String> uploadCover(Uint8List jpeg) async => 'https://cdn.test/public/1/c.jpg';

  @override
  Future<int> publishVideo({
    required ContentType type,
    String? text,
    required String coverUrl,
    required VideoDraft video,
    required String objectKey,
    List<int> mentionedUserIds = const [],
    required String idempotencyKey,
  }) async {
    if (publishError != null) throw publishError!;
    return 1;
  }
}

/// L0 widget：专题线 video Story 1.6 —— 封面、进度、失败重试、关闭确认、发布 403。
void main() {
  final coverJpeg = Uint8List.fromList(img.encodeJpg(img.Image(width: 72, height: 128)));
  final entry = find.byKey(const ValueKey('publishAddVideo'));

  Future<(PublishController, _VideoRepo)> pump(WidgetTester tester, {_VideoRepo? repo, Uint8List? cover}) async {
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final r = repo ?? _VideoRepo();
    final controller = PublishController(repository: _Repo(), uploadOne: (b) async => 'x', videoRepository: r);
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(controller),
      profileRepositoryProvider.overrideWithValue(_PetRepo()),
      authControllerProvider.overrideWith(_Auth.new),
      videoPickerProvider.overrideWithValue(() async => 'test/fixtures/video/ok_avc_720x1280.mp4'),
      videoCoverServiceProvider.overrideWithValue(_Cover(cover ?? coverJpeg)),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: Builder(
          builder: (ctx) => Scaffold(
            body: Center(
              child: TextButton(
                key: const ValueKey('openCompose'),
                onPressed: () => Navigator.of(ctx).push(MaterialPageRoute<void>(
                  builder: (_) => const Scaffold(body: PublishComposePage(preset: ContentType.daily)),
                )),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('openCompose')));
    await tester.pumpAndSettle();
    return (controller, r);
  }

  Future<void> pickVideo(WidgetTester tester) async {
    await tester.runAsync(() async {
      await tester.tap(entry);
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });
    await tester.pumpAndSettle();
  }

  testWidgets('选中后自动上传：显示封面，视频与封面都成功后可发布', (tester) async {
    final (c, _) = await pump(tester);
    await pickVideo(tester);
    expect(find.byKey(const ValueKey('videoCoverImage')), findsOneWidget);
    expect(c.videoStatus, MediaUploadStatus.success);
    expect(c.coverStatus, MediaUploadStatus.success);
    expect(c.canPublish, isTrue);
  });

  testWidgets('取帧失败：提示选封面、不可发布', (tester) async {
    final repo = _VideoRepo();
    final controller = PublishController(repository: _Repo(), uploadOne: (b) async => 'x', videoRepository: repo);
    // 直接驱动控制器模拟「没有默认封面」。
    controller.setVideo(const VideoDraft(path: 'p', durationMs: 4000, width: 720, height: 1280, sizeBytes: 1));
    controller.markCoverMissing();
    await controller.startVideoUpload();
    expect(controller.canPublish, isFalse);
  });

  testWidgets('上传失败显示重试，点重试后成功', (tester) async {
    final repo = _VideoRepo()..failUpload = true;
    final (c, _) = await pump(tester, repo: repo);
    await pickVideo(tester);
    expect(c.videoStatus, MediaUploadStatus.failed);
    expect(find.byKey(const ValueKey('videoUploadRetry')), findsOneWidget);
    expect(find.text('Upload failed. Tap to retry.'), findsOneWidget);

    repo.failUpload = false;
    await tester.runAsync(() async {
      await tester.tap(find.byKey(const ValueKey('videoUploadRetry')));
      await Future<void>.delayed(const Duration(milliseconds: 100));
    });
    await tester.pumpAndSettle();
    expect(c.videoStatus, MediaUploadStatus.success);
    expect(repo.uploads, 2);
  });

  testWidgets('上传中点关闭：弹确认；继续编辑留在页面，放弃则取消上传并关闭', (tester) async {
    final repo = _VideoRepo()..gate = Completer<void>();
    final (c, _) = await pump(tester, repo: repo);
    await pickVideo(tester);
    expect(c.videoUploading, isTrue);
    expect(find.text('Uploading 42%'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('publishClose')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('videoDiscardDialog')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('videoDiscardKeep')));
    await tester.pumpAndSettle();
    expect(find.byType(PublishComposePage), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('publishClose')));
    await tester.pumpAndSettle();
    await tester.runAsync(() async {
      await tester.tap(find.byKey(const ValueKey('videoDiscardConfirm')));
      await Future<void>.delayed(const Duration(milliseconds: 100));
    });
    await tester.pumpAndSettle();
    expect(find.byType(PublishComposePage), findsNothing);
    expect(c.videoStatus, MediaUploadStatus.idle);
  });

  testWidgets('上传中点取消：显示中性「已取消」文案而非失败（code review）', (tester) async {
    final repo = _VideoRepo()..gate = Completer<void>();
    final (c, _) = await pump(tester, repo: repo);
    await pickVideo(tester);
    expect(c.videoUploading, isTrue);
    await tester.runAsync(() async {
      await tester.tap(find.byKey(const ValueKey('videoUploadCancel')));
      await Future<void>.delayed(const Duration(milliseconds: 100));
    });
    await tester.pumpAndSettle();
    expect(c.videoStatus, MediaUploadStatus.idle);
    expect(find.text('Upload failed. Tap to retry.'), findsNothing);
    expect(find.text('Upload canceled. Tap to upload.'), findsOneWidget);
  });

  testWidgets('发布遇到 403 video-permission-denied：提示且留在编辑页', (tester) async {
    final ro = RequestOptions(path: '/api/v1/content-posts');
    final repo = _VideoRepo()
      ..publishError = DioException(
        requestOptions: ro,
        type: DioExceptionType.badResponse,
        response: Response(requestOptions: ro, statusCode: 403, data: {
          'type': 'https://petgo/errors/video-permission-denied',
          'status': 403,
        }),
      );
    final (c, _) = await pump(tester, repo: repo);
    await pickVideo(tester);
    c.setText('halo');
    await tester.pump();
    await tester.runAsync(() async {
      await tester.tap(find.byKey(const ValueKey('publishSubmit')));
      await Future<void>.delayed(const Duration(milliseconds: 1200));
    });
    await tester.pumpAndSettle();
    expect(find.text("You can't post videos right now."), findsWidgets);
    expect(find.byType(PublishComposePage), findsOneWidget);
    expect(c.text, 'halo');
    expect(c.video, isNotNull);
  });
}
