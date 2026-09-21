import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';

/// V1.3.0 batch-b1 Story 1.1 · L0：场所列表接口的**线上契约**（AC4 / CROSS-STORY C5）。
///
/// ## 为什么需要这组测试
/// Dart 解析里的 `?? 0` / `?? ''` 兜底会把字段名不匹配**悄悄吞掉** —— 页面照常渲染、
/// 不报错、日志里什么都没有，只是那个数字永远是 0。V1.1.6 真出过这个事故
/// （后端 `diaryCount` vs 客户端 `happyMomentCount`，统计条恒显示 0）。
///
/// ⚠️ 这组用例用的是**后端真实响应的字段名**（`PlaceListResponse` / `PlaceListItemResponse`，
/// 由 `PlaceListResponseContractTest` 同步钉死）。改后端字段名而不改这里，这些用例会红。
void main() {
  /// 后端 `GET /api/v1/places` 的真实响应形状（「按最新」分支，无距离）。
  const wireItem = {
    'token': 'aZ09aZ09aZ09aZ09aZ09aZ09aZ09aZ09',
    'name': 'Kopi Kayu Manis',
    'type': 'CAFE',
    'tags': ['PETS_ALLOWED_INSIDE', 'OUTDOOR_SEATING', 'PET_MENU'],
    'firstPhotoUrl': 'https://cdn.example/oss/place-1.jpg',
    'photoCount': 6,
    'commentCount': 3,
    'recommendCount': 11,
    'notRecommendCount': 2,
  };

  group('场所列表项的字段名必须与客户端解析的一致', () {
    test('真实响应能解析出非零的数字与非空的值（而不是被兜底默认值吞掉）', () {
      final p = PlaceSummary.fromJson(Map<String, dynamic>.from(wireItem));

      expect(p.token, 'aZ09aZ09aZ09aZ09aZ09aZ09aZ09aZ09',
          reason: '🔴 解析出空串说明字段名对不上 —— 列表能渲染，但点进详情会 404');
      expect(p.name, 'Kopi Kayu Manis');
      expect(p.type, PlaceType.cafe);
      expect(p.tags,
          [PlaceTag.petsAllowedInside, PlaceTag.outdoorSeating, PlaceTag.petMenu]);
      expect(p.firstPhotoUrl, 'https://cdn.example/oss/place-1.jpg');
      expect(p.photoCount, 6, reason: '🔴 解析出 0 会静默显示 0 张照片，不报任何错');
      expect(p.commentCount, 3);
      expect(p.recommendCount, 11);
      expect(p.notRecommendCount, 2);
    });

    /// 🛡 「按最新」分支服务端**省略** distanceMeters（NON_NULL）→ 必须解析成 null，
    /// 界面据此隐藏距离位。解析成 0 的话列表上每个场所都会显示「0 m」。
    test('缺 distanceMeters 解析为 null，而不是 0', () {
      final p = PlaceSummary.fromJson(Map<String, dynamic>.from(wireItem));
      expect(p.distanceMeters, isNull);
    });

    test('「按距离」分支的 distanceMeters 能解析出来', () {
      final p = PlaceSummary.fromJson(
          {...wireItem, 'distanceMeters': 1200}.cast<String, dynamic>());
      expect(p.distanceMeters, 1200);
    });

    /// 🛡 无照片的场所：服务端省略 firstPhotoUrl，photoCount 仍为 0（有意义的值，不是缺失）。
    test('无照片时首图为 null、照片数为 0', () {
      final p = PlaceSummary.fromJson(const {
        'token': 'tok',
        'name': 'Taman Suropati',
        'type': 'PARK',
        'tags': ['LEASH_REQUIRED'],
        'photoCount': 0,
        'commentCount': 0,
        'recommendCount': 0,
        'notRecommendCount': 0,
      });
      expect(p.firstPhotoUrl, isNull);
      expect(p.photoCount, 0);
    });

    /// 🔴 后端加了客户端不认识的类型 → null（而不是悄悄落到「其他」）。
    test('未知类型解析为 null，不兜底成 other', () {
      final p = PlaceSummary.fromJson(
          {...wireItem, 'type': 'BRAND_NEW_KIND'}.cast<String, dynamic>());
      expect(p.type, isNull,
          reason: '兜底成 other 会让界面一本正经地显示「其他」，没人看得出契约漂了');
    });

    test('认不出的标签被丢弃，认得出的保留', () {
      final p = PlaceSummary.fromJson(
          {...wireItem, 'tags': ['PET_MENU', 'SOMETHING_NEW']}.cast<String, dynamic>());
      expect(p.tags, [PlaceTag.petMenu]);
    });
  });

  group('列表信封', () {
    test('items 与服务端下发的 sortMode 都能解析', () {
      final page = PlaceListResult.fromJson({
        'items': [Map<String, dynamic>.from(wireItem)],
        'sortMode': 'recent',
      });
      expect(page.items, hasLength(1));
      expect(page.sortMode, PlaceSortMode.recent);
    });

    test('distance 排序路径能解析', () {
      final page = PlaceListResult.fromJson(const {'items': [], 'sortMode': 'distance'});
      expect(page.sortMode, PlaceSortMode.distance);
    });

    /// 🛡 空库（冷启动前）：items 为空数组 → 空列表，页面走空态而不是错误态。
    test('空 items 解析成空列表', () {
      final page = PlaceListResult.fromJson(const {'items': [], 'sortMode': 'recent'});
      expect(page.items, isEmpty);
    });

    /// 未知 / 缺失 sortMode 一律当 recent —— 兜底方向正确的那一侧（按最新是 FR-112.2 的正常态）。
    test('未知 sortMode 回落到 recent', () {
      expect(PlaceListResult.fromJson(const {'items': []}).sortMode, PlaceSortMode.recent);
      expect(PlaceListResult.fromJson(const {'items': [], 'sortMode': 'wat'}).sortMode,
          PlaceSortMode.recent);
    });
  });
}
