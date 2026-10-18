/// V1.4.0 ① Tailrent Level 数据模型（Story 1.5 · AD-16）。
///
/// 后端只下发**代码与数字**，显示串全由 App 按代码本地化（`note` 例外，原样显示）。
/// `fromJson` 全部容忍缺字段：新字段可空、旧后端不崩；未知枚举值 → null。
library;

/// 段位（后端 `GrowthTier`）。
enum GrowthTier {
  newbie,
  buddy,
  guardian,
  star;

  /// `NEWBIE` / `BUDDY` / `GUARDIAN` / `STAR` → 枚举；未知 / null → null。
  static GrowthTier? fromCode(Object? code) => switch (code) {
        'NEWBIE' => GrowthTier.newbie,
        'BUDDY' => GrowthTier.buddy,
        'GUARDIAN' => GrowthTier.guardian,
        'STAR' => GrowthTier.star,
        _ => null,
      };
}

int? _int(Object? v) => (v as num?)?.toInt();

List<Map<String, dynamic>> _list(Object? v) =>
    v is List ? v.whereType<Map<String, dynamic>>().toList() : const [];

/// 等级表一行。
class GrowthLevelDef {
  const GrowthLevelDef({required this.level, required this.threshold, this.tier});

  final int level;
  final int threshold;
  final GrowthTier? tier;

  factory GrowthLevelDef.fromJson(Map<String, dynamic> j) => GrowthLevelDef(
        level: _int(j['level']) ?? 0,
        threshold: _int(j['threshold']) ?? 0,
        tier: GrowthTier.fromCode(j['tier']),
      );
}

/// `/me` 的成长值摘要（「我的」页等级胶囊）。不参与者后端不下发（整体 null）。
class GrowthBrief {
  const GrowthBrief({required this.level, this.tier, this.frame, this.lifetimeTotal = 0, this.nextThreshold});

  final int level;
  final GrowthTier? tier;

  /// 生效头像框代码（`NONE` / `BASIC` / `UPGRADED` / `ANIMATED`），Epic 3 用。
  final String? frame;
  final int lifetimeTotal;

  /// 满级为 null。
  final int? nextThreshold;

  /// 缺 `level` 视为无效摘要 → null（不显示胶囊，不闪「Lv1」）。
  static GrowthBrief? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    final level = _int(json['level']);
    if (level == null) return null;
    return GrowthBrief(
      level: level,
      tier: GrowthTier.fromCode(json['tier']),
      frame: json['frame'] as String?,
      lifetimeTotal: _int(json['lifetimeTotal']) ?? 0,
      nextThreshold: _int(json['nextThreshold']),
    );
  }
}

/// 头像框一档（Story 3.3 渲染；本 story 只解析）。
class GrowthFrameOption {
  const GrowthFrameOption({required this.frame, required this.unlocked, required this.unlockLevel});

  final String frame;
  final bool unlocked;
  final int unlockLevel;

  factory GrowthFrameOption.fromJson(Map<String, dynamic> j) => GrowthFrameOption(
        frame: (j['frame'] ?? '') as String,
        unlocked: (j['unlocked'] ?? false) as bool,
        unlockLevel: _int(j['unlockLevel']) ?? 0,
      );
}

class GrowthFrames {
  const GrowthFrames({this.effective, this.manual = false, this.options = const []});

  final String? effective;
  final bool manual;
  final List<GrowthFrameOption> options;

  static GrowthFrames? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    return GrowthFrames(
      effective: json['effective'] as String?,
      manual: (json['manual'] ?? false) as bool,
      options: _list(json['options']).map(GrowthFrameOption.fromJson).toList(),
    );
  }
}

/// 一条规则的配置与今日进度。
class GrowthRuleProgress {
  const GrowthRuleProgress({
    required this.code,
    required this.points,
    this.unitAmount,
    this.dailyCap,
    this.capGroup,
    this.todayCount,
    this.todayEarned = 0,
    this.maxedToday = false,
  });

  final String code;
  final int points;
  final int? unitAmount;
  final int? dailyCap;
  final String? capGroup;
  final int? todayCount;
  final int todayEarned;
  final bool maxedToday;

  factory GrowthRuleProgress.fromJson(Map<String, dynamic> j) => GrowthRuleProgress(
        code: (j['code'] ?? '') as String,
        points: _int(j['points']) ?? 0,
        unitAmount: _int(j['unitAmount']),
        dailyCap: _int(j['dailyCap']),
        capGroup: j['capGroup'] as String?,
        todayCount: _int(j['todayCount']),
        todayEarned: _int(j['todayEarned']) ?? 0,
        maxedToday: (j['maxedToday'] ?? false) as bool,
      );
}

/// 被动三项当天合计。
class GrowthPassiveGroup {
  const GrowthPassiveGroup({this.dailyCap, this.todayEarned = 0, this.maxedToday = false});

