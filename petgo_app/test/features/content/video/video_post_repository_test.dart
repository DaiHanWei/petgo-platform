import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/video/video_post_repository.dart';
import 'package:tailtopia/features/content/video/video_spec.dart';
import 'package:tailtopia/features/media/data/media_repository.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/data/upload_ticket.dart';

/// 记录请求并返回固定 JSON 的假 adapter。
class _Adapter implements HttpClientAdapter {
  _Adapter(this.body);
  final Map<String, dynamic> body;
  final List<RequestOptions> requests = [];
  final List<List<int>> bodies = [];

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    requests.add(options);
    final buf = <int>[];
    if (requestStream != null) {
      await for (final chunk in requestStream) {
        buf.addAll(chunk);
      }
    }
    bodies.add(buf);
    return ResponseBody.fromString(jsonEncode(body), 200, headers: {
      Headers.contentTypeHeader: ['application/json'],
    });
  }

  @override
  void close({bool force = false}) {}
}

class _Media implements MediaRepository {
  String? purpose;
  @override
  Future<UploadTicket> requestUploadTicket(MediaScope scope, {String? contentType, String? purpose}) async {
    this.purpose = purpose;
    return UploadTicket.fromJson({
      'uploadUrl': 'https://oss.test/put',
      'objectKey': 'public/1/c.jpg',
      'method': 'PUT',
      'headers': {'Content-Type': 'image/jpeg', 'x-oss-object-acl': 'private', 'x-oss-forbid-overwrite': 'true'},
      'publicUrl': 'https://cdn.test/public/1/c.jpg',
    });
  }
}

/// L0：专题线 video Story 1.6 —— 视频仓库的请求形状。
void main() {
  const draft = VideoDraft(path: 'test/fixtures/video/ok_avc_720x1280.mp4', durationMs: 4000, width: 720, height: 1280, sizeBytes: 62261);

  test('策略解析保持字段顺序', () {
    final p = VideoUploadPolicy.fromJson({
      'host': 'https://b.oss.test',
      'key': 'video/1/k.mp4',
      'fields': {'key': 'video/1/k.mp4', 'policy': 'P', 'OSSAccessKeyId': 'A', 'signature': 'S', 'success_action_status': '200'},
    });
    expect(p.fields.keys.toList(), ['key', 'policy', 'OSSAccessKeyId', 'signature', 'success_action_status']);
  });

  test('上传是 multipart：字段按策略顺序、file 最后、video/mp4，进度回调触发', () async {
    final oss = Dio();
    final adapter = _Adapter({});
    oss.httpClientAdapter = adapter;
    final repo = DioVideoPostRepository(api: Dio(), media: _Media(), ossDio: oss);
    final policy = VideoUploadPolicy(host: 'https://b.oss.test', key: 'video/1/k.mp4', fields: {
      'key': 'video/1/k.mp4', 'policy': 'P', 'OSSAccessKeyId': 'A', 'signature': 'S',
      'success_action_status': '200', 'Content-Type': 'video/mp4', 'x-oss-object-acl': 'private',
      'x-oss-forbid-overwrite': 'true',
    });
    var progressed = false;
    await repo.uploadVideo(policy, draft.path,
        onProgress: (s, t) => progressed = true, cancelToken: CancelToken());

    expect(adapter.requests.single.uri.toString(), 'https://b.oss.test');
    expect(adapter.requests.single.headers['content-type'], startsWith('multipart/form-data'));
    final text = latin1.decode(adapter.bodies.single);
    final order = ['name="key"', 'name="policy"', 'name="OSSAccessKeyId"', 'name="signature"',
      'name="success_action_status"', 'name="Content-Type"', 'name="x-oss-object-acl"',
      'name="x-oss-forbid-overwrite"', 'name="file"'];
    var last = -1;
    for (final n in order) {
      final at = text.indexOf(n);
      expect(at, greaterThan(last), reason: '$n 顺序不对');
      last = at;
    }
    expect(text, contains('content-type: video/mp4'));
    expect(adapter.bodies.single.length, greaterThan(File(draft.path).lengthSync()));
    expect(progressed, isTrue);
  });

  test('独立 Dio：15 秒连接、600 秒发送、无拦截器', () {
    final d = buildVideoOssDio();
    expect(d.options.connectTimeout, const Duration(seconds: 15));
    expect(d.options.sendTimeout, const Duration(seconds: 600));
    expect(d.interceptors.whereType<InterceptorsWrapper>(), isEmpty);
  });

  test('封面走 VIDEO_COVER 用途，按票据 PUT 并返回 publicUrl', () async {
    final media = _Media();
    final adapter = _Adapter({});
    final uploader = OssUploader(dio: Dio()..httpClientAdapter = adapter);
    final repo = DioVideoPostRepository(api: Dio(), media: media, coverUploader: uploader, ossDio: Dio());

    final url = await repo.uploadCover(Uint8List.fromList([0xFF, 0xD8, 0xFF]));

    expect(media.purpose, 'VIDEO_COVER');
    expect(url, 'https://cdn.test/public/1/c.jpg');
    final req = adapter.requests.single;
    expect(req.method, 'PUT');
    expect(req.headers['x-oss-forbid-overwrite'], 'true');
    expect(req.headers['x-oss-object-acl'], 'private');
  });

  test('发布请求体：VIDEO 字段齐全，图文专属键一律不带', () async {
    final api = Dio();
    final adapter = _Adapter({'id': 77});
    api.httpClientAdapter = adapter;
    final repo = DioVideoPostRepository(api: api, media: _Media(), ossDio: Dio());

    final id = await repo.publishVideo(
      type: ContentType.daily,
      text: 'halo',
      coverUrl: 'https://cdn.test/public/1/c.jpg',
      video: draft,
      objectKey: 'video/1/k.mp4',
      mentionedUserIds: const [5],
      idempotencyKey: 'IDEM',
    );

    expect(id, 77);
    final req = adapter.requests.single;
    expect(req.path, endsWith('/content-posts'));
    expect(req.headers['Idempotency-Key'], 'IDEM');
    final body = jsonDecode(utf8.decode(adapter.bodies.single)) as Map<String, dynamic>;
    expect(body, {
      'type': 'DAILY',
      'mediaType': 'VIDEO',
      'visibility': 'PUBLIC',
      'imageUrls': ['https://cdn.test/public/1/c.jpg'],
      'video': {'objectKey': 'video/1/k.mp4', 'durationMs': 4000, 'width': 720, 'height': 1280},
      'text': 'halo',
      'mentionedUserIds': [5],
    });
  });
}
