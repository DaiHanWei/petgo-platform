import 'place_summary.dart';

/// 打卡成功结果（V1.3.2 Story 1.1，消费 `POST /api/v1/places/{token}/checkins` 的 201 响应）。
///
/// 字段与后端 `PlaceCheckinResponse` 一一对应；契约由 `test/place/place_checkin_result_test.dart` 钉住。
///
/// 🔴 **没有距离、没有坐标**：服务端刻意不回（防试探 500 m 边界 + 坐标不外流）。
class PlaceCheckinResult {
  const PlaceCheckinResult({
    required this.checkinToken,
    required this.placeToken,
    required this.placeName,
    required this.isNewStamp,
    required this.visitCount,
    this.placeType,
    this.visitDate,
    this.passportNo,
    this.stampCount,
  });

  /// 本次打卡的不可枚举标识（Story 1.5 顺手发帖时关联用）。
  final String checkinToken;

  /// 打卡落在的场所 token —— 合并过的场所是**保留方**的 token，以它为准。
  final String placeToken;
  final String placeName;

  /// null = 服务端给了客户端不认识的类型 → 章面回落通用占位。
  final PlaceType? placeType;

  /// 打卡的 WIB 自然日（yyyy-MM-dd，只有日期）。
  final DateTime? visitDate;

  /// true = 新章（C2）；false = 再访（C2b，章角标 ×[visitCount]）。
  final bool isNewStamp;

  /// 写入后该宠物在这里的打卡总数（= 章的次数）。
  final int visitCount;

  /// 宠物护照号（Story 1.2：首次打卡同一事务内签发）。老后端不下发 → null。
  final String? passportNo;

  /// 写入后该宠物的章数（Story 1.2；无分母）。老后端不下发 → null。
  final int? stampCount;

  factory PlaceCheckinResult.fromJson(Map<String, dynamic> json) {
    final count = json['visitCount'];
    return PlaceCheckinResult(
      checkinToken: json['checkinToken']?.toString() ?? '',
      placeToken: json['placeToken']?.toString() ?? '',
      placeName: json['placeName']?.toString() ?? '',
      placeType: PlaceType.fromApi(json['placeType']?.toString()),
      visitDate: DateTime.tryParse(json['visitDate']?.toString() ?? ''),
      isNewStamp: json['isNewStamp'] == true,
      visitCount: count is num && count > 0 ? count.toInt() : 1,
      passportNo: _blankToNull(json['passportNo']?.toString()),
      stampCount: json['stampCount'] is num ? (json['stampCount'] as num).toInt() : null,
    );
  }

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;
}