  final int? dailyCap;
  final int todayEarned;
  final bool maxedToday;

  static GrowthPassiveGroup? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    return GrowthPassiveGroup(
      dailyCap: _int(json['dailyCap']),
      todayEarned: _int(json['todayEarned']) ?? 0,
      maxedToday: (json['maxedToday'] ?? false) as bool,
    );
  }
}

/// 段位权益；`toLevel == null` = 最高段位（「Lv15+」）。`benefits` 为权益代码，未知代码由 UI 忽略。
class GrowthTierBenefits {
  const GrowthTierBenefits({this.tier, required this.fromLevel, this.toLevel, this.benefits = const []});

  final GrowthTier? tier;
  final int fromLevel;
  final int? toLevel;
  final List<String> benefits;

  factory GrowthTierBenefits.fromJson(Map<String, dynamic> j) => GrowthTierBenefits(
        tier: GrowthTier.fromCode(j['tier']),
        fromLevel: _int(j['fromLevel']) ?? 0,
        toLevel: _int(j['toLevel']),
        benefits: (j['benefits'] is List) ? (j['benefits'] as List).whereType<String>().toList() : const [],
      );
}

/// EXP 明细一行。
class GrowthLedgerEntry {
  const GrowthLedgerEntry({
    required this.ruleCode,
    required this.delta,
    this.reason,
    this.note,
    this.detailCode,
    this.createdAt,
  });

  final String ruleCode;
  final int delta;

  /// 扣回 / 调整原因代码；正向入账为 null。
  final String? reason;

  /// 运营填写的原因或活动名（原样显示）。
  final String? note;

  /// `SPEND_*` → 支付用途；`POST_PUBLISHED` → 帖子类型；其余 null。
  final String? detailCode;

  /// UTC。
  final DateTime? createdAt;

  factory GrowthLedgerEntry.fromJson(Map<String, dynamic> j) => GrowthLedgerEntry(
        ruleCode: (j['ruleCode'] ?? '') as String,
        delta: _int(j['delta']) ?? 0,
        reason: j['reason'] as String?,
        note: j['note'] as String?,
        detailCode: j['detailCode'] as String?,
        createdAt: j['createdAt'] == null ? null : DateTime.tryParse(j['createdAt'] as String),
      );
}

/// EXP 明细一页（`nextCursor == null` 为末页）。
class GrowthLedgerPage {
  const GrowthLedgerPage({this.items = const [], this.nextCursor});

  final List<GrowthLedgerEntry> items;
  final String? nextCursor;

  factory GrowthLedgerPage.fromJson(Map<String, dynamic> j) => GrowthLedgerPage(
        items: _list(j['items']).map(GrowthLedgerEntry.fromJson).toList(),
        nextCursor: j['nextCursor'] as String?,
      );
}

/// 等级页首屏整包（`GET /me/growth`）。
class GrowthSummary {
  const GrowthSummary({
    required this.participating,
    this.level = 1,
    this.tier,
    this.lifetimeTotal = 0,
    this.currentThreshold = 0,
    this.nextLevel,
    this.nextThreshold,
    this.levels = const [],
    this.frames,
    this.rules = const [],
    this.passiveGroup,
    this.tierBenefits = const [],
    this.recentLedger = const [],
  });

  final bool participating;
  final int level;
  final GrowthTier? tier;
  final int lifetimeTotal;
  final int currentThreshold;

  /// 满级为 null。
  final int? nextLevel;
  final int? nextThreshold;
  final List<GrowthLevelDef> levels;
  final GrowthFrames? frames;
  final List<GrowthRuleProgress> rules;
  final GrowthPassiveGroup? passiveGroup;
  final List<GrowthTierBenefits> tierBenefits;
  final List<GrowthLedgerEntry> recentLedger;

  bool get isMaxLevel => nextThreshold == null;

  factory GrowthSummary.fromJson(Map<String, dynamic> j) => GrowthSummary(
        participating: (j['participating'] ?? false) as bool,
        level: _int(j['level']) ?? 1,
        tier: GrowthTier.fromCode(j['tier']),
        lifetimeTotal: _int(j['lifetimeTotal']) ?? 0,
        currentThreshold: _int(j['currentThreshold']) ?? 0,
        nextLevel: _int(j['nextLevel']),
        nextThreshold: _int(j['nextThreshold']),
        levels: _list(j['levels']).map(GrowthLevelDef.fromJson).toList(),
        frames: GrowthFrames.fromJson(j['frames']),
        rules: _list(j['rules']).map(GrowthRuleProgress.fromJson).toList(),
        passiveGroup: GrowthPassiveGroup.fromJson(j['passiveGroup']),
        tierBenefits: _list(j['tierBenefits']).map(GrowthTierBenefits.fromJson).toList(),
        recentLedger: _list(j['recentLedger']).map(GrowthLedgerEntry.fromJson).toList(),
      );
}
