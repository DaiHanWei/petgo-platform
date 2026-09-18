import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/domain/place_detail.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';

/// V1.3.0 batch-b1 Story 1.5 · L0：场所详情接口的**线上契约**（AC1 / AC6 · CROSS-STORY C5）。
///
/// ## 为什么需要这组测试
/// 同 `place_wire_contract_test.dart`：Dart 侧的 `?? 0` / `?? ''` 兜底会把字段名不匹配
/// **悄悄吞掉** —— 页面照常渲染，只是地址永远是空串、坐标永远是 `0,0`
/// （而 `0,0` 是几内亚湾上的一点，小地图会"正常"显示大海）。
///
/// ⚠️ 用例里的 JSON 是**后端真实响应的字段名**（`PlaceDetailResponse` + `AuthorView`，
/// 由 `PlaceDetailResponseContractTest` 同步钉死）。改后端字段名而不改这里，这些用例会红。
void main() {
  /// 后端 `GET /api/v1/places/{token}` 的真实响应形状（带坐标 → 有 distanceMeters）。
  const wire = {
    'token': 'aZ09aZ09aZ09aZ09aZ09aZ09aZ09aZ09',
    'name': 'Kopi Kayu Manis',
    'type': 'CAFE',
    'tags': ['PETS_ALLOWED_INSIDE', 'OUTDOOR_SEATING', 'PET_MENU'],
    // Story 1.9：照片从字符串数组换成了**带上传者的对象**（AC2）。
    'photos': [
      {
        'id': 11,
        'url': 'https://cdn.example/oss/place-1.jpg'
            '?x-oss-process=image/resize,w_1080/format,jpg',
        'uploaderId': 4021,
        'uploaderNickname': 'Rina',
        'uploaderDeleted': false,
        'moderationStatus': 'VISIBLE',
        'mine': false,
      },
      {
        'id': 12,
        'url': 'https://cdn.example/oss/place-2.jpg'
            '?x-oss-process=image/resize,w_1080/format,jpg',
        'uploaderId': 9001,
        'uploaderNickname': 'Budi',
        'uploaderDeleted': false,
        'moderationStatus': 'VISIBLE',
        'mine': true,
      },
    ],
    'addressText': 'Jl. Kemang Raya No. 12, Jakarta Selatan',
    'description': 'Ada area outdoor yang luas untuk anabul.',
    'latitude': -6.2607,
    'longitude': 106.8134,
    'distanceMeters': 1240,
    'markedBy': {
      'userId': 4021,
      'nickname': 'Rina',
      'avatarUrl': 'https://cdn.example/oss/avatar-4021.jpg',
      'deleted': false,
      'tags': <Object>[],
    },
    'commentCount': 3,
    'recommendCount': 11,
    'notRecommendCount': 2,
  };

  group('详情字段名必须与客户端解析的一致', () {
    test('真实响应能解析出全部非默认值（而不是被兜底吞掉）', () {
      final p = PlaceDetail.fromJson(Map<String, dynamic>.from(wire));

      expect(p.token, 'aZ09aZ09aZ09aZ09aZ09aZ09aZ09aZ09');
      expect(p.name, 'Kopi Kayu Manis');
      expect(p.type, PlaceType.cafe);
      expect(p.tags,
          [PlaceTag.petsAllowedInside, PlaceTag.outdoorSeating, PlaceTag.petMenu]);
      expect(p.photos, hasLength(2));
      // 🔴 AC2：每张都带上传者 —— 解析丢了的话界面会把别人拍的照片标成标记人的。
      expect(p.photos[1].uploaderNickname, 'Budi');
      expect(p.photos[1].mine, isTrue, reason: '本人传的 → 该给删除入口');
      expect(p.photoUrls, hasLength(2), reason: '灯箱/分享只要 URL 的场合');
      expect(p.addressText, 'Jl. Kemang Raya No. 12, Jakarta Selatan',
          reason: '🔴 解析成空串的话地址行与「复制」按钮会复制一个空串，界面看不出错');
      expect(p.description, 'Ada area outdoor yang luas untuk anabul.');
      expect(p.latitude, closeTo(-6.2607, 1e-9),
          reason: '🔴 解析成 0 会让小地图指到几内亚湾的海上，而不是报错');
      expect(p.longitude, closeTo(106.8134, 1e-9));
      expect(p.distanceMeters, 1240);
      expect(p.commentCount, 3);
      expect(p.recommendCount, 11);
      expect(p.notRecommendCount, 2);
    });

    test('markedBy 的字段名对得上，且可点', () {
      final p = PlaceDetail.fromJson(Map<String, dynamic>.from(wire));
      expect(p.markedBy.userId, 4021);
      expect(p.markedBy.nickname, 'Rina');
      expect(p.markedBy.avatarUrl, 'https://cdn.example/oss/avatar-4021.jpg');
      expect(p.markedBy.deleted, isFalse);
      expect(p.markedBy.tappable, isTrue);
    });

    /// 🛡 NFR-8 匿名化：注销后昵称/头像为 null 而 userId 仍在 → 渲染「已注销用户」且**不可点**。
    test('注销标记人：昵称/头像为 null、deleted=true、不可点', () {
      final p = PlaceDetail.fromJson({
        ...wire,
        'markedBy': {'userId': 4021, 'deleted': true, 'tags': <Object>[]},
      }.cast<String, dynamic>());
      expect(p.markedBy.nickname, isNull);
      expect(p.markedBy.avatarUrl, isNull);
      expect(p.markedBy.deleted, isTrue);
      expect(p.markedBy.tappable, isFalse,
          reason: '🔴 可点的话会跳到一个已注销用户的主页（NFR-8）');
    });

    /// 契约里 markedBy 恒有值；真拿不到时**按已注销渲染**（fail-closed：不显示任何身份）。
    test('markedBy 缺失时 fail-closed 成已注销、不可点', () {
      final json = Map<String, dynamic>.from(wire)..remove('markedBy');
      final p = PlaceDetail.fromJson(json);
      expect(p.markedBy.deleted, isTrue);
      expect(p.markedBy.tappable, isFalse);
    });

    /// 🛡 没带坐标进来时服务端**省略** distanceMeters（NON_NULL）→ 必须是 null，界面隐藏距离位。
    test('缺 distanceMeters 解析为 null，而不是 0', () {
      final json = Map<String, dynamic>.from(wire)..remove('distanceMeters');
      expect(PlaceDetail.fromJson(json).distanceMeters, isNull);
    });

    /// 无描述 / 无照片的场所：服务端省略 description、photoUrls 为空数组。
    test('无描述解析为 null、无照片解析为空列表', () {
      final json = Map<String, dynamic>.from(wire)
        ..remove('description')
        ..['photos'] = <Object>[];
      final p = PlaceDetail.fromJson(json);
      expect(p.description, isNull);
      expect(p.photos, isEmpty);
    });

    test('未知类型解析为 null，不兜底成 other', () {
      final p = PlaceDetail.fromJson(
          {...wire, 'type': 'BRAND_NEW_KIND'}.cast<String, dynamic>());
      expect(p.type, isNull);
    });
  });

  group('照片的上传者标注（Story 1.9 · AC2）', () {
    test('注销上传者：昵称为 null、标为已注销、不可点', () {
      final json = Map<String, dynamic>.from(wire)
        ..['photos'] = [
          {
            'id': 11,
            'url': 'https://cdn/a.jpg',
            'uploaderId': 4021,
            'uploaderDeleted': true,
            'moderationStatus': 'VISIBLE',
            'mine': false,
          },
        ];
      final photo = PlaceDetail.fromJson(json).photos.single;

      expect(photo.uploaderNickname, isNull);
      expect(photo.uploaderDeleted, isTrue);
      expect(photo.uploaderTappable, isFalse);
    });

    /// 🔴 非 VISIBLE 的照片**只会下发给上传者本人** —— 拿到一张挂起的，它一定是你自己刚传的。
    test('挂起中的照片标为仅本人可见', () {
      final json = Map<String, dynamic>.from(wire)
        ..['photos'] = [
          {
            'id': 12,
            'url': 'https://cdn/b.jpg',
            'uploaderId': 4021,
            'uploaderNickname': 'Rina',
            'uploaderDeleted': false,
            'moderationStatus': 'UNDER_REVIEW',
            'mine': true,
          },
        ];
      final photo = PlaceDetail.fromJson(json).photos.single;

      expect(photo.moderation.onlyVisibleToMe, isTrue);
      expect(photo.mine, isTrue);
    });

    test('缺 photos 解析成空列表（走"还没有照片"的空态）', () {
      final json = Map<String, dynamic>.from(wire)..remove('photos');
      expect(PlaceDetail.fromJson(json).photos, isEmpty);
    });
  });

  /// 🔴 **AC6 的反向验收**：这些字段在契约里就**不存在**。
  ///
  /// 后端 `PlaceDetailResponseContractTest` 已经断言了响应里没有这些键；这里守住另一半 ——
  /// 客户端域模型也不能悄悄长出这些概念（长出来就意味着有人在准备做收藏 / 打星 / 打卡）。
  group('🔴 AC6：详情模型里没有收藏 / 评分 / 营业时间 / 打卡 / 可编辑', () {
    test('即使服务端多下发了这些键，客户端也不认（解析不报错、不产生任何状态）', () {
      final p = PlaceDetail.fromJson({
        ...wire,
        'favorited': true,
        'rating': 4.5,
        'openingHours': '09:00-21:00',
        'phone': '+62811111111',
        'checkedIn': true,
        'editable': true,
      }.cast<String, dynamic>());
      // 多余键被忽略，正常字段照旧 —— 未知键不该让解析崩掉（宽进严出）。
      expect(p.token, 'aZ09aZ09aZ09aZ09aZ09aZ09aZ09aZ09');
      expect(p.name, 'Kopi Kayu Manis');
    });

    test('域模型源码里没有这些概念的字段（机械检查，防"顺手加一个"）', () {
      final src =
          File('lib/features/place/domain/place_detail.dart').readAsStringSync();
      // 只看代码行 —— 注释里说明"没有它"是允许的（那个文件的头部就在解释这件事）。
      final codeLines = src
          .split('\n')
          .where((l) => !l.trimLeft().startsWith('//'))
          .join('\n');
      for (final banned in [
        'favorited',
        'rating',
        'openingHours',
        'checkedIn',
        'editable',
      ]) {
        expect(codeLines.contains(banned), isFalse,
            reason: '🔴 "$banned" 是 FR-112.6 明确不做的（不是"还没做"）——'
                '要加先回 PRD ⑥ / 决策日志改口径');
      }
    });
  });
}
