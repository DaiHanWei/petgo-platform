import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../media/data/media_repository.dart';
import '../../media/data/oss_uploader.dart';
import '../domain/content_type.dart';
import '../domain/feed_item.dart' show kVisibilityPublic;
import 'video_spec.dart';

/// 视频 PostObject 上传策略（专题线 video Story 1.2 / 1.6 · AD-3）。
class VideoUploadPolicy {
  const VideoUploadPolicy({required this.host, required this.key, required this.fields});

  factory VideoUploadPolicy.fromJson(Map<String, dynamic> json) {
    final raw = (json['fields'] as Map).cast<String, dynamic>();
    // 保持服务端给的字段顺序（表单字段在前、file 最后）。
    final fields = <String, String>{}; // Dart 字面量 Map 默认保持插入顺序
    raw.forEach((k, v) => fields[k] = '$v');
    return VideoUploadPolicy(host: json['host'] as String, key: json['key'] as String, fields: fields);
  }

  final String host;
  final String key;
  final Map<String, String> fields;
}

/// 视频帖的上传与发布（专题线 video Story 1.6）。
///
/// 🔴 **刻意不扩展 `ContentRepository` 接口**：它有十几个实现（含大量测试假实现），
/// 加参数会让它们全部编译失败；视频发布的请求体也和图文完全不同，独立成仓库更清楚。
abstract class VideoPostRepository {
  Future<VideoUploadPolicy> requestPolicy();

  /// 流式上传视频文件（不读进内存）；OSS 返回 200 即成功。
  Future<void> uploadVideo(
    VideoUploadPolicy policy,
    String path, {
    required void Function(int sent, int total) onProgress,
    required CancelToken cancelToken,
  });

  /// 上传封面（`purpose=VIDEO_COVER`，服务端签成私有），返回 publicUrl（发布后才可匿名访问）。
  Future<String> uploadCover(Uint8List jpeg);

  Future<int> publishVideo({
    required ContentType type,
    String? text,
    required String coverUrl,
    required VideoDraft video,
    required String objectKey,
    List<int> mentionedUserIds,
    required String idempotencyKey,
  });
}

class DioVideoPostRepository implements VideoPostRepository {
  DioVideoPostRepository({required this.api, required this.media, Dio? ossDio, OssUploader? coverUploader})
      : _ossDio = ossDio ?? buildVideoOssDio(),
        _coverUploader = coverUploader ?? OssUploader();

  /// 带鉴权拦截器的业务 API 客户端。
  final Dio api;
  final MediaRepository media;
  final Dio _ossDio;
  final OssUploader _coverUploader;

  @override
  Future<VideoUploadPolicy> requestPolicy() async {
    final resp = await api.post<Map<String, dynamic>>(ApiPaths.mediaVideoUploadPolicy);
    return VideoUploadPolicy.fromJson(resp.data!);
  }

  @override
  Future<void> uploadVideo(
    VideoUploadPolicy policy,
    String path, {
    required void Function(int sent, int total) onProgress,
    required CancelToken cancelToken,
  }) async {
    final form = FormData();
    policy.fields.forEach((k, v) => form.fields.add(MapEntry(k, v)));
    // 🔴 file 必须是最后一个字段（OSS PostObject 要求）；fromFile 流式读取，不进内存。
    form.files.add(MapEntry(
      'file',
      await MultipartFile.fromFile(path, filename: 'video.mp4', contentType: DioMediaType('video', 'mp4')),
    ));
    await _ossDio.post<void>(
      policy.host,
      data: form,
      onSendProgress: onProgress,
      cancelToken: cancelToken,
    );
  }

  @override
  Future<String> uploadCover(Uint8List jpeg) async {
    final ticket = await media.requestUploadTicket(
      MediaScope.public,
      contentType: 'image/jpeg',
      purpose: 'VIDEO_COVER',
    );
    final result = await _coverUploader.put(ticket, bytes: jpeg);
    return result.publicUrl!;
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
    final data = <String, dynamic>{
      'type': type.wire,
      'mediaType': 'VIDEO',
      'visibility': kVisibilityPublic,
      'imageUrls': [coverUrl],
      'video': {
        'objectKey': objectKey,
        'durationMs': video.durationMs,
        'width': video.width,
        'height': video.height,
      },
    };
    if (text != null && text.isNotEmpty) data['text'] = text;
    if (mentionedUserIds.isNotEmpty) data['mentionedUserIds'] = mentionedUserIds;
    final resp = await api.post<Map<String, dynamic>>(
      ApiPaths.contentPosts,
      data: data,
      options: Options(headers: <String, dynamic>{'Idempotency-Key': idempotencyKey}),
    );
    return resp.data!['id'] as int;
  }
}

/// 视频直传 OSS 用的独立 Dio（AD-3）：**不挂鉴权拦截器**（目标是 OSS，不能把 JWT 发出去），
/// 连接 15 秒、发送 600 秒（100MB @ 5Mbps 上行约 160 秒，留足余量）。
Dio buildVideoOssDio() => Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 15),
      sendTimeout: const Duration(seconds: 600),
    ));

final videoPostRepositoryProvider = Provider<VideoPostRepository>((ref) => DioVideoPostRepository(
      api: ref.read(dioProvider),
      media: ref.read(mediaRepositoryProvider),
    ));
