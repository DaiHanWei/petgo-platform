import 'dart:ui';

import 'gift_order_browse.dart' show GiftOrderSender;

/// 单笔礼物详情（V1.5.0 gift-pack Story 2-5 / 2-6 · 后端 `GiftOrderDetail`）。
/// 全局 JSON `non_null`：访客视角 `opened` / `ownerExp` 省略；非拆礼物接口 `alreadyOpened` 省略；无 / 已隐藏祝福语 `message` 省略。
class GiftOrderDetail {
  const GiftOrderDetail({
    required this.orderToken,
    required this.petId,
    required this.petName,
    required this.giftNameEn,
    required this.giftNameId,
    this.giftIconUrl,
    this.quantity = 1,
    this.receivedAt,
    this.opened,
    this.alreadyOpened,
    this.message,
    this.messageHidden = false,
    this.sender = const GiftDetailSender(),
    this.ownerExp,
    this.canReport = false,
  });

  final String orderToken;
  final int petId;
  final String petName;
  final String giftNameEn;
  final String giftNameId;
  final String? giftIconUrl;
  final int quantity;
  final DateTime? receivedAt;
  final bool? opened;
  final bool? alreadyOpened;
  final String? message;
  final bool messageHidden;
  final GiftDetailSender sender;
  final int? ownerExp;
  final bool canReport;

  String giftNameFor(Locale locale) {
    final preferred = locale.languageCode == 'id' ? giftNameId : giftNameEn;
    if (preferred.isNotEmpty) return preferred;
    return locale.languageCode == 'id' ? giftNameEn : giftNameId;
  }

  factory GiftOrderDetail.fromJson(Map<String, dynamic> j) => GiftOrderDetail(
        orderToken: j['orderToken'] as String? ?? '',
        petId: (j['petId'] as num?)?.toInt() ?? 0,
        petName: j['petName'] as String? ?? '',
        giftNameEn: j['giftNameEn'] as String? ?? '',
        giftNameId: j['giftNameId'] as String? ?? '',
        giftIconUrl: j['giftIconUrl'] as String?,
        quantity: (j['quantity'] as num?)?.toInt() ?? 1,
        receivedAt: DateTime.tryParse(j['receivedAt'] as String? ?? ''),
        opened: j['opened'] as bool?,
        alreadyOpened: j['alreadyOpened'] as bool?,
        message: j['message'] as String?,
        messageHidden: j['messageHidden'] == true,
        sender: j['sender'] is Map
            ? GiftDetailSender.fromJson((j['sender'] as Map).cast<String, dynamic>())
            : const GiftDetailSender(deleted: true),
        ownerExp: (j['ownerExp'] as num?)?.toInt(),
        canReport: j['canReport'] == true,
      );
}

/// 送礼人（注销：userId / nickname / avatarUrl 为 null，deleted = true，petNames 为空）。
class GiftDetailSender {
  const GiftDetailSender({this.userId, this.nickname, this.avatarUrl, this.deleted = false, this.petNames = const []});

  final int? userId;
  final String? nickname;
  final String? avatarUrl;
  final bool deleted;
  final List<String> petNames;

  GiftOrderSender get brief => GiftOrderSender(nickname: nickname, avatarUrl: avatarUrl, deleted: deleted);

  factory GiftDetailSender.fromJson(Map<String, dynamic> j) => GiftDetailSender(
        userId: (j['userId'] as num?)?.toInt(),
        nickname: j['nickname'] as String?,
        avatarUrl: j['avatarUrl'] as String?,
        deleted: j['deleted'] == true,
        petNames: j['petNames'] is List ? [for (final n in j['petNames'] as List) '$n'] : const [],
      );
}

/// 拆礼物响应：`items` 按送达时刻倒序，`totalOwnerExp = Σ ownerExp`。
class GiftOpenResult {
  const GiftOpenResult({this.items = const [], this.totalOwnerExp = 0});

  final List<GiftOrderDetail> items;
  final int totalOwnerExp;

  factory GiftOpenResult.fromJson(Map<String, dynamic> j) => GiftOpenResult(
        items: j['items'] is List
            ? [
                for (final i in j['items'] as List)
                  if (i is Map) GiftOrderDetail.fromJson(i.cast<String, dynamic>()),
              ]
            : const [],
        totalOwnerExp: (j['totalOwnerExp'] as num?)?.toInt() ?? 0,
      );
}
