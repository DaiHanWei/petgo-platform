/// 礼物展示的线格式（V1.4.0 gift Story 2.2 · AC3 / AC4；接口由 Story 2.1 提供）。
///
/// 🔴 礼物名只下发 EN / ID 两份快照，App 按语言取（[giftNameFor]），**不渲染后端拼好的显示串**。
/// 数字一律 `num → int` 容错（后端 `long`）；缺键按空 / 0，不抛。
library;

int _int(Object? v) => v is num ? v.toInt() : 0;

String? _str(Object? v) => v is String && v.isNotEmpty ? v : null;

List<T> _list<T>(Object? raw, T Function(Map<String, dynamic>) f) => raw is List
    ? [
        for (final e in raw)
          if (e is Map) f(e.cast<String, dynamic>()),
      ]
    : const [];

/// 按语言取礼物名：`id` → [nameId]，其余 → [nameEn]；所选那份为空时回落另一份。
String giftNameFor(String languageCode, {String? nameEn, String? nameId}) {
  final primary = languageCode == 'id' ? nameId : nameEn;
  final secondary = languageCode == 'id' ? nameEn : nameId;
  return (primary != null && primary.isNotEmpty) ? primary : (secondary ?? '');
}

/// 礼物墙一行（`GiftWallItemView`）：同一礼物改价后按价位分成两行。
class GiftWallItem {
  const GiftWallItem({this.nameEn, this.nameId, this.iconUrl, this.unitPrice = 0, this.count = 0});

  final String? nameEn;
  final String? nameId;
  final String? iconUrl;
  final int unitPrice;
  final int count;

  factory GiftWallItem.fromJson(Map<String, dynamic> j) => GiftWallItem(
        nameEn: _str(j['nameEn']),
        nameId: _str(j['nameId']),
        iconUrl: _str(j['iconUrl']),
        unitPrice: _int(j['unitPrice']),
        count: _int(j['count']),
      );

  String nameFor(String languageCode) => giftNameFor(languageCode, nameEn: nameEn, nameId: nameId);
}

/// 礼物页头部的宠物（`PetGiftShowcaseResponse.PetView`）。
class GiftShowcasePet {
  const GiftShowcasePet({required this.petId, required this.name, this.avatarUrl, this.petType});

  final int petId;
  final String name;
  final String? avatarUrl;
  final String? petType;

  factory GiftShowcasePet.fromJson(Map<String, dynamic> j) => GiftShowcasePet(
        petId: _int(j['petId']),
        name: (j['name'] as String?) ?? '',
        avatarUrl: _str(j['avatarUrl']),
        petType: _str(j['petType']),
      );
}

/// 礼物页汇总 + 礼物墙（`GET /api/v1/pets/{petId}/gifts`）。
class PetGiftShowcase {
  const PetGiftShowcase({
    required this.pet,
    required this.viewerIsOwner,
    this.totalValue = 0,
    this.giftCount = 0,
    this.supporterCount = 0,
    this.wall = const [],
  });

  final GiftShowcasePet pet;

  /// 主人视角：无吸底「Kirim Hadiah」（E2 / E4）。
  final bool viewerIsOwner;
  final int totalValue;
  final int giftCount;

  /// 主人视角已排除其隐藏的送礼人（与粉丝榜行数一致，客户端不再过滤）。
  final int supporterCount;
  final List<GiftWallItem> wall;

  factory PetGiftShowcase.fromJson(Map<String, dynamic> j) => PetGiftShowcase(
        pet: GiftShowcasePet.fromJson(
            j['pet'] is Map ? (j['pet'] as Map).cast<String, dynamic>() : const {}),
        viewerIsOwner: j['viewerIsOwner'] == true,
        totalValue: _int(j['totalValue']),
        giftCount: _int(j['giftCount']),
        supporterCount: _int(j['supporterCount']),
        wall: _list(j['wall'], GiftWallItem.fromJson),
      );
}

