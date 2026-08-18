/// 复购引擎域模型（Story 6.4 区域① · 6.5 区域②，FR-107 / FR-109 / FR-93）。
///
/// 🔒 **本文件不持有体重**：体重是 PII 邻近的健康数据，推荐结果只需要「够不够完整」
/// 这一个布尔（[Recommendations.degraded]），不需要数值（NFR-5）。
library;

/// 补货提醒卡（区域①，FR-109）。
///
/// 🔴 **文案给「估算依据」而非断言**：用 [daysLeft] 渲染成「预计 ~N 天后吃完」，
/// **不写成确定事实** —— 档案体重不准或用户混喂时会有偏差。
class RepurchaseCard {
  const RepurchaseCard({
    required this.triggerId,
    required this.triggerType,
    required this.productToken,
    required this.productName,
    required this.daysLeft,
    this.petName,
  });

  final int triggerId;

  /// ⚠️ 本版本只会是 `FOOD_LOW`：驱虫/疫苗（FR-108）已挪 1.2.0（C-11）。
  /// 原型画的驱虫卡按 UX-DR1 已删。
  final String triggerType;
  final String productToken;
  final String productName;
  final String? petName;

  /// 距预估耗尽还有几天。**可能为负** = 已过预估耗尽日。
  final int daysLeft;

  bool get isOverdue => daysLeft < 0;

  factory RepurchaseCard.fromJson(Map<String, dynamic> j) => RepurchaseCard(
        triggerId: (j['triggerId'] as num?)?.toInt() ?? 0,
        triggerType: j['triggerType']?.toString() ?? '',
        productToken: j['productToken']?.toString() ?? '',
        productName: j['productName']?.toString() ?? '',
        petName: j['petName']?.toString(),
        daysLeft: (j['daysLeft'] as num?)?.toInt() ?? 0,
      );
}

/// 档案推荐结果（区域②，FR-107）。
class Recommendations {
  const Recommendations({
    required this.degraded,
    required this.items,
    this.missing,
    this.petName,
  });

  /// 🔴 档案不完整 → 结果已降级为按物种推荐，尾部要展示「补全档案，推荐更准」引导卡。
  final bool degraded;

  /// `GUEST`（未登录，整区不渲染）/ `PROFILE`（已登录未建档）/ `WEIGHT` / `AGE` / `BOTH` / `NONE`。
  ///
  /// 🔴 <b>GUEST 与 PROFILE 必须分开</b>：游客整区不渲染，已登录未建档才换成建档引导卡
  /// （FR-93 状态矩阵第 1 行 vs 第 2 行）。混成一个值会让游客看到一张点下去就是登录墙的卡。
  final String? missing;
  final String? petName;
  final List<RecommendationItem> items;

  /// 未登录 → 整区不渲染（不是空态、不是建档卡）。
  bool get isGuest => missing == 'GUEST';

  /// 🔴 已登录未建档 → 整区**替换为建档引导卡**（复用 FR-0G 文案，不新建）。
  bool get needsProfileCreation => missing == 'PROFILE';

  /// 已建档但缺体重/年龄 → 正常出推荐 + 尾部引导卡。
  bool get needsProfileCompletion => degraded && !needsProfileCreation && !isGuest;

  factory Recommendations.fromJson(Map<String, dynamic> j) => Recommendations(
        degraded: j['degraded'] == true,
        missing: j['missing']?.toString(),
        petName: j['petName']?.toString(),
        items: j['items'] is List
            ? (j['items'] as List)
                .whereType<Map<String, dynamic>>()
                .map(RecommendationItem.fromJson)
                .toList(growable: false)
            : const [],
      );
}

/// 一条推荐。
class RecommendationItem {
  const RecommendationItem({
    required this.productToken,
    required this.name,
    required this.minPrice,
    required this.reason,
    this.brand,
    this.mainImageKey,
  });

  final String productToken;
  final String name;
  final String? brand;
  final String? mainImageKey;
  final int minPrice;

  /// 🔴 **推荐理由**（如 `Untuk anjing dewasa 10–25 kg`）。由服务端给 ——
  /// 前端自己拼会和实际用上的过滤维度对不上，那种「理由」比没有更糟。
  final String reason;

  factory RecommendationItem.fromJson(Map<String, dynamic> j) => RecommendationItem(
        productToken: j['productToken']?.toString() ?? '',
        name: j['name']?.toString() ?? '',
        brand: j['brand']?.toString(),
        mainImageKey: j['mainImageKey']?.toString(),
        minPrice: (j['minPrice'] as num?)?.toInt() ?? 0,
        reason: j['reason']?.toString() ?? '',
      );
}
