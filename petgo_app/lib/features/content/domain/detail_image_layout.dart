/// 详情页图片区的**高度护栏口径**（V1.3.0 批次 A · Story 2.2 · AD-A11 / AD-A12）。
///
/// ## 🔴 三段口径本身不在这里，在 `feed_image_layout.dart`
/// 「取实际比例 → clamp 到 0.75~1.34 闭区间 → 高度护栏」这三步，详情页与 Feed
/// **调用同一个出口函数** [resolveFeedImageAspect]。本文件**只提供护栏的被减项**。
///
/// 为什么必须共用那一个函数：用户从首页点进详情，看的是**同一张图**。
/// 两处各写一套计算，将来改了一边，跳变就会重新出现 —— 而这正是本 story 要消灭的问题。
///
/// ## 🔴 被减项必须按详情页自己的形态取，不能照抄 Feed
/// Feed 的被减项是「条目其余部分 142 + 露出余量 40」，那是**列表**的口径：
/// 它要给「下一条内容」留出露出余量，还要扣掉作者行 / 正文 2 行 / 操作行 / 时间行。
///
/// 详情页三点都不同：
/// 1. **没有「下一条露出」的概念** —— 详情页只有这一条内容，露出余量这一项整个不存在；
/// 2. **有一条固定底栏**（评论输入区），它常驻在滚动视口之外，要扣；
/// 3. **正文不截断**（Feed 截 2 行），但正文在图片**下方**，用户可以往下滚 ——
///    所以它不参与「首屏要装得下」的减法。
///
/// 照抄 Feed 的数字会算错，而且是**往小了算**（多扣了不存在的露出余量），
/// 结果是详情页的竖图被裁得比 Feed 还狠 —— 与本 story「少裁」的初衷正好相反。
library;

import 'feed_image_layout.dart';

/// 详情页里**图片之外**、且落在首屏可视区内的各块高度。
///
/// ⚠️ 这些数字的判据是可观测行为：**小屏机（360×640 量级）上，极端长图不超出上限，
/// 且图片下方至少能看到正文的第一行**。真机复核时要调的就是这里。
class DetailImageMetrics {
  const DetailImageMetrics._();

  /// 作者行：36 头像 + 上下 padding，与详情页 `_authorRow` 实际用的 36 尺寸对齐。
  static const double authorRow = 52;

  /// 作者行与图片之间的间距（`AppSpacing.md`）。
  static const double gapAboveImage = 12;

  /// 图片下方到可视区底之间，**首屏至少要露出来**的那一点内容。
  ///
  /// 口径 = 正文第一行 22 + 图文间距 12。
  /// 它的作用与 Feed 的「露出余量」神似但理由不同：Feed 是为了让用户知道「下面还有下一条」，
  /// 详情页是为了让用户知道「这张图下面还有正文」—— 一屏全是图会让人以为内容就到此为止。
  static const double bodyPeek = 34;

  /// 常驻底栏（评论输入区）的高度。
  ///
  /// ⚠️ **这一项 Feed 完全没有**，是详情页独有的被减项。它不在滚动视口里，
  /// 所以 `LayoutBuilder` 量到的 `constraints.maxHeight` 已经把它扣掉了 ——
  /// 这里留成常量**只为记录口径**，[maxImageHeight] 不再重复扣一次。
  /// 重复扣就是 Feed 那次实机复核抓到的同类错误（条目间隔被两边各记一遍）。
  static const double fixedBottomBar = 56;

  /// 「图片之外」合计（**不含** [fixedBottomBar]，理由见该字段注释）。
  static const double chrome = authorRow + gapAboveImage + bodyPeek;

  /// 给定**滚动视口的实际高度**，得出详情页图片区的高度上限。
  ///
  /// [viewportHeight] 必须由详情页的滚动容器用 `LayoutBuilder` 量得 ——
  /// 那个数已经扣掉了 AppBar 与固定底栏，所以这里只减 [chrome]。
  ///
  /// 极端小视口（横屏 / 分屏）下留 80 的下限，让图片仍然可见（与 Feed 同一兜底策略）。
  static double maxImageHeight(double viewportHeight) {
    final available = viewportHeight - chrome;
    return available > 80 ? available : 80;
  }
}

/// 详情页图片区的最终宽高比 —— 薄薄一层，把详情页的护栏口径喂给**共用的**三段出口。
///
/// 🛡 本函数**不做任何比例计算**，计算全在 [resolveFeedImageAspect] 里。
/// 它存在的唯一意义是「别让调用方自己去拼护栏参数」，从而保证详情页与 Feed
/// 走的是同一条路径。**不要在这里加 clamp、加兜底、加任何 if**。
double resolveDetailImageAspect({
  required ImageSize? size,
  required double width,
  required double viewportHeight,
}) =>
    resolveFeedImageAspect(
      size: size,
      width: width,
      maxImageHeight: DetailImageMetrics.maxImageHeight(viewportHeight),
    );
