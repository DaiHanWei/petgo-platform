import '../../place/domain/place_summary.dart';

/// B4 整页落章的入参（V1.3.2 Story 1.3 · AC1），经 go_router `extra` 传入。
///
/// 🔴 **只由打卡成功页在 `isNewStamp == true` 分支构造**：重复到访没有新章可落，
/// 重播仪式会从惊喜变干扰（PRD 09-23）。B4 不接受任何「重复」入参，也不从护照页内部可达。
class NewStampArgs {
  const NewStampArgs({
    required this.placeToken,
    required this.placeName,
    required this.stampCount,
    this.placeType,
    this.stampImageUrl,
  });

  final String placeToken;
  final String placeName;
  final PlaceType? placeType;

  /// 场所专属章（Story 1.4 补；本 story 可空）。
  final String? stampImageUrl;

  /// 已集章数（「{n} cap terkumpul」，**无分母**）。
  final int stampCount;
}
