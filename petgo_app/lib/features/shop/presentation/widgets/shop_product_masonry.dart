/// Toko 商品**两列瀑布流**（2026-08-27 产品改版）。
///
/// 🔴 从 [toko_page_v2.dart] 抽出来是因为**搜索页要用同一套结果呈现**
/// （2026-09-02 产品定的形态：Toko 吸顶行放大镜 → 独立搜索页）。
/// 两边各画一份网格的话，卡片版式、分列算法、埋点口径会立刻开始分叉，
/// 而「搜索结果长得和列表不一样」是用户第一眼就会注意到的那种不一致。
library;

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/shop_tokens.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../domain/shop_product.dart';
import 'shop_decor.dart';

/// 两列瀑布流。
///
/// 🔴 为什么是瀑布流而不是等高网格：商品图比例并不统一（1:1 的素材规格没被严格遵守）。
/// 定高框下只有两种坏结果 —— `cover` 裁掉竖图的上下两端，`contain` 留出大片左右空白，
/// 两者都让「用户看到的」与「运营上架的」不是同一张图。宽度撑满 + 高度按比例，
/// 才能既不裁切也不留白。**代价是卡片参差、价格行不再横向对齐**，产品已确认接受。
///
/// **按高度贪心分列**：每张卡放进当前较矮的那列，两列底部因此大致齐平。
///
/// ⚠️ 高度用**相对值**估算（以列宽为 1），不换算像素 —— 分列只需要比较谁更矮，
/// 绝对值没有意义，而列宽要等 layout 才知道。
/// 🔴 尺寸未知的商品按 1:1 计入：估错的代价只是两列略不齐，不影响正确性。
///
/// 🔴 一次性构建全部卡片，**没有 Sliver 的视口懒构建** —— 全部商品的图片同时开始加载。
/// 当前 92 个商品尚可承受，商品量再上去必须改为分页加载。
class ShopProductMasonry extends StatelessWidget {
  const ShopProductMasonry({super.key, required this.items, required this.entrySource});

  final List<ShopProductSummary> items;

  /// 行级归因：用户从哪个入口进的这件商品。列表用 `TOKO_ALL_FEATURED` /
  /// `TOKO_CATEGORY`，搜索页用 `TOKO_SEARCH` —— 服务端加购时会把它记在购物车行上。
  final String entrySource;

  @override
  Widget build(BuildContext context) {
    final left = <Widget>[];
    final right = <Widget>[];
    // 以列宽为 1 的相对高度累计值。
    var leftH = 0.0;
    var rightH = 0.0;
    for (final p in items) {
      final card = _GridCard(product: p, entrySource: entrySource);
      // 图片相对高度 = 1 / (w/h)；未知比例按 1:1。再加上名称+价格那块的固定占位。
      final cardH = 1 / (p.mainImageAspect ?? 1.0) + kMasonryTextBlockRatio;
      if (leftH <= rightH) {
        left.add(card);
        leftH += cardH;
      } else {
        right.add(card);
        rightH += cardH;
      }
    }
    return Row(
      // 🔴 必须是 start：默认的 stretch 会把两列拉成等高，瀑布流当场退化回网格。
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(child: _column(left)),
        const SizedBox(width: kShopGutter),
        Expanded(child: _column(right)),
      ],
    );
  }

  Widget _column(List<Widget> cards) => Column(
        children: [
          for (var i = 0; i < cards.length; i++) ...[
            if (i > 0) const SizedBox(height: kShopGutter),
            cards[i],
          ],
        ],
      );
}

/// 全部商品网格卡。
class _GridCard extends StatefulWidget {
  const _GridCard({required this.product, required this.entrySource});

  final ShopProductSummary product;
  final String entrySource;

  @override
  State<_GridCard> createState() => _GridCardState();
}

class _GridCardState extends State<_GridCard> {
  @override
  void initState() {
    super.initState();
    // ⚠️ <b>这不是真正的「曝光」</b>（2026-08-27 审查记录，未修）：瀑布流用
    //    [SliverToBoxAdapter] 一次性构建全部卡片，因此首屏会同时报出 92 条
    //    `toko_product_shown`，而用户实际只看得到 4 个。要变成真曝光需要
    //    `VisibilityDetector`（当前不是依赖）或把网格改成惰性 sliver ——
    //    后者本来就是这里 TODO 的分页改造。在那之前，**不要拿这个事件算曝光转化率**，
    //    它现在的含义是「进过列表的商品数」。
    Analytics.capture('toko_product_shown', {
      'product_token': widget.product.token,
      'zone': 'all_featured',
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final p = widget.product;
    return InkWell(
      onTap: () => context.push('/shop/products/${p.token}?from=${widget.entrySource}'),
      child: ColoredBox(
        color: ShopColors.surface,
        child: Padding(
          padding: const EdgeInsets.all(9),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 🔴 2026-08-27 瀑布流：宽度撑满、高度随图片比例走，
              //    既不裁切（cover 的毛病）也不留白（contain 的毛病）。
              _AutoHeightImage(url: p.mainImageUrl, aspect: p.mainImageAspect),
              const SizedBox(height: 6),
              // 🔴 **不能再用 Expanded**：瀑布流下卡片高度由内容决定，Column 处于
              //    无界高度约束中，Expanded 会直接抛「RenderFlex has unbounded height」。
              //    原来用它是为了让价格行在等高网格里天然对齐 —— 瀑布流本就不要求对齐，
              //    这个诉求随定高一起消失了。溢出也不再可能：高度跟着内容长。
              Align(
                alignment: Alignment.topLeft,
                child: Text(p.name,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: ShopText.productNameCard),
              ),
              const SizedBox(height: 4),
              Text(
                // 🔴 无 SKU 时显占位而非 `Rp 0` —— 那是错的价格，不是缺失的价格。
                p.minPrice == null ? l10n.tokoPriceUnavailable : formatIdr(p.minPrice!),
                style: ShopText.priceGrid.copyWith(
                    color: p.minPrice == null ? ShopColors.text4 : ShopColors.accent),
              ),
              // ★ 评分 · 已售数：接口无此字段 → 整行不显示。
            ],
          ),
        ),
      ),
    );
  }
}