/// 粉丝行里送过的某种礼物。
class GiftSupporterKind {
  const GiftSupporterKind({this.iconUrl, this.nameEn, this.nameId, this.count = 0});

  final String? iconUrl;
  final String? nameEn;
  final String? nameId;
  final int count;

  factory GiftSupporterKind.fromJson(Map<String, dynamic> j) => GiftSupporterKind(
        iconUrl: _str(j['iconUrl']),
        nameEn: _str(j['nameEn']),
        nameId: _str(j['nameId']),
        count: _int(j['count']),
      );
}

/// 粉丝榜一行（`GiftSupporterRow`）。不含任何送礼人标识 —— 行不可点（UI 稿 E1）。
class GiftSupporter {
  const GiftSupporter({
    required this.rank,
    this.nickname,
    this.avatarUrl,
    this.deleted = false,
    this.totalAmount = 0,
    this.giftCount = 0,
    this.gifts = const [],
    this.moreKinds = 0,
  });

  final int rank;
  final String? nickname;
  final String? avatarUrl;

  /// 送礼人已注销：灰色默认头像 +「Pengguna terhapus」（AC12）。
  final bool deleted;
  final int totalAmount;
  final int giftCount;
  final List<GiftSupporterKind> gifts;
  final int moreKinds;

  factory GiftSupporter.fromJson(Map<String, dynamic> j) => GiftSupporter(
        rank: _int(j['rank']),
        nickname: _str(j['nickname']),
        avatarUrl: _str(j['avatarUrl']),
        deleted: j['deleted'] == true,
        totalAmount: _int(j['totalAmount']),
        giftCount: _int(j['giftCount']),
        gifts: _list(j['gifts'], GiftSupporterKind.fromJson),
        moreKinds: _int(j['moreKinds']),
      );
}

/// 粉丝榜一页（`GiftSupporterPage`）；[nextCursor] 为 null = 没有下一页。
class GiftSupporterPage {
  const GiftSupporterPage({this.items = const [], this.nextCursor});

  final List<GiftSupporter> items;
  final String? nextCursor;

  bool get hasMore => nextCursor != null;

  factory GiftSupporterPage.fromJson(Map<String, dynamic> j) => GiftSupporterPage(
        items: _list(j['items'], GiftSupporter.fromJson),
        nextCursor: _str(j['nextCursor']),
      );
}

/// Diary 详情礼物区的一只宠物（`ContentDetailResponse.giftPets[]` = `GiftPetSummaryView`）。
class GiftPetSummary {
  const GiftPetSummary({
    required this.petId,
    required this.name,
    this.avatarUrl,
    this.totalValue = 0,
    this.giftCount = 0,
    this.kindCount = 0,
    this.wallPreview = const [],
  });

  final int petId;
  final String name;
  final String? avatarUrl;
  final int totalValue;
  final int giftCount;

  /// 礼物墙总行数；「+N」= `kindCount − wallPreview.length`（0 不显示）。
  final int kindCount;

  /// 礼物墙前 3 行（与礼物页同序）。
  final List<GiftWallItem> wallPreview;

  int get moreKinds {
    final n = kindCount - wallPreview.length;
    return n > 0 ? n : 0;
  }

  factory GiftPetSummary.fromJson(Map<String, dynamic> j) => GiftPetSummary(
        petId: _int(j['petId']),
        name: (j['name'] as String?) ?? '',
        avatarUrl: _str(j['avatarUrl']),
        totalValue: _int(j['totalValue']),
        giftCount: _int(j['giftCount']),
        kindCount: _int(j['kindCount']),
        wallPreview: _list(j['wallPreview'], GiftWallItem.fromJson),
      );

  /// 缺键 / 非数组 → 空表（Moment / Tips / 无关联宠物 / 老后端）。
  static List<GiftPetSummary> listFromJson(Object? raw) =>
      [for (final p in _list(raw, GiftPetSummary.fromJson)) if (p.petId > 0) p];
}
