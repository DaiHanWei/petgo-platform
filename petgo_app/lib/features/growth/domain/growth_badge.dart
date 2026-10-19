/// 头像等级角标 + 生效头像框（V1.4.0 ① Story 3.2 · AD-14）。拍平响应的 `authorLevel / authorTier / authorFrame`
/// （或无前缀的 `level / tier / frame`）与 `/me` 的嵌套 `growth` 都解析成它。字段缺失 = 不显示（老接口天然兼容）。
library;

import 'growth_models.dart';

/// 生效头像框三档；「不戴」与未知值都解析成 null（不画框）。
enum GrowthFrame {
  basic,
  upgraded,
  animated;

  static GrowthFrame? fromCode(Object? code) => switch (code) {
        'BASIC' => GrowthFrame.basic,
        'UPGRADED' => GrowthFrame.upgraded,
        'ANIMATED' => GrowthFrame.animated,
        _ => null,
      };
}

class GrowthBadge {
  const GrowthBadge({required this.level, required this.tier, this.frame});

  final int level;
  final GrowthTier tier;
  final GrowthFrame? frame;

  /// 拍平字段：`prefix: 'author'` 读 `authorLevel / authorTier / authorFrame`，`prefix: ''` 读 `level / tier / frame`。
  /// `level` 缺失 / 非数字或 `tier` 未知 → null。
  static GrowthBadge? fromFlat(Map<String, dynamic> json, {String prefix = 'author'}) {
    String key(String name) => prefix.isEmpty ? name : '$prefix${name[0].toUpperCase()}${name.substring(1)}';
    return _parse(json[key('level')], json[key('tier')], json[key('frame')]);
  }

  /// `/me` 的嵌套 `growth`（`{level, tier, frame, ...}`；`frame = NONE` → 不戴）。
  static GrowthBadge? fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) return null;
    return _parse(raw['level'], raw['tier'], raw['frame']);
  }

  /// 「我的」页：由 Story 1.5 已解析的 [GrowthBrief] 转来，不重复解析 `/me`。
  static GrowthBadge? fromBrief(GrowthBrief? brief) {
    final tier = brief?.tier;
    if (brief == null || tier == null) return null;
    return GrowthBadge(level: brief.level, tier: tier, frame: GrowthFrame.fromCode(brief.frame));
  }

  static GrowthBadge? _parse(Object? level, Object? tier, Object? frame) {
    if (level is! num) return null;
    final t = GrowthTier.fromCode(tier);
    if (t == null) return null;
    return GrowthBadge(level: level.toInt(), tier: t, frame: GrowthFrame.fromCode(frame));
  }

  @override
  bool operator ==(Object other) =>
      other is GrowthBadge && other.level == level && other.tier == tier && other.frame == frame;

  @override
  int get hashCode => Object.hash(level, tier, frame);
}
