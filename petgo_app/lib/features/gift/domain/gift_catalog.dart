import 'dart:ui';

/// 送礼目录（V1.4.0 gift Story 1.3 · AC8；后端 `GiftCatalogResponse`，Story 1.1 · AD-10）。
///
/// 只用 `fromJson`、缺字段不崩；金额一律 `int`（IDR，1 koin = Rp1）。
/// 排序已由后端做完（价位升序；档内「适用该物种 → 通用 → 其他」），App 只按档价位再保一次升序。
class GiftCatalog {
  const GiftCatalog({
    required this.recipient,
    required this.qrisMinAmount,
    this.tiers = const [],
    this.exclusiveGroups = const [],
    this.lockBlock,
  });

  /// 收礼宠物（`petId` 交给发起接口）。
  final GiftRecipient recipient;

  /// 送礼 QRIS 单笔最低金额（总价低于它 → QRIS 置灰）。
  final int qrisMinAmount;

  /// 普通礼物，按价位升序分组。
  final List<GiftTier> tiers;

  /// 已解锁的专属礼物，按解锁等级升序分组（排在所有价位档之后，S2）。
  final List<GiftExclusiveGroup> exclusiveGroups;

  /// 下一批未解锁专属礼物的门槛；全解锁 / 无专属礼物 → null（B3 不显示锁区块）。
  final GiftLockBlock? lockBlock;

  /// 网格顺序的全部礼物：价位档（升序）→ 专属组（等级升序）。第一项即默认选中（AC2.4）。
  List<GiftItem> get orderedItems => [
        for (final t in sortedTiers) ...t.gifts,
        for (final g in sortedExclusiveGroups) ...g.gifts,
      ];

  List<GiftTier> get sortedTiers => [...tiers]..sort((a, b) => a.price.compareTo(b.price));

  List<GiftExclusiveGroup> get sortedExclusiveGroups =>
      [...exclusiveGroups]..sort((a, b) => a.minLevel.compareTo(b.minLevel));

  /// 按 token 找礼物（目录刷新后已下线 → null）。
  GiftItem? itemByToken(String? token) {
    if (token == null) return null;
    for (final item in orderedItems) {
      if (item.giftToken == token) return item;
    }
    return null;
  }

  factory GiftCatalog.fromJson(Map<String, dynamic> json) {
    final rawTiers = json['tiers'];
    final rawGroups = json['exclusiveGroups'];
    final rawLock = json['lockBlock'];
    final rawRecipient = json['recipient'];
    return GiftCatalog(
      recipient: GiftRecipient.fromJson(
          rawRecipient is Map ? rawRecipient.cast<String, dynamic>() : const {}),
      qrisMinAmount: _int(json['qrisMinAmount']),
      tiers: rawTiers is List
          ? [
              for (final t in rawTiers)
                if (t is Map) GiftTier.fromJson(t.cast<String, dynamic>()),
            ]
          : const [],
      exclusiveGroups: rawGroups is List
          ? [
              for (final g in rawGroups)
                if (g is Map) GiftExclusiveGroup.fromJson(g.cast<String, dynamic>()),
            ]
          : const [],
      lockBlock: rawLock is Map ? GiftLockBlock.fromJson(rawLock.cast<String, dynamic>()) : null,
    );
  }
}

/// 收礼宠物（`petType` = CAT / DOG / OTHER；头像与品种可空）。
class GiftRecipient {
  const GiftRecipient({
    required this.petId,
    required this.name,
    this.avatarUrl,
    this.petType,
    this.breed,
  });

  final int petId;
  final String name;
  final String? avatarUrl;
  final String? petType;
  final String? breed;

  factory GiftRecipient.fromJson(Map<String, dynamic> json) => GiftRecipient(
        petId: _int(json['petId']),
        name: json['name'] as String? ?? '',
        avatarUrl: json['avatarUrl'] as String?,
        petType: json['petType'] as String?,
        breed: json['breed'] as String?,
      );
}

/// 一个价位档。
class GiftTier {
  const GiftTier({required this.price, this.gifts = const []});

  final int price;
  final List<GiftItem> gifts;

  factory GiftTier.fromJson(Map<String, dynamic> json) => GiftTier(
        price: _int(json['price']),
        gifts: _items(json['gifts']),
      );
}

/// 一批已解锁的专属礼物（同一解锁等级）。
class GiftExclusiveGroup {
  const GiftExclusiveGroup({required this.minLevel, this.gifts = const []});

  final int minLevel;
  final List<GiftItem> gifts;

  factory GiftExclusiveGroup.fromJson(Map<String, dynamic> json) => GiftExclusiveGroup(
        minLevel: _int(json['minLevel']),
        gifts: _items(json['gifts']),
      );
}

/// 锁区块：送礼人还差的最低解锁等级。
class GiftLockBlock {
  const GiftLockBlock({required this.requiredLevel});

  final int requiredLevel;

  factory GiftLockBlock.fromJson(Map<String, dynamic> json) =>
      GiftLockBlock(requiredLevel: _int(json['requiredLevel']));
}

/// 单个礼物。名称按 App 语言取 `nameId` / `nameEn` 快照（NFR5：不渲染后端显示串）。
class GiftItem {
  const GiftItem({
    required this.giftToken,
    required this.nameEn,
    required this.nameId,
    required this.price,
    this.iconUrl,
    this.kind = 'NORMAL',
    this.minLevel,
  });

  /// 礼物 `public_token`（发起送礼 / 埋点 `gift_id` 用，不可枚举）。
  final String giftToken;
  final String nameEn;
  final String nameId;
  final String? iconUrl;
  final int price;

  /// NORMAL / EXCLUSIVE。
  final String kind;

  /// 专属礼物解锁等级；普通礼物为 null。
  final int? minLevel;

  bool get isExclusive => kind == 'EXCLUSIVE';

  /// `id` → `nameId`，其余语言 → `nameEn`（App 只有 EN / ID 两份文案）。一侧为空时退另一侧。
  String nameFor(Locale locale) {
    final preferred = locale.languageCode == 'id' ? nameId : nameEn;
    if (preferred.isNotEmpty) return preferred;
    return locale.languageCode == 'id' ? nameEn : nameId;
  }

  factory GiftItem.fromJson(Map<String, dynamic> json) => GiftItem(
        giftToken: json['giftToken'] as String? ?? '',
        nameEn: json['nameEn'] as String? ?? '',
        nameId: json['nameId'] as String? ?? '',
        iconUrl: json['iconUrl'] as String?,
        price: _int(json['price']),
        kind: json['kind'] as String? ?? 'NORMAL',
        minLevel: json['minLevel'] is num ? (json['minLevel'] as num).toInt() : null,
      );
}

List<GiftItem> _items(Object? raw) => raw is List
    ? [
        for (final g in raw)
          if (g is Map) GiftItem.fromJson(g.cast<String, dynamic>()),
      ]
    : const [];

int _int(Object? v) => v is num ? v.toInt() : int.tryParse('$v') ?? 0;
