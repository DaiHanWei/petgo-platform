import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/video/video_post_repository.dart';
import 'package:tailtopia/features/content/video/video_spec.dart';

class _Repo implements ContentRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

DioException _problem(int status, String slug) {
  final ro = RequestOptions(path: '/x');
  return DioException(
    requestOptions: ro,
    type: DioExceptionType.badResponse,
    response: Response(requestOptions: ro, statusCode: status, data: {'type': 'https://petgo/errors/$slug', 'status': status}),
  );
}

class _VideoRepo implements VideoPostRepository {
  int policyCalls = 0;
  int uploadCalls = 0;
  int failPolicyTimes = 0;
  Object? policyError;
  bool failUpload = false;
  Completer<void>? uploadGate;
  CancelToken? lastToken;
  Object? coverError;
  final List<Uint8List> covers = [];
  Map<String, Object?>? published;

  @override
  Future<VideoUploadPolicy> requestPolicy() async {
    policyCalls++;
    if (policyError != null) throw policyError!;
    if (policyCalls <= failPolicyTimes) throw _problem(503, 'internal');
    return const VideoUploadPolicy(host: 'https://h', key: 'video/1/k.mp4', fields: {});
  }

  @override
  Future<void> uploadVideo(VideoUploadPolicy policy, String path,
      {required void Function(int sent, int total) onProgress, required CancelToken cancelToken}) async {
    uploadCalls++;
    lastToken = cancelToken;
    onProgress(50, 100);
    final gate = uploadGate;
    if (gate != null) {
      await Future.any([gate.future, cancelToken.whenCancel.then((_) => throw DioException.requestCancelled(
            requestOptions: RequestOptions(path: '/'), reason: 'cancel'))]);
    }
    if (failUpload) throw DioException(requestOptions: RequestOptions(path: '/'), type: DioExceptionType.connectionError);
    onProgress(100, 100);
  }

  @override
  Future<String> uploadCover(Uint8List jpeg) async {
    covers.add(jpeg);
    if (coverError != null) throw coverError!;
    return 'https://cdn.test/public/1/c${covers.length}.jpg';
  }

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
    published = {'type': type, 'text': text, 'coverUrl': coverUrl, 'objectKey': objectKey, 'key': idempotencyKey};
    return 99;
  }
}

