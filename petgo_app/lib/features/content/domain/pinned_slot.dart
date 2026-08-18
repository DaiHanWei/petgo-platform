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
  });

  /// 坑位配置标识（埋点要带）。
  final int pinConfigId;

  /// 线格式：`CONTENT`（已发布内容）/ `PROMO`（推广卡片，Story 4.3）。
  final String pinType;

  /// 顶置的内容条目；推广卡片时为 null。
  final FeedItem? item;

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
    );
  }
}
