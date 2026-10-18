/// 我的送礼记录的线格式（V1.4.0 gift Story 2.3 · AC4；接口 `GET /api/v1/me/gift-orders`）。
///
/// 🔴 状态 / 渠道原样是代码，App 本地化；礼物名只有 EN / ID 两份快照，按语言取（[giftNameFor]）。
/// 数字一律 `num → int` 容错；缺键按空 / 0，不抛。后端 NON_NULL：宠物已删时 `pet` 键缺省。
library;

import 'gift_send_result.dart';
import 'gift_showcase.dart';

int _int(Object? v) => v is num ? v.toInt() : 0;

String? _str(Object? v) => v is String && v.isNotEmpty ? v : null;

/// 收礼宠物（当下的名字与头像，宠物改名后显示新名）。
class GiftOrderPet {
  const GiftOrderPet({required this.petId, required this.name, this.avatarUrl});

  final int petId;
  final String name;
  final String? avatarUrl;

  factory GiftOrderPet.fromJson(Map<String, dynamic> j) => GiftOrderPet(
        petId: _int(j['petId']),
        name: (j['name'] as String?) ?? '',
        avatarUrl: _str(j['avatarUrl']),
      );
}

/// 送礼记录一行（`GiftOrderRow`）。
class GiftOrderRecord {
  const GiftOrderRecord({
    required this.orderToken,
    required this.status,
    required this.channel,
    required this.quantity,
    required this.total,
    this.createdAt,
    this.nameEn,
    this.nameId,
    this.iconUrl,
    this.unitPrice = 0,
    this.pet,
  });

  final String orderToken;
  final DateTime? createdAt;

  /// 状态（未知代码 → [GiftOrderStatus.unknown]，页面不显示胶囊、不渲染原始代码）。
  final GiftOrderStatus status;

  /// `PAWCOIN` / `QRIS`（品牌名，两语一致，不进 arb）。
  final String channel;
  final int quantity;
  final int total;
  final String? nameEn;
  final String? nameId;
  final String? iconUrl;
  final int unitPrice;

  /// 宠物已删除 → null。
  final GiftOrderPet? pet;

  /// 宠物已不存在（`petGone` 或缺 `pet`）：整行与头像不可点。
  bool get petGone => pet == null;

  /// 按语言取礼物名快照（`id` → 印尼语，其余 → 英语）。
  String giftName(String languageCode) => giftNameFor(languageCode, nameEn: nameEn, nameId: nameId);

  factory GiftOrderRecord.fromJson(Map<String, dynamic> j) {
    final gift = j['gift'] is Map ? (j['gift'] as Map).cast<String, dynamic>() : const <String, dynamic>{};
    final rawPet = j['pet'];
    final gone = j['petGone'] == true;
    return GiftOrderRecord(
      orderToken: (j['orderToken'] as String?) ?? '',
      createdAt: j['createdAt'] is String ? DateTime.tryParse(j['createdAt'] as String) : null,
      status: GiftOrderStatus.fromWire(j['status'] as String?),
      channel: (j['channel'] as String?) ?? '',
      quantity: _int(j['quantity']),
      total: _int(j['total']),
      nameEn: _str(gift['nameEn']),
      nameId: _str(gift['nameId']),
      iconUrl: _str(gift['iconUrl']),
      unitPrice: _int(gift['unitPrice']),
      pet: !gone && rawPet is Map ? GiftOrderPet.fromJson(rawPet.cast<String, dynamic>()) : null,
    );
  }
}

/// 送礼记录分页（`GiftOrderPage`）。[nextCursor] 是服务端不透明串，原样回传。
class GiftOrderHistoryPage {
  const GiftOrderHistoryPage({required this.items, this.nextCursor});

  final List<GiftOrderRecord> items;
  final String? nextCursor;

  factory GiftOrderHistoryPage.fromJson(Map<String, dynamic> j) => GiftOrderHistoryPage(
        items: [
          for (final e in (j['items'] is List ? j['items'] as List : const []))
            if (e is Map) GiftOrderRecord.fromJson(e.cast<String, dynamic>()),
        ],
        nextCursor: _str(j['nextCursor']),
      );
}
