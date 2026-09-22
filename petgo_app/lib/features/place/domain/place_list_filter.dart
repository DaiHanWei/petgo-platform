import 'package:flutter/foundation.dart' show setEquals;

import 'place_summary.dart';

/// 场所列表的类型 / 标签筛选（V1.3.0 batch-b1 Story 1.11 · 决策 B1-D14）。
///
/// 语义由**服务端**执行（`GET /api/v1/places` 的可重复 `type` / `tag` 参数）：
/// 类型之间「或」、标签之间「且」、两维度之间「且」。客户端只负责把选择原样送过去。
///
/// 🔴 **必须结构相等**：它是列表 family 族键（[PlaceListQuery]）的一个字段。
/// record 对字段用 `==` 比较，而 `Set` / `List` 的 `==` 是**身份相等** —— 直接把 Set
/// 塞进 record，每次 build 都是一个新族键 → 无限重拉。所以这里手写 `==` / `hashCode`
/// （无序集合比较），两个勾选顺序不同但内容相同的筛选命中同一份缓存（AC10）。
///
/// 筛选**只在本次页面生命周期有效**（AC10），持久化不归这里管 —— 也不该有人加。
class PlaceListFilter {
  const PlaceListFilter({this.types = const {}, this.tags = const {}});

  /// 不筛（与 Story 1.11 之前的请求一字不差）。
  static const PlaceListFilter none = PlaceListFilter();

  final Set<PlaceType> types;
  final Set<PlaceTag> tags;

  bool get isEmpty => types.isEmpty && tags.isEmpty;
  bool get isNotEmpty => !isEmpty;

  /// 送往服务端的 `type` 值（按枚举声明顺序排，请求 URL 稳定、便于日志比对）。
  List<String> get typeParams => [
        for (final t in PlaceType.values)
          if (types.contains(t)) t.api,
      ];

  /// 送往服务端的 `tag` 值（同上，按枚举声明顺序）。
  List<String> get tagParams => [
        for (final t in PlaceTag.values)
          if (tags.contains(t)) t.api,
      ];

  PlaceListFilter withTypes(Set<PlaceType> v) =>
      PlaceListFilter(types: Set.unmodifiable(v), tags: tags);

  PlaceListFilter withTags(Set<PlaceTag> v) =>
      PlaceListFilter(types: types, tags: Set.unmodifiable(v));

  @override
  bool operator ==(Object other) =>
      other is PlaceListFilter &&
      setEquals(other.types, types) &&
      setEquals(other.tags, tags);

  @override
  int get hashCode => Object.hash(
      Object.hashAllUnordered(types), Object.hashAllUnordered(tags));

  @override
  String toString() => 'PlaceListFilter(types: $typeParams, tags: $tagParams)';
}