/// 高度随图片比例走的商品图（瀑布流专用）。
///
/// 🔴 与 [ShopImage] 的分工：那个**必须**给定高（`size`），服务于等高网格与各处方框；
/// 这个的高度由图片比例决定 —— 这正是瀑布流成立的前提。
///
/// ## 两条路径，取决于 [aspect] 有没有
///
/// - **已知比例**（后端下发了宽高）：[AspectRatio] 预置高度 ⇒ 图片解码前后**布局不变**，
///   没有任何跳动。这是 2026-08-27 补上尺寸列后的默认路径。
/// - **未知比例**（存量商品，尺寸恒为 null）：退回「宽度撑满、高度等解码」——
///   加载中用 1:1 方块占位而不是零高度，把跳动收敛成「1:1 → 实际比例」这一次；
///   零高度会让整列卡片在图片陆续到达时反复向下弹跳。
///
/// ⚠️ 已知比例时用 [BoxFit.cover]：容器比例就是图片比例，正常情况一个像素都不会裁掉；
/// 只有比例被 [kShopImageRatioMin]/[kShopImageRatioMax] 收敛过的极端长图才会裁 ——
/// 那正是收敛想要的效果（宁可裁，不让一张长图把后面的商品全挤出首屏）。
class _AutoHeightImage extends StatelessWidget {
  const _AutoHeightImage({required this.url, this.aspect});

  final String? url;

  /// 图片宽高比（w / h），已在 domain 层收敛过。null = 未知。
  final double? aspect;

  @override
  Widget build(BuildContext context) {
    // 占位要撑满列宽再取高，故用 LayoutBuilder 拿实际宽度。
    final placeholder = LayoutBuilder(
      builder: (ctx, c) => ShopImage(
        url: null,
        size: c.maxWidth.isFinite ? c.maxWidth : kGridImageHeight,
        fillWidth: true,
        radius: ShopShape.radiusChip,
      ),
    );
    final u = url;
    final a = aspect;
    if (u == null || u.isEmpty) {
      // 无图：比例已知时仍按该比例占位，免得后续补图时整列重排。
      return a == null ? placeholder : AspectRatio(aspectRatio: a, child: placeholder);
    }
    final dpr = MediaQuery.devicePixelRatioOf(context);
    // 列表图走 OSS 缩略图省流量；非 OSS 域原样返回（见 AppImage.ossResized）。
    final src = AppImage.ossResized(u, width: (kMasonryThumbWidth * dpr).round());
    final radius = BorderRadius.circular(ShopShape.radiusChip);

    if (a != null) {
      return ClipRRect(
        borderRadius: radius,
        child: AspectRatio(
          aspectRatio: a,
          child: Image.network(
            src,
            fit: BoxFit.cover,
            // 高度已由 AspectRatio 定死，占位不会改变布局 ⇒ 无跳动。
            frameBuilder: (ctx, child, frame, wasSync) =>
                frame == null && !wasSync ? placeholder : child,
            errorBuilder: (_, _, _) => placeholder,
          ),
        ),
      );
    }

    return ClipRRect(
      borderRadius: radius,
      child: Image.network(
        src,
        width: double.infinity,
        fit: BoxFit.fitWidth,
        // frame == null ⇒ 首帧尚未解码，先占方块。wasSync 为真表示同步命中缓存，直接给图。
        frameBuilder: (ctx, child, frame, wasSync) =>
            frame == null && !wasSync ? placeholder : child,
        errorBuilder: (_, _, _) => placeholder,
      ),
    );
  }
}

/// 卡片里名称+价格那块相对于列宽的高度占比，仅用于分列时估算总高。
/// ⚠️ 不需要精确：估错只会让两列略不齐，不影响任何正确性。
const double kMasonryTextBlockRatio = 0.35;

/// 瀑布流列宽下的缩略图取图宽度（逻辑像素）。两列 + 3px 灰缝，单列约占屏宽一半。
const double kMasonryThumbWidth = 200;

/// 图片解码前、且拿不到列宽时的兜底边长（设计稿网格图高 104）。
/// ⚠️ 正常路径永远走 `c.maxWidth` —— 这个值只在约束无界时用得上。
const double kGridImageHeight = 104;
