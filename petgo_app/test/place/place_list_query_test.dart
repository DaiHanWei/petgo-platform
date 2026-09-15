import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';

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
    expect(placeListQueryFor(-6.2354999, 106.8104999), (lat: -6.235, lng: 106.810));
  });

  test('族键是 record，结构相等可直接比较（family 的缓存依赖这一点）', () {
    expect(placeListQueryFor(-6.235, 106.81) == placeListQueryFor(-6.235, 106.81), isTrue);
    expect(placeListQueryFor(-6.235, 106.81).hashCode,
        placeListQueryFor(-6.235, 106.81).hashCode);
  });
}
