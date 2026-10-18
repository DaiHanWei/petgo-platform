/// 升级庆祝 / 存量汇总弹窗模型（V1.4.0 ① Story 2.3 · AD-11，对应 `GET /api/v1/me/growth/popups`）。
/// `fromJson` 对缺字段 / 类型不对的单项返回 null，不抛。段位代码复用 [GrowthTier]，权益代码保持字符串（与等级页同一套映射）。
library;

import 'growth_models.dart';

/// 跨段位后的头像框动作（后端 `FrameAction`）；未知值按 [none]。
enum GrowthFrameAction {
  auto,
  offerNewFrame,
  none;

  static GrowthFrameAction fromCode(Object? code) => switch (code) {
        'AUTO' => GrowthFrameAction.auto,
        'OFFER_NEW_FRAME' => GrowthFrameAction.offerNewFrame,
        _ => GrowthFrameAction.none,
      };
}

int? _int(Object? v) => v is num ? v.toInt() : null;

class GrowthCelebration {
  const GrowthCelebration({
    required this.fromLevel,
    required this.toLevel,
    required this.tierUp,
    this.toTier,
    this.lifetimeTotal = 0,
    this.unlockedBenefits = const [],
    this.frameAction = GrowthFrameAction.none,
  });

  final int fromLevel;
  final int toLevel;
  final bool tierUp;
  final GrowthTier? toTier;
  final int lifetimeTotal;
  final List<String> unlockedBenefits;
  final GrowthFrameAction frameAction;

  static GrowthCelebration? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    final from = _int(json['fromLevel']);
    final to = _int(json['toLevel']);
    if (from == null || to == null) return null;
    final benefits = json['unlockedBenefits'];
    return GrowthCelebration(
      fromLevel: from,
      toLevel: to,
      tierUp: json['tierUp'] == true,
      toTier: GrowthTier.fromCode(json['toTier']),
      lifetimeTotal: _int(json['lifetimeTotal']) ?? 0,
      unlockedBenefits: benefits is List ? benefits.whereType<String>().toList() : const [],
      frameAction: GrowthFrameAction.fromCode(json['frameAction']),
    );
  }
}

class GrowthBackfill {
  const GrowthBackfill({
    required this.level,
    this.tier,
    this.activeDays = 0,
    this.milestoneCount = 0,
    this.milestoneExp = 0,
    this.total = 0,
  });

  final int level;
  final GrowthTier? tier;
  final int activeDays;
  final int milestoneCount;
  final int milestoneExp;
  final int total;

  static GrowthBackfill? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    final level = _int(json['level']);
    if (level == null) return null;
    return GrowthBackfill(
      level: level,
      tier: GrowthTier.fromCode(json['tier']),
      activeDays: _int(json['activeDays']) ?? 0,
      milestoneCount: _int(json['milestoneCount']) ?? 0,
      milestoneExp: _int(json['milestoneExp']) ?? 0,
      total: _int(json['total']) ?? 0,
    );
  }
}

class GrowthPopups {
  const GrowthPopups({this.celebration, this.backfill});

  final GrowthCelebration? celebration;
  final GrowthBackfill? backfill;

  bool get isEmpty => celebration == null && backfill == null;

  static GrowthPopups fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return const GrowthPopups();
    return GrowthPopups(
      celebration: GrowthCelebration.fromJson(json['celebration']),
      backfill: GrowthBackfill.fromJson(json['backfill']),
    );
  }
}

/// 升级通知 `targetRef = "{toLevel}:{TIER}"`（Story 2.1 契约）解析；格式不对 → null（调用方兜底系统文案）。
({int level, GrowthTier tier})? parseLevelUpRef(String? ref) {
  if (ref == null) return null;
  final parts = ref.split(':');
  if (parts.length != 2) return null;
  final level = int.tryParse(parts[0]);
  final tier = GrowthTier.fromCode(parts[1]);
  if (level == null || level <= 0 || tier == null) return null;
  return (level: level, tier: tier);
}