/// L0：专题线 video Story 1.6 —— 视频 / 封面上传状态机、退避重试、发布分支、埋点。
void main() {
  const draft = VideoDraft(path: '/tmp/a.mp4', durationMs: 75000, width: 720, height: 1280, sizeBytes: 25000000);
  late _VideoRepo repo;
  late List<Duration> delays;
  late List<(String, Map<String, Object>?)> events;
  var uploadOneCalls = 0;

  PublishController make() => PublishController(
        repository: _Repo(),
        uploadOne: (_) async {
          uploadOneCalls++;
          return 'x';
        },
        videoRepository: repo,
        delay: (d) async => delays.add(d),
      )..setVideo(draft);

  setUp(() {
    repo = _VideoRepo();
    delays = [];
    events = [];
    uploadOneCalls = 0;
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  test('视频 + 封面都成功后才可发布（文案可为空）', () async {
    final c = make();
    expect(c.canPublish, isFalse);
    await c.startVideoUpload();
    expect(c.videoStatus, MediaUploadStatus.success);
    expect(c.videoProgress, 1);
    expect(c.videoObjectKey, 'video/1/k.mp4');
    expect(c.canPublish, isFalse); // 封面还没传
    await c.setVideoCover(Uint8List.fromList([1]));
    expect(c.coverStatus, MediaUploadStatus.success);
    expect(c.canPublish, isTrue);
  });

  test('申请凭证失败按 1/2/4 秒退避，共 4 次后失败并上报 policy', () async {
    repo.failPolicyTimes = 10;
    final c = make();
    await c.startVideoUpload();
    expect(repo.policyCalls, 4);
    expect(delays, const [Duration(seconds: 1), Duration(seconds: 2), Duration(seconds: 4)]);
    expect(c.videoStatus, MediaUploadStatus.failed);
    expect(c.videoError, VideoUploadError.policy);
    expect(events.where((e) => e.$1 == 'video_upload_failed').single.$2, {'error_type': 'policy'});
  });

  test('退避中途恢复：第 3 次成功', () async {
    repo.failPolicyTimes = 2;
    final c = make();
    await c.startVideoUpload();
    expect(repo.policyCalls, 3);
    expect(delays, const [Duration(seconds: 1), Duration(seconds: 2)]);
    expect(c.videoStatus, MediaUploadStatus.success);
  });

  test('403 / 429 不重试，错误类型分别是 permission / quota', () async {
    repo.policyError = _problem(403, 'video-permission-denied');
    final c = make();
    await c.startVideoUpload();
    expect(repo.policyCalls, 1);
    expect(c.videoError, VideoUploadError.permission);

    repo
      ..policyCalls = 0
      ..policyError = _problem(429, 'video-upload-quota-exceeded');
    final c2 = make();
    await c2.startVideoUpload();
    expect(repo.policyCalls, 1);
    expect(c2.videoError, VideoUploadError.quota);
    expect(delays, isEmpty);
  });

  test('上传失败 → 重试从头申请凭证并成功；文案与封面保留', () async {
    repo.failUpload = true;
    final c = make()..setText('halo');
    await c.setVideoCover(Uint8List.fromList([1]));
    await c.startVideoUpload();
    expect(c.videoStatus, MediaUploadStatus.failed);
    expect(c.videoError, VideoUploadError.upload);

    repo.failUpload = false;
    await c.retryVideoUpload();
    expect(repo.policyCalls, 2);
    expect(repo.uploadCalls, 2);
    expect(c.videoStatus, MediaUploadStatus.success);
    expect(c.text, 'halo');
    expect(c.coverUrl, isNotNull);
  });

  test('取消：回到 idle、不上报错误', () async {
    repo.uploadGate = Completer<void>();
    final c = make();
    final f = c.startVideoUpload();
    await Future<void>.delayed(Duration.zero);
    expect(c.videoUploading, isTrue);
    c.cancelVideoUpload();
    await f;
    expect(c.videoStatus, MediaUploadStatus.idle);
    expect(events.where((e) => e.$1 == 'video_upload_failed'), isEmpty);
  });

  test('移除视频会取消上传并清空视频 / 封面状态', () async {
    repo.uploadGate = Completer<void>();
    final c = make();
    unawaited(c.startVideoUpload());
    await Future<void>.delayed(Duration.zero);
    await c.setVideoCover(Uint8List.fromList([1]));
    c.clearVideo();
    expect(repo.lastToken!.isCancelled, isTrue);
    expect(c.video, isNull);
    expect(c.coverBytes, isNull);
    expect(c.coverUrl, isNull);
    expect(c.videoStatus, MediaUploadStatus.idle);
  });

  test('dispose 时取消进行中的上传', () async {
    repo.uploadGate = Completer<void>();
    final c = make();
    unawaited(c.startVideoUpload());
    await Future<void>.delayed(Duration.zero);
    c.dispose();
    expect(repo.lastToken!.isCancelled, isTrue);
  });

  test('换封面：旧结果作废，用新封面的 URL', () async {
    final c = make();
    await c.setVideoCover(Uint8List.fromList([1]));
    final first = c.coverUrl;
    await c.setVideoCover(Uint8List.fromList([2]));
    expect(c.coverUrl, isNot(first));
    expect(repo.covers.length, 2);
  });

  test('封面上传失败 → 重试成功；上报 cover', () async {
    repo.coverError = Exception('x');
    final c = make();
    await c.setVideoCover(Uint8List.fromList([1]));
    expect(c.coverStatus, MediaUploadStatus.failed);
    repo.coverError = null;
    await c.retryCoverUpload();
    expect(c.coverStatus, MediaUploadStatus.success);
    expect(events.where((e) => e.$1 == 'video_upload_failed').single.$2, {'error_type': 'cover'});
  });

  test('发布走 publishVideo、不碰图片上传；上报 video_publish 且不含路径 / URL / key', () async {
    final c = make()
      ..setType(ContentType.knowledge)
      ..setText('  tips  ');
    await c.startVideoUpload();
    await c.setVideoCover(Uint8List.fromList([1]));

    final id = await c.publish(idempotencyKey: 'K');

    expect(id, 99);
    expect(uploadOneCalls, 0);
    expect(repo.published, {
      'type': ContentType.knowledge,
      'text': 'tips',
      'coverUrl': 'https://cdn.test/public/1/c1.jpg',
      'objectKey': 'video/1/k.mp4',
      'key': 'K',
    });
    final pub = events.where((e) => e.$1 == 'video_publish_succeeded').single.$2!;
    expect(pub.keys, containsAll(['duration_ms', 'size_bytes', 'upload_ms']));
    expect(pub['duration_ms'], 75000);
    expect(pub['size_bytes'], 25000000);
    for (final e in events) {
      final v = e.$2?.values.join(' ') ?? '';
      expect(v, isNot(contains('/tmp')));
      expect(v, isNot(contains('http')));
      expect(v, isNot(contains('video/1')));
    }
  });
}
