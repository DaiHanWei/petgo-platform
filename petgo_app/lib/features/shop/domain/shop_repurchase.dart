/// 推荐域模型（Story 6.5 区域②，FR-107 / FR-93；v1.4.2 shop Story 9.4：区域①复购卡 `RepurchaseCard` 随 FR-136 删除）。
///
/// 🔒 **本文件不持有体重**：体重是 PII 邻近的健康数据，推荐结果只需要「够不够完整」
/// 这一个布尔（[Recommendations.degraded]），不需要数值（NFR-5）。
library;

/// 档案推荐结果（区域②，FR-107）。
class Recommendations {
  const Recommendations({
    required this.degraded,
    required this.items,
    this.missing,
    this.petName,
    this.profileSummary,
  });

  /// 档案摘要（v1.4.2 shop Story 6.1 AC13）：App 据此本地拼 A1 / A9 栏头依据行。
  /// 🔒 未建档 / 静默期 / 游客为 null —— 首页推荐块据此整块不显示。
  final ProfileSummary? profileSummary;

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
        profileSummary: j['profileSummary'] is Map
            ? ProfileSummary.fromJson(Map<String, dynamic>.from(j['profileSummary'] as Map))
            : null,
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
    this.mainImageUrl,
    this.promo = false,
  });

  final String productToken;
  final String name;
  final String? brand;
  /// 🔴 裸 objectKey，**不能直接喂给 Image** —— 客户端没有 key→URL 拼装
  /// （CDN base 只在服务端）。保留它是因为埋点与既有契约在用。显示一律走 [mainImageUrl]。
  final String? mainImageKey;

  /// 可直接显示的公开 URL（2026-08-27 由服务端补下发）。
  /// 在此之前推荐滑道只能渲染斜纹占位 —— 不是样式问题，是压根没有图可显示。
  final String? mainImageUrl;

  final int minPrice;

  /// 「Lagi promo」角标（v1.4.2 shop Story 2.2）；缺键按 false。
  final bool promo;

  /// 🔴 **推荐理由**（如 `Untuk anjing dewasa 10–25 kg`）。由服务端给 ——
  /// 前端自己拼会和实际用上的过滤维度对不上，那种「理由」比没有更糟。
  final String reason;

  factory RecommendationItem.fromJson(Map<String, dynamic> j) => RecommendationItem(
        productToken: j['productToken']?.toString() ?? '',
        name: j['name']?.toString() ?? '',
        brand: j['brand']?.toString(),
        mainImageKey: j['mainImageKey']?.toString(),
        mainImageUrl: j['mainImageUrl']?.toString(),
        minPrice: (j['minPrice'] as num?)?.toInt() ?? 0,
        reason: j['reason']?.toString() ?? '',
        promo: j['promo'] == true,
      );
}

/// 推荐档案摘要（v1.4.2 shop Story 6.1 AC13，后端 `RecommendationView.ProfileSummary`）。
///
/// 🔴 只有枚举，不带后端拼好的句子：App 用 arb 词表本地拼「Anjing kecil · dewasa」（D-17，不渲染后端显示串）。
class ProfileSummary {
  const ProfileSummary({
    required this.petName,
    this.species,
    this.sizeClass,
    this.ageStage,
    this.weightMissing = false,
  });

  final String petName;

  /// `DOG` / `CAT` / `UNIVERSAL`（其它物种）；未知为 null。
  final String? species;

  /// `SMALL` / `MEDIUM` / `LARGE`；体重未填为 null。
  final String? sizeClass;

  /// `PUPPY` / `ADULT` / `SENIOR`；生日缺失为 null。
  final String? ageStage;

  final bool weightMissing;

  factory ProfileSummary.fromJson(Map<String, dynamic> j) {
    String? s(Object? v) => (v == null || v.toString().isEmpty) ? null : v.toString();
    return ProfileSummary(
      petName: j['petName']?.toString() ?? '',
      species: s(j['species']),
      sizeClass: s(j['sizeClass']),
      ageStage: s(j['ageStage']),
      weightMissing: j['weightMissing'] == true,
    );
  }
}
