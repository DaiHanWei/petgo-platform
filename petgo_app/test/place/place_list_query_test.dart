import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_list_filter.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';

/// V1.3.0 batch-b1 Story 1.2 · L0：列表族键的构造。
///
/// 🔴 守的是「**GPS 米级抖动不该把页面打回 loading**」：原始坐标当族键的话，每次定点抖动
/// 都会产生一个全新的、没有缓存的 family provider，`previous == null` → 整屏列表换成转圈，
/// 正是 F13 要避免的。按 ~110 m 归一后，站着不动就命中同一个键。
void main() {
  test('无坐标 → 按最新的族键', () {
    expect(placeListQueryFor(null, null), placeListRecentQuery);
    expect(placeListQueryFor(-6.2, null), placeListRecentQuery);
    expect(placeListQueryFor(null, 106.8), placeListRecentQuery);
  });

  test('🔴 米级抖动落在同一个族键上', () {
    final a = placeListQueryFor(-6.235012, 106.810004);
    final b = placeListQueryFor(-6.235047, 106.809960);

    expect(a, b, reason: 'record 结构相等；不相等说明归一没生效，列表会反复回到 loading');
  });

  test('百米级移动换族键（换了就该重新按距离排）', () {
    final a = placeListQueryFor(-6.2350, 106.8100);
    final b = placeListQueryFor(-6.2380, 106.8100);

    expect(a, isNot(b));
  });

  test('归一保留 3 位小数', () {
    expect(placeListQueryFor(-6.2354999, 106.8104999),
        (lat: -6.235, lng: 106.810, filter: PlaceListFilter.none));
  });

  test('族键是 record，结构相等可直接比较（family 的缓存依赖这一点）', () {
    expect(placeListQueryFor(-6.235, 106.81) == placeListQueryFor(-6.235, 106.81), isTrue);
    expect(placeListQueryFor(-6.235, 106.81).hashCode,
        placeListQueryFor(-6.235, 106.81).hashCode);
  });

  /// Story 1.5：**详情族键必须与列表同一套归一规则**（code-review 2026-09-15）。
  /// 详情页同样会被抖动打回 loading —— 而且它没有列表页那样的"保留旧数据"分支可兜。
  group('详情族键（Story 1.5）', () {
    test('无坐标 → 坐标位为 null（距离位隐藏），token 保留', () {
      expect(placeDetailQueryFor('tok', null, null),
          (token: 'tok', lat: null, lng: null));
      // 只给一条坐标也当没有（后端对半套坐标回 422，不该发出去）。
      expect(placeDetailQueryFor('tok', -6.2, null),
          (token: 'tok', lat: null, lng: null));
    });

    test('🔴 米级抖动落在同一个详情族键上', () {
      expect(placeDetailQueryFor('tok', -6.235012, 106.810004),
          placeDetailQueryFor('tok', -6.235047, 106.809960));
    });

    test('与列表用同样的 3 位归一（两侧送往服务端的精度一致）', () {
      final d = placeDetailQueryFor('tok', -6.2354999, 106.8104999);
      final l = placeListQueryFor(-6.2354999, 106.8104999);
      expect((d.lat, d.lng), (l.lat, l.lng));
    });

    test('不同 token 不共用族键', () {
      expect(placeDetailQueryFor('a', -6.235, 106.81),
          isNot(placeDetailQueryFor('b', -6.235, 106.81)));
    });
  });

  /// Story 1.11 · AC10：筛选进族键。
  group('筛选族键（Story 1.11）', () {
    test('勾选顺序不同、内容相同 → 同一族键（命中缓存）', () {
      final a = placeListQueryFor(-6.235, 106.81,
          filter: const PlaceListFilter(
              types: {PlaceType.cafe, PlaceType.park}, tags: {PlaceTag.petMenu}));
      final b = placeListQueryFor(-6.235, 106.81,
          filter: const PlaceListFilter(
              types: {PlaceType.park, PlaceType.cafe}, tags: {PlaceTag.petMenu}));
      expect(a, b);
      expect(a.hashCode, b.hashCode);
    });

    test('改筛选 → 新族键（新请求）', () {
      final a = placeListQueryFor(null, null,
          filter: const PlaceListFilter(types: {PlaceType.cafe}));
      final b = placeListQueryFor(null, null,
          filter: const PlaceListFilter(types: {PlaceType.cafe, PlaceType.park}));
      expect(a, isNot(b));
      expect(a, isNot(placeListRecentQuery));
    });

    test('空筛选 + 无坐标 == placeListRecentQuery', () {
      expect(placeListQueryFor(null, null, filter: const PlaceListFilter()),
          placeListRecentQuery);
    });

    test('送往服务端的参数按枚举声明顺序、用后端字面量', () {
      const f = PlaceListFilter(
          types: {PlaceType.park, PlaceType.cafe},
          tags: {PlaceTag.petMenu, PlaceTag.outdoorSeating});
      expect(f.typeParams, ['CAFE', 'PARK']);
      expect(f.tagParams, ['OUTDOOR_SEATING', 'PET_MENU']);
    });
  });
}
