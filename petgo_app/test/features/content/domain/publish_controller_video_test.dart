import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/video/video_spec.dart';

class _Repo implements ContentRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// L0：专题线 video Story 1.5 —— 媒体互斥（图片列表 | 单个视频）。
void main() {
  const draft = VideoDraft(path: '/tmp/a.mp4', durationMs: 5000, width: 720, height: 1280, sizeBytes: 1000);
  PublishController make() => PublishController(repository: _Repo(), uploadOne: (_) async => 'u');

  test('有图片时不能选视频', () {
    final c = make()..addImage(Uint8List.fromList([1, 2, 3]));
    expect(c.canPickVideo, isFalse);
    c.setVideo(draft);
    expect(c.video, isNull);
  });

  test('有视频时不能加图，入口状态正确', () {
    final c = make()..setVideo(draft);
    expect(c.video, same(draft));
    expect(c.canAddImage, isFalse);
    expect(c.canPickVideo, isFalse);
    expect(c.addImage(Uint8List.fromList([1])), isFalse);
    expect(c.items, isEmpty);
  });

  test('选视频时若当前是成长日记，自动切回日常；有视频时切不到成长日记', () {
    final c = make()..setType(ContentType.growthMoment);
    c.setVideo(draft);
    expect(c.type, ContentType.daily);
    c.setType(ContentType.growthMoment);
    expect(c.type, ContentType.daily);
    c.setType(ContentType.knowledge);
    expect(c.type, ContentType.knowledge);
  });

  test('有视频时暂不可发布（上传在 1.6）；移除后恢复图文规则', () {
    final c = make()
      ..setText('halo')
      ..setVideo(draft);
    expect(c.canPublish, isFalse);
    c.clearVideo();
    expect(c.video, isNull);
    expect(c.canPublish, isTrue);
    expect(c.canAddImage, isTrue);
  });
}
