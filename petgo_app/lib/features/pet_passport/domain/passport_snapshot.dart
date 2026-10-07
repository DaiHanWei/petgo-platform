import 'pet_passport.dart';

/// 已买护照版本列表的一行（V1.3.2 Story 3.4 · 后端 `PassportSnapshotListResponse.Item`）。
class PassportSnapshotItem {
  const PassportSnapshotItem({required this.snapshotToken, required this.paidAt, required this.stampCount});

  final String snapshotToken;
  final DateTime paidAt;
  final int stampCount;

  static List<PassportSnapshotItem> listFromJson(Map<String, dynamic> json) {
    final raw = json['items'];
    if (raw is! List) return const [];
    return raw.whereType<Map>().map((e) {
      final m = Map<String, dynamic>.from(e);
      return PassportSnapshotItem(
        snapshotToken: m['snapshotToken']?.toString() ?? '',
        paidAt: DateTime.tryParse(m['paidAt']?.toString() ?? '') ?? DateTime.fromMillisecondsSinceEpoch(0),
        stampCount: (m['stampCount'] as num?)?.toInt() ?? 0,
      );
    }).where((i) => i.snapshotToken.isNotEmpty).toList(growable: false);
  }
}

/// 已买版本回看（V1.3.2 Story 3.4 · 后端 `PassportSnapshotDetailResponse`）：**冻结**的章 + 当前护照号 / 宠物名。
///
/// 章的键名与实时护照同名，直接复用 [PassportStamp.fromJson]；冻结的章不带 `placeStatus`
/// → `available == false`（回看只读，章本体本就不可点）。
class PassportSnapshotDetail {
  const PassportSnapshotDetail({
    required this.snapshotToken,
    required this.paidAt,
    required this.petName,
    required this.passportNo,
    required this.stamps,
  });

  final String snapshotToken;
  final DateTime paidAt;
  final String petName;
  final String passportNo;
  final List<PassportStamp> stamps;

  factory PassportSnapshotDetail.fromJson(Map<String, dynamic> json) {
    final raw = json['stamps'];
    return PassportSnapshotDetail(
      snapshotToken: json['snapshotToken']?.toString() ?? '',
      paidAt: DateTime.tryParse(json['paidAt']?.toString() ?? '') ?? DateTime.fromMillisecondsSinceEpoch(0),
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
