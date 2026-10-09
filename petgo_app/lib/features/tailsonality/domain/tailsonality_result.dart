import '../../keepsake/domain/keepsake_pricing.dart';

/// Tailsonality 测试结果（V1.3.2 Story 2.1 · 后端 `TailsonalityResultResponse`）。
///
/// 🔴 字段全集与后端 `TailsonalityResultResponseContractTest.FULL_FIELDS` 同集；
/// `test/tailsonality/tailsonality_result_wire_contract_test.dart` 钉住。
class TailsonalityResult {
  const TailsonalityResult({
    required this.token,
    required this.typeCode,
    required this.letters,
    required this.energy,
    required this.questionSet,
    required this.resultIndex,
    required this.unlocked,
    this.unlockedAt,
    required this.contentVersion,
    required this.createdAt,
    this.equipped = false,
    this.matchUnlocked = false,
    this.upgradePrice,
  });

  /// 不可枚举对外标识（32 位）。
  final String token;

  /// 完整代号，如 `ENTJ-H`。
  final String typeCode;

  /// 四字母（社交-探索-情绪-驱动），如 `ENTJ`。
  final String letters;

  /// 能量后缀 `H` / `L`。
  final String energy;

  /// 题套 `CAT` / `DOG` / `GENERAL`（服务端按物种选）。
  final String questionSet;

  /// 同宠物第几次测试（1 起），埋点 `result_index` 用。
  final int resultIndex;

  /// 是否已解锁。🔴 缺键 / 非 bool 一律 false（fail-closed：不能让未付费结果显示成已解锁）。
  final bool unlocked;

  /// 解锁时刻；未解锁时后端省略该键 → null。
  final DateTime? unlockedAt;

  final int contentVersion;
  final DateTime createdAt;

  /// 当前宠物佩戴的就是本结果（V1.3.2 Story 3.3）。缺键 / 非 bool → false。
  final bool equipped;

  /// 配型可看（完整解读已解锁，或单独买过配型；2026-10-09 配型改回付费）。
  /// 🔴 缺键 / 非 bool 一律 false（fail-closed，同 [unlocked]）。
  final bool matchUnlocked;

  /// 已单独买过配型时，完整解读的补差价（IDR）；其余情况后端省略 → null，按定价接口原价。
  final int? upgradePrice;

  /// 本结果完整解读的应付价：补差价优先，否则定价接口原价；都没有 → null（按钮显示「…」/ 重试）。
  int? fullUnlockPrice(KeepsakePricing? pricing) => upgradePrice ?? pricing?.tailsonality;

  factory TailsonalityResult.fromJson(Map<String, dynamic> json) {
    final rawUnlockedAt = json['unlockedAt'];
    return TailsonalityResult(
      token: json['token'] as String,
      typeCode: json['typeCode'] as String,
      letters: json['letters'] as String,
      energy: json['energy'] as String,
      questionSet: json['questionSet'] as String,
      resultIndex: (json['resultIndex'] as num).toInt(),
      unlocked: json['unlocked'] == true,
      unlockedAt: rawUnlockedAt is String ? DateTime.parse(rawUnlockedAt) : null,
      contentVersion: (json['contentVersion'] as num?)?.toInt() ?? 1,
      createdAt: DateTime.parse(json['createdAt'] as String),
      equipped: json['equipped'] == true,
      matchUnlocked: json['matchUnlocked'] == true,
      upgradePrice: (json['upgradePrice'] as num?)?.toInt(),
    );
  }
}
