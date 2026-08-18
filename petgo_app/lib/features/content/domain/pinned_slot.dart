import 'feed_item.dart';

/// 顶置坑位（V1.1.6 Story 4.2 · FR-68）。
///
/// 🛡 [item] 与普通条目**是同一个类型** —— 顶置卡因此用同一个卡片组件渲染，
/// 只多挂一个角标。新写一套卡片才是这条最容易走歪的地方。
class PinnedSlot {
  const PinnedSlot({
    required this.pinConfigId,
    required this.pinType,
    required this.item,
    this.promo,
  });

  /// 坑位配置标识（埋点要带）。
  final int pinConfigId;

  /// 线格式：`CONTENT`（已发布内容）/ `PROMO`（推广卡片，Story 4.3）。
  final String pinType;

  /// 顶置的内容条目；推广卡片时为 null。
  final FeedItem? item;

  /// 推广卡片（V1.1.6 Story 4.3）；顶置已发布内容时为 null。
  final PromoCard? promo;

  /// 埋点用的类型值。
  ///
  /// ⚠️ 与线格式**刻意不同**：埋点口径由 PRD 定死为 `post` / `promo_card`，
  /// 改埋点取值会切断已有序列，所以这里做一次显式映射而不是直接把线格式发上去。
  String get analyticsType => pinType == 'PROMO' ? 'promo_card' : 'post';

  /// 无生效配置 → null（客户端什么都不渲染、**不留占位**）。
  static PinnedSlot? fromJson(Map<String, dynamic> json) {
    final pin = json['pin'];
    if (pin is! Map) return null;
    final map = pin.cast<String, dynamic>();
    final id = map['pinConfigId'];
    if (id is! int) return null;
    final rawItem = map['item'];
    return PinnedSlot(
      pinConfigId: id,
      pinType: (map['pinType'] ?? 'CONTENT') as String,
      item: rawItem is Map ? FeedItem.fromJson(rawItem.cast<String, dynamic>()) : null,
      promo: PromoCard.fromJson(map['promo']),
    );
  }
}


/// 推广卡片（V1.1.6 Story 4.3 · FR-68 对象 b）。**不对应任何真实帖子**。
///
/// 🔴 只有这三个字段 —— **没有作者、没有点赞评论、没有时间**。
/// 渲染时因此不出那三块（没有数据可放），而不是编造出来。
///
/// ⚠️ UI 稿屏 02 画的其实是一张普通内容卡的数据（有作者名、点赞 212、评论 27、时间），
/// **不可照抄** —— 照抄意味着给用户看编造的作者与互动数字。
class PromoCard {
  const PromoCard({required this.imageUrl, required this.title, this.linkUrl});

  final String imageUrl;
  final String title;

  /// 跳转目标：外部链接或 App 内部深链；**空 = 纯展示卡（不可点）**。
  final String? linkUrl;

  /// 跳转目标的类型（埋点要带，也决定怎么跳）。
  PromoJumpTarget get jumpTarget => PromoJumpTarget.of(linkUrl);

  static PromoCard? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final map = raw.cast<String, dynamic>();
    final image = map['imageUrl'];
    final title = map['title'];
    // 图片与标题必填 —— 缺一就不是一张能展示的卡，当作没有顶置。
    if (image is! String || image.isEmpty || title is! String || title.isEmpty) return null;
    final link = map['linkUrl'];
    return PromoCard(
      imageUrl: image,
      title: title,
      linkUrl: (link is String && link.isNotEmpty) ? link : null,
    );
  }
}

/// 推广卡片的跳转目标类型。
///
/// 🔴 [unknown] 一律**什么都不做** —— 运营在后台把链接填错一个字符，
/// 不该让首页崩掉或弹一个用户看不懂的报错。
enum PromoJumpTarget {
  /// 外部链接 → 交给系统浏览器（项目已有同款用法）。
  externalUrl('external_url'),

  /// App 内部深链 → 丢给**既有**的深链映射，不新建体系。
  deeplink('deeplink'),

  /// 空 / 认不出 → 不可点。
  unknown('unknown');

  const PromoJumpTarget(this.analyticsValue);

  /// 埋点取值（PRD 口径）。
  final String analyticsValue;

  static PromoJumpTarget of(String? url) {
    if (url == null || url.isEmpty) return unknown;
    final uri = Uri.tryParse(url);
    if (uri == null) return unknown;
    if (uri.scheme == 'http' || uri.scheme == 'https') return externalUrl;
    if (uri.scheme == 'tailtopia') return deeplink;
    return unknown;
  }
}
