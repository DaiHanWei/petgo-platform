/// 🔴🔴 问诊与商品的边界：商品链接策略（Story 9.1 · FR-110 · **安全攸关·约束性需求**）。
///
/// decision-log N-3：《战略决策快照》「靠什么赢」第一条是 AI 问诊与本地信任。
/// **此约束优先于任何转化率优化** —— 问诊一旦被感知为销售前端，护城河即失效。
///
/// 兽医**可能手工在消息里粘贴商品链接**（我们拦不住他打字）。能拦的是**渲染**：
/// 🔴 这类链接不得被渲染为可点击卡片 —— 一条纯文本 URL 和一张带图带价的商品卡，
/// 在用户感知里是两件完全不同的事。
///
/// ⚠️ **只降级、不删改**：删掉兽医写的字会让他以为消息发失败了，然后换个写法再发一次。
///
/// **为什么放在 `shared/` 而不是 `features/shop/`**：这是问诊侧要用的东西。
/// 放进 `features/shop/` 的话，会话页一用就得 import shop —— 那正是 FR-110 要禁的形状。
/// 后端同名守卫见 `com.tailtopia.shared.boundary.ShopLinkPolicy`，两端各扫各的。
library;

/// 商品链接特征：本站商品详情路径 / 深链。
///
/// 覆盖 `/shop/products/<token>`、`/api/v1/shop/products/<token>`
/// 与 App 深链 `tailtopia://shop/products/<token>`。
final RegExp _shopLink = RegExp(
  r'(?:https?://[^\s]*|tailtopia://)?/?(?:api/v\d+/)?shop/products/[A-Za-z0-9_-]+',
  caseSensitive: false,
);

/// 降级后的占位（印尼语「商品链接」）。用户读得懂兽医想指什么，只是点不进去。
const String kNeutralizedShopLink = '[tautan produk]';

class ShopLinkPolicy {
  const ShopLinkPolicy._();

  /// 文本里是否含商品链接。
  static bool containsShopLink(String? text) =>
      text != null && _shopLink.hasMatch(text);

  /// 🔴 把商品链接降级为不可点击的纯文本。
  ///
  /// 在链接前后塞零宽字符、指望渲染层「看得懂」的做法太脆 —— 换个 Text 组件就漏。
  /// 这里直接把整段链接换掉，任何自动 linkify 都不再认得它。
  static String neutralize(String? text) {
    if (text == null) return '';
    return text.replaceAll(_shopLink, kNeutralizedShopLink);
  }
}
