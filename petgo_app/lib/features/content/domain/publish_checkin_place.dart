import '../../place/domain/place_summary.dart';

/// 发帖页「打卡场所条」的展示数据（V1.3.2 Story 1.5 · AC3）。
///
/// 🔴 **只用于界面场所条与埋点，不进请求体** —— 请求体只带 `placeCheckinToken`，
/// 场所由服务端按打卡解析。发帖页因此不依赖 place 的 repository。
class PublishCheckinPlace {
  const PublishCheckinPlace({required this.placeToken, required this.placeName, this.placeType});

  final String placeToken;
  final String placeName;
  final PlaceType? placeType;
}
