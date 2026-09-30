import '../../profile/presentation/pet_insights_page.dart';

/// Tailsonality 路由路径（V1.3.2 Story 2.3）。
///
/// 🔴 路径值基于 [PetInsightsRoutes.hub] 拼接，**全部落在 `/profile/` 前缀下** → 自动继承游客门控；
/// 绝不进路由表的 `_controlledExactExceptions`（安全规则层只升不降）。改聚合页路径时这里跟着动。
class TailsonalityRoutes {
  TailsonalityRoutes._();

  static const String base = '${PetInsightsRoutes.hub}/tailsonality';

  /// 答题页（3 页 × 6 题）。
  static const String quiz = '$base/quiz';

  /// 结果列表（Story 2.6 注册页面）。
  static const String results = '$base/results';

  /// 单次结果页路由模板。
  static const String resultPattern = '$results/:token';

  static String result(String token) => '$results/${Uri.encodeComponent(token)}';

  /// 主人配型页（Story 2.5），挂在某次结果下。
  static const String matchPattern = '$resultPattern/match';

  static String match(String token) => '${result(token)}/match';
}
