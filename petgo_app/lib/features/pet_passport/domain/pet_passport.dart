import '../../place/domain/place_summary.dart';

/// 宠物护照（V1.3.2 batch-a Story 1.2，消费 `GET /api/v1/pet-profiles/me/passport`）。
///
/// 字段与后端 `PetPassportResponse` 一一对应；契约由 `test/pet_passport/pet_passport_wire_contract_test.dart` 钉住。
///
/// 🔴 **没有任何总数分母**：场所由运营持续增加，写死分母 = 一个永远填不满的假进度（AC3.3）。
class PetPassport {
  const PetPassport({
    required this.petName,
    required this.passportNo,
    required this.stamps,
  });

  final String petName;

  /// 12 位连写（例 `TT02P2600128`），页眉单独一行、等宽数字、不截断。
  final String passportNo;

  /// 按首次到访升序（新章恒在最后）。
  final List<PassportStamp> stamps;

  int get stampCount => stamps.length;

  /// `focus` 定位：该 token 在 [stamps] 里的下标；找不到 → 0（停第 1 页）。
  int indexOfToken(String? token) {
    if (token == null) return 0;
    final i = stamps.indexWhere((s) => s.placeToken == token);
    return i < 0 ? 0 : i;
  }

  factory PetPassport.fromJson(Map<String, dynamic> json) {
    final raw = json['stamps'];
    return PetPassport(
      petName: json['petName']?.toString() ?? '',
      passportNo: json['passportNo']?.toString() ?? '',
      stamps: raw is! List
          ? const []
          : raw
              .whereType<Map>()
              .map((e) => PassportStamp.fromJson(Map<String, dynamic>.from(e)))
              .where((s) => s.placeToken.isNotEmpty)
              .toList(growable: false),
    );
  }
}

/// 一枚章 = 该宠物在某场所（当前场所）全部打卡的聚合（AD-5）。
class PassportStamp {
  const PassportStamp({
    required this.placeToken,
    required this.placeName,
    required this.available,
    required this.visitCount,
    this.placeType,
    this.stampImageUrl,
    this.firstVisitDate,
  });

  final String placeToken;
  final String placeName;

  /// null = 客户端不认识的类型 → 章面回落通用占位。
  final PlaceType? placeType;

  /// `placeStatus == ACTIVE`。🔴 缺键 / 未知值一律视为不可用（fail-closed）。
  final bool available;

  /// 场所专属章（Story 1.4 起有值）；null → 默认章 → 占位章。
  final String? stampImageUrl;

  final DateTime? firstVisitDate;
  final int visitCount;

  factory PassportStamp.fromJson(Map<String, dynamic> json) {
    final count = json['visitCount'];
    final url = json['stampImageUrl']?.toString();
    return PassportStamp(
      placeToken: json['placeToken']?.toString() ?? '',
      placeName: json['placeName']?.toString() ?? '',
      placeType: PlaceType.fromApi(json['placeType']?.toString()),
      available: json['placeStatus'] == 'ACTIVE',
      stampImageUrl: (url == null || url.isEmpty) ? null : url,
      firstVisitDate: DateTime.tryParse(json['firstVisitDate']?.toString() ?? ''),
      visitCount: count is num && count > 0 ? count.toInt() : 1,
    );
  }
}
