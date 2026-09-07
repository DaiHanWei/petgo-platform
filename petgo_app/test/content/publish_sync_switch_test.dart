import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/me/data/my_posts_repository.dart';

/// Story 4.2 · L0：发布页同步开关 → `visibility` 的映射、主按钮文案联动、以及
/// 「发布后不可更改」这条硬约束。
///
/// ⚠️ 主按钮文案联动（开=Bagikan / 关=Simpan）**PRD 未提、只在 UI 稿 P2 里**，
/// 靠本文件的断言防回归 —— 别删。
class _RecordingRepo implements ContentRepository {
  bool? lastSync;
  ContentType? lastType;

  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    String? text,
    List<String> imageUrls = const [],
    List<ImageSize?> imageSizes = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
  }) async {
    lastType = type;
    lastSync = syncToMoment;
    return 1;
  }
}

PublishController _controller(_RecordingRepo repo) => PublishController(
      uploadOne: (Uint8List bytes) async => 'https://cdn/x.jpg',
      repository: repo,
    );

void main() {
  group('AC1/AC2 同步开关 → visibility 映射', () {
    test('Diary 默认开启 → 发布带 syncToMoment=true（= PUBLIC）', () async {
      final repo = _RecordingRepo();
      final c = _controller(repo)
        ..setType(ContentType.growthMoment)
        ..setText('文字');

      expect(c.syncToMoment, isTrue, reason: 'AC1：默认开启');
      await c.publish(petId: 9, idempotencyKey: 'k1');
      expect(repo.lastSync, isTrue);
    });

    test('关掉开关 → 发布带 syncToMoment=false（= PRIVATE，只进自己的成长档案）', () async {
      final repo = _RecordingRepo();
      final c = _controller(repo)
        ..setType(ContentType.growthMoment)
        ..setText('文字')
        ..setSyncToMoment(false);

      await c.publish(petId: 9, idempotencyKey: 'k2');
      expect(repo.lastSync, isFalse);
    });

    test('切到 Moment / Tips → 开关复位为开启，且发布恒公开（AC4：它们本就公开）', () async {
      final repo = _RecordingRepo();
      final c = _controller(repo)
        ..setType(ContentType.growthMoment)
        ..setSyncToMoment(false);

      // 关掉后切类型：若不复位，「看不见的开关」会把公开内容偷偷发成私密。
      c.setType(ContentType.daily);
      expect(c.syncToMoment, isTrue);

      c.setText('文字');
      await c.publish(idempotencyKey: 'k3');
      expect(repo.lastSync, isTrue);
    });
  });

  group('AC2 主按钮文案联动（PRD 未提，UI 稿 P2 独有）', () {
    test('Diary + 开 → 分享；Diary + 关 → 保存；Moment/Tips → 恒分享', () {
      final c = _controller(_RecordingRepo());

      c.setType(ContentType.growthMoment);
      expect(c.isSharing, isTrue, reason: '开关默认开 → Bagikan');

      c.setSyncToMoment(false);
      expect(c.isSharing, isFalse, reason: '关同步 → Simpan');

      c.setType(ContentType.daily);
      expect(c.isSharing, isTrue);
      c.setType(ContentType.knowledge);
      expect(c.isSharing, isTrue);
    });
  });

  group('AC7 同步状态发布后不可更改', () {
    test('控制器只在创建期暴露开关；没有任何「改可见范围」的接口', () {
      // 反向断言：PublishController 是唯一设置点，模型层不提供事后修改能力。
      // MyPost 只读 visibility（无 setter / copyWith），内容详情与我的发布都不该有转私密入口。
      const post = MyPost(id: 1, type: 'GROWTH_MOMENT', visibility: 'PRIVATE');
      expect(post.isPrivate, isTrue);
      expect(
        MyPost.fromJson(const {'id': 2, 'type': 'DAILY'}).visibility,
        'PUBLIC',
        reason: '缺省公开：老后端不下发该字段时不得把内容误标成私密',
      );
    });
  });

  group('AC8 我的发布：私密标识数据面', () {
    test('visibility=PRIVATE → isPrivate；PUBLIC / 缺省 → false', () {
      expect(const MyPost(id: 1, type: 'DAILY', visibility: 'PRIVATE').isPrivate, isTrue);
      expect(const MyPost(id: 2, type: 'DAILY', visibility: 'PUBLIC').isPrivate, isFalse);
      expect(const MyPost(id: 3, type: 'DAILY').isPrivate, isFalse);
    });
  });
}
