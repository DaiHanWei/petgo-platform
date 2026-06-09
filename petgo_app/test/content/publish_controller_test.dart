import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/content/data/content_repository.dart';
import 'package:petgo/features/content/domain/content_type.dart';
import 'package:petgo/features/content/domain/publish_controller.dart';

class _FakeRepo implements ContentRepository {
  ContentType? lastType;
  int? lastPetId;
  List<String>? lastUrls;
  String? lastIdem;
  DateTime? lastEventDate;
  int publishCalls = 0;

  /// 注入式发布异常（模拟审核 422 等）；非空则 publish 抛出。
  Object? throwOnPublish;

  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    String? text,
    List<String> imageUrls = const [],
    DateTime? eventDate,
    required String idempotencyKey,
  }) async {
    publishCalls++;
    lastType = type;
    lastPetId = petId;
    lastUrls = imageUrls;
    lastEventDate = eventDate;
    lastIdem = idempotencyKey;
    if (throwOnPublish != null) throw throwOnPublish!;
    return 123;
  }
}

void main() {
  Uint8List bytes(String s) => Uint8List.fromList(utf8.encode(s));

  // uploader：bytes 内容含 'bad' 的失败（可控制重试时改为成功）。
  ({PublishController c, _FakeRepo repo, List<String> uploadLog, Set<String> failing}) make() {
    final repo = _FakeRepo();
    final uploadLog = <String>[];
    final failing = <String>{'bad'};
    final c = PublishController(
      repository: repo,
      uploadOne: (b) async {
        final content = utf8.decode(b);
        uploadLog.add(content);
        if (failing.contains(content)) throw Exception('upload failed');
        return 'https://cdn/$content.jpg';
      },
    );
    return (c: c, repo: repo, uploadLog: uploadLog, failing: failing);
  }

  test('addImage 上限 9 张', () {
    final m = make();
    for (var i = 0; i < 9; i++) {
      expect(m.c.addImage(bytes('img$i')), isTrue);
    }
    expect(m.c.addImage(bytes('overflow')), isFalse);
    expect(m.c.items.length, 9);
  });

  test('uploadAll：部分失败标记 failed，其余 success', () async {
    final m = make();
    m.c.addImage(bytes('good'));
    m.c.addImage(bytes('bad'));
    await m.c.uploadAll();
    expect(m.c.items[0].status, ImageUploadStatus.success);
    expect(m.c.items[0].url, 'https://cdn/good.jpg');
    expect(m.c.items[1].status, ImageUploadStatus.failed);
    expect(m.c.hasFailed, isTrue);
    expect(m.c.allUploaded, isFalse);
  });

  test('retryFailed：仅重传失败件，文字保留，成功件不重传', () async {
    final m = make();
    m.c.setText('我的草稿文字');
    m.c.addImage(bytes('good'));
    m.c.addImage(bytes('bad'));
    await m.c.uploadAll();
    expect(m.uploadLog, ['good', 'bad']); // 首轮各传一次

    m.failing.clear(); // 网络恢复
    await m.c.retryFailed();

    // 只重传 'bad'，'good' 不再传
    expect(m.uploadLog, ['good', 'bad', 'bad']);
    expect(m.c.allUploaded, isTrue);
    expect(m.c.text, '我的草稿文字'); // 文字内存保留
  });

  test('publish：全部成功 → 提交 post 返回 id；含失败件 → 返回 null 不提交', () async {
    final m = make();
    m.c.setText('hi');
    m.c.addImage(bytes('bad'));
    final r1 = await m.c.publish(idempotencyKey: 'K1');
    expect(r1, isNull); // 有失败件
    expect(m.repo.publishCalls, 0);

    m.failing.clear();
    final r2 = await m.c.publish(idempotencyKey: 'K1');
    expect(r2, 123);
    expect(m.repo.publishCalls, 1);
    expect(m.repo.lastUrls, ['https://cdn/bad.jpg']);
    expect(m.repo.lastIdem, 'K1');
  });

  test('成长日历提交带 petId；普通类型不带', () async {
    final m = make();
    m.c.setText('moment');
    m.c.setType(ContentType.growthMoment);
    await m.c.publish(idempotencyKey: 'K', petId: 5);
    expect(m.repo.lastType, ContentType.growthMoment);
    expect(m.repo.lastPetId, 5);

    final m2 = make();
    m2.c.setText('daily');
    m2.c.setType(ContentType.daily);
    await m2.c.publish(idempotencyKey: 'K', petId: 5);
    expect(m2.repo.lastPetId, isNull); // 普通类型忽略 petId
  });

  test('最低内容（AC6）：文字图片皆空禁发，任一非空可发', () {
    final m = make();
    expect(m.c.canPublish, isFalse); // 皆空
    m.c.addImage(bytes('img')); // 仅图片
    expect(m.c.canPublish, isTrue);
    m.c.removeImage(0);
    expect(m.c.canPublish, isFalse);
    m.c.setText('  '); // 纯空白不算内容
    expect(m.c.canPublish, isFalse);
    m.c.setText('hi'); // 仅文字
    expect(m.c.canPublish, isTrue);
  });

  test('成长日历默认带今天事件日期；普通类型不带（F9）', () async {
    final m = make();
    m.c.setText('moment');
    m.c.setType(ContentType.growthMoment);
    await m.c.publish(idempotencyKey: 'K', petId: 5);
    final today = DateTime.now();
    expect(m.repo.lastEventDate, isNotNull);
    expect(m.repo.lastEventDate!.year, today.year);
    expect(m.repo.lastEventDate!.month, today.month);
    expect(m.repo.lastEventDate!.day, today.day);

    final m2 = make();
    m2.c.setText('daily');
    await m2.c.publish(idempotencyKey: 'K');
    expect(m2.repo.lastEventDate, isNull); // 日常不带事件日期
  });

  test('setEventDate 显式日期透传（去时分）', () async {
    final m = make();
    m.c.setText('生日');
    m.c.setType(ContentType.growthMoment);
    m.c.setEventDate(DateTime(2024, 5, 1, 13, 30));
    await m.c.publish(idempotencyKey: 'K', petId: 9);
    expect(m.repo.lastEventDate, DateTime(2024, 5, 1));
  });

  test('字数实时计数与发布禁用', () {
    final m = make();
    expect(m.c.canPublish, isFalse); // 空内容
    m.c.setText('abc');
    expect(m.c.remainingChars, kMaxPostTextLength - 3);
    expect(m.c.canPublish, isTrue);
    m.c.setText('x' * (kMaxPostTextLength + 1));
    expect(m.c.textWithinLimit, isFalse);
    expect(m.c.canPublish, isFalse); // 超限禁止发布
  });
}
