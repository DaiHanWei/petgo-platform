import 'dart:ui';

import 'gift_showcase.dart';

/// 逐笔礼物（V1.5.0 gift-pack Story 2-4 · 后端 `GiftOrderCard` / `PetGiftOrderPage` / `GiftOrderPreview`）。
///
/// 全局 JSON `non_null`：访客视角 `opened` / `unopenedCount` 省略 = null；翻页省略 `categories` / `pet`。

int _int(Object? v) => v is num ? v.toInt() : int.tryParse('$v') ?? 0;

/// 送礼人（注销：nickname / avatarUrl 为 null，deleted = true）。
class GiftOrderSender {
  const GiftOrderSender({this.nickname, this.avatarUrl, this.deleted = false});

  final String? nickname;
  final String? avatarUrl;
  final bool deleted;

  factory GiftOrderSender.fromJson(Map<String, dynamic> j) => GiftOrderSender(
        nickname: j['nickname'] as String?,
        avatarUrl: j['avatarUrl'] as String?,
        deleted: j['deleted'] == true,
      );
}

/// 一份礼物（一笔订单）。
class GiftOrderCard {
  const GiftOrderCard({
    required this.orderToken,
    required this.giftNameEn,
    required this.giftNameId,
    this.giftIconUrl,
    this.quantity = 1,
    this.receivedAt,
    this.sender = const GiftOrderSender(),
    this.opened,
    this.hasMessage = false,
  });

  final String orderToken;
  final String giftNameEn;
  final String giftNameId;
  final String? giftIconUrl;
  final int quantity;
  final DateTime? receivedAt;
  final GiftOrderSender sender;

  /// 是否已拆：**仅主人视角**有值；访客为 null（访客看到的都是礼物本身）。
  final bool? opened;
  final bool hasMessage;

  /// 主人视角且未拆。
  bool get unopened => opened == false;

  String nameFor(Locale locale) {
    final preferred = locale.languageCode == 'id' ? giftNameId : giftNameEn;
    if (preferred.isNotEmpty) return preferred;
    return locale.languageCode == 'id' ? giftNameEn : giftNameId;
  }

  GiftOrderCard copyWith({bool? opened}) => GiftOrderCard(
        orderToken: orderToken,
        giftNameEn: giftNameEn,
        giftNameId: giftNameId,
        giftIconUrl: giftIconUrl,
        quantity: quantity,
        receivedAt: receivedAt,
        sender: sender,
        opened: opened ?? this.opened,
        hasMessage: hasMessage,
      );

  factory GiftOrderCard.fromJson(Map<String, dynamic> j) => GiftOrderCard(
        orderToken: j['orderToken'] as String? ?? '',
        giftNameEn: j['giftNameEn'] as String? ?? '',
        giftNameId: j['giftNameId'] as String? ?? '',
        giftIconUrl: j['giftIconUrl'] as String?,
        quantity: j['quantity'] is num ? (j['quantity'] as num).toInt() : 1,
        receivedAt: DateTime.tryParse(j['receivedAt'] as String? ?? ''),
        sender: j['sender'] is Map
            ? GiftOrderSender.fromJson((j['sender'] as Map).cast<String, dynamic>())
            : const GiftOrderSender(deleted: true),
        opened: j['opened'] as bool?,
        hasMessage: j['hasMessage'] == true,
      );
}

/// 筛选胶囊（后台分类，按后端顺序）。
class GiftCategoryChip {
  const GiftCategoryChip({required this.categoryId, required this.nameEn, required this.nameId});

  final int categoryId;
  final String nameEn;
  final String nameId;

  String nameFor(Locale locale) {
    final preferred = locale.languageCode == 'id' ? nameId : nameEn;
    if (preferred.isNotEmpty) return preferred;
    return locale.languageCode == 'id' ? nameEn : nameId;
  }

  factory GiftCategoryChip.fromJson(Map<String, dynamic> j) => GiftCategoryChip(
        categoryId: _int(j['categoryId']),
        nameEn: j['nameEn'] as String? ?? '',
        nameId: j['nameId'] as String? ?? '',
      );
}

List<GiftOrderCard> _cards(Object? raw) => raw is List
    ? [
        for (final c in raw)
          if (c is Map) GiftOrderCard.fromJson(c.cast<String, dynamic>()),
      ]
    : const [];

/// 列表一页；首页另带 `categories` / `pet` / `viewerIsOwner`（宠物复用 [GiftShowcasePet]）。
class GiftOrderPage {
  const GiftOrderPage({
    this.items = const [],
    this.nextCursor,
    this.unopenedCount,
    this.categories,
    this.pet,
    this.viewerIsOwner,
  });

  final List<GiftOrderCard> items;
  final String? nextCursor;
  final int? unopenedCount;
  final List<GiftCategoryChip>? categories;
  final GiftShowcasePet? pet;
  final bool? viewerIsOwner;

  factory GiftOrderPage.fromJson(Map<String, dynamic> j) => GiftOrderPage(
        items: _cards(j['items']),
        nextCursor: j['nextCursor'] as String?,
        unopenedCount: j['unopenedCount'] is num ? (j['unopenedCount'] as num).toInt() : null,
        categories: j['categories'] is List
            ? [
                for (final c in j['categories'] as List)
                  if (c is Map) GiftCategoryChip.fromJson(c.cast<String, dynamic>()),
              ]
            : null,
        pet: j['pet'] is Map ? GiftShowcasePet.fromJson((j['pet'] as Map).cast<String, dynamic>()) : null,
        viewerIsOwner: j['viewerIsOwner'] as bool?,
      );
}

/// Diary 礼物行。
class GiftPreview {
  const GiftPreview({this.giftCount = 0, this.unopenedCount, this.recent = const []});

  final int giftCount;
  final int? unopenedCount;
  final List<GiftOrderCard> recent;

  factory GiftPreview.fromJson(Map<String, dynamic> j) => GiftPreview(
        giftCount: _int(j['giftCount']),
        unopenedCount: j['unopenedCount'] is num ? (j['unopenedCount'] as num).toInt() : null,
        recent: _cards(j['recent']),
      );
}

/// 列表筛选（后端 `filter` 只认 ALL / UNOPENED；分类另传 `categoryId`）。
class GiftOrderFilter {
  const GiftOrderFilter.all() : unopened = false, categoryId = null;
  const GiftOrderFilter.unopened() : unopened = true, categoryId = null;
  const GiftOrderFilter.category(int this.categoryId) : unopened = false;

  final bool unopened;
  final int? categoryId;

  @override
  bool operator ==(Object other) =>
      other is GiftOrderFilter && other.unopened == unopened && other.categoryId == categoryId;

  @override
  int get hashCode => Object.hash(unopened, categoryId);
}
