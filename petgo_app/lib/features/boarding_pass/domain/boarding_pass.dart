import '../../place/domain/place_summary.dart';

/// 登机牌列表（V1.3.2 Story 3.5 · 后端 `BoardingPassListResponse`）：每个打过卡的（当前）场所一张，最近到访在前。
class BoardingPassList {
  const BoardingPassList({required this.petName, required this.passportNo, required this.items});

  final String petName;
  final String? passportNo;
  final List<BoardingPassItem> items;

  factory BoardingPassList.fromJson(Map<String, dynamic> json) {
    final raw = json['items'];
    return BoardingPassList(
      petName: json['petName']?.toString() ?? '',
      passportNo: _blankToNull(json['passportNo']?.toString()),
      items: raw is! List
          ? const []
          : raw
              .whereType<Map>()
              .map((e) => BoardingPassItem.fromJson(Map<String, dynamic>.from(e)))
              .where((i) => i.placeToken.isNotEmpty)
              .toList(growable: false),
    );
  }
}

class BoardingPassItem {
  const BoardingPassItem({
    required this.placeToken,
    required this.placeName,
    required this.available,
    required this.visitCount,
    required this.unlocked,
    this.placeType,
    this.stampImageUrl,
    this.lastVisitDate,
  });

  final String placeToken;
  final String placeName;
  final PlaceType? placeType;

  /// `placeStatus == ACTIVE`；缺键 / 未知 → false（fail-closed）。
  final bool available;
  final String? stampImageUrl;
  final DateTime? lastVisitDate;
  final int visitCount;

  /// 🔴 缺键 / 非 bool → false（不能把没买的显示成已买）。
  final bool unlocked;

  factory BoardingPassItem.fromJson(Map<String, dynamic> json) {
    final count = json['visitCount'];
    return BoardingPassItem(
      placeToken: json['placeToken']?.toString() ?? '',
      placeName: json['placeName']?.toString() ?? '',
      placeType: PlaceType.fromApi(json['placeType']?.toString()),
      available: json['placeStatus'] == 'ACTIVE',
      stampImageUrl: _blankToNull(json['stampImageUrl']?.toString()),
      lastVisitDate: DateTime.tryParse(json['lastVisitDate']?.toString() ?? ''),
      visitCount: count is num && count > 0 ? count.toInt() : 1,
      unlocked: json['unlocked'] == true,
    );
  }
}

/// 登机牌详情（V1.3.2 Story 3.5 · 后端 `BoardingPassDetailResponse`）。不含距离、不含内部 id。
class BoardingPassDetail {
  const BoardingPassDetail({
    required this.placeToken,
    required this.passenger,
    required this.placeName,
    required this.seat,
    required this.visitCount,
    required this.available,
    required this.unlocked,
    this.breed,
    this.passportNo,
    this.lastVisitDate,
    this.firstVisitDate,
    this.placeType,
    this.placeImageUrl,
    this.stampImageUrl,
    this.addressText,
    this.city,
    this.unlockToken,
  });

  /// 解析后的场所 token（MERGED → 保留方）；地址条跳场所详情用它。
  final String placeToken;
  final String passenger;
  final String? breed;
  final String placeName;
  final String? passportNo;
  final DateTime? lastVisitDate;
  final DateTime? firstVisitDate;
  final int visitCount;
  final String seat;
  final PlaceType? placeType;
  final bool available;
  final String? placeImageUrl;
  final String? stampImageUrl;

  /// 仅 ACTIVE 时服务端下发。
  final String? addressText;
  final String? city;
  final bool unlocked;
  final String? unlockToken;

  factory BoardingPassDetail.fromJson(Map<String, dynamic> json) {
    final count = json['visitCount'];
    return BoardingPassDetail(
      placeToken: json['placeToken']?.toString() ?? '',
      passenger: json['passenger']?.toString() ?? '',
      breed: _blankToNull(json['breed']?.toString()),
      placeName: json['placeName']?.toString() ?? '',
      passportNo: _blankToNull(json['passportNo']?.toString()),
      lastVisitDate: DateTime.tryParse(json['lastVisitDate']?.toString() ?? ''),
      firstVisitDate: DateTime.tryParse(json['firstVisitDate']?.toString() ?? ''),
      visitCount: count is num && count > 0 ? count.toInt() : 1,
      seat: json['seat']?.toString() ?? '',
      placeType: PlaceType.fromApi(json['placeType']?.toString()),
      available: json['placeStatus'] == 'ACTIVE',
      placeImageUrl: _blankToNull(json['placeImageUrl']?.toString()),
      stampImageUrl: _blankToNull(json['stampImageUrl']?.toString()),
      addressText: _blankToNull(json['addressText']?.toString()),
      city: _blankToNull(json['city']?.toString()),
      unlocked: json['unlocked'] == true,
      unlockToken: _blankToNull(json['unlockToken']?.toString()),
    );
  }
}

String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;
