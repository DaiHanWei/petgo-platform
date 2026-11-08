/// 商品卡（v1.4.2 shop Story 8.1 AC6.3，UI A1）——首页全部商品、搜索结果、推荐网格、空购物车共用。
///
/// 白卡片、浅紫图底、图片 [BoxFit.contain] **不裁切**、名称两行、紫色价格
/// （`minPrice` 为 null 显示占位，**不显示 Rp 0**）、右下实心「＋」快速加购（热区 44 → 规格弹层）；
/// `promo=true` 时左上角标 `local_fire_department`「Lagi promo」（红橙渐变，不写金额）。
///
/// 🔴 卡片**定高**（图区 1:1 + 定高文字区），高度由 [ShopProductCard.extentFor] 按 textScaler 算，
/// 网格据此给 `mainAxisExtent` —— 不再用 childAspectRatio 猜高度（那正是旧网格溢出的来源）。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../domain/shop_product.dart';
import 'shop_decor.dart';
import 'shop_pressable.dart';
import 'sku_picker_sheet.dart';

/// 页面级商品曝光去重（v1.4.2 shop Story 8.1 复审 #5）：同一次进入页面，同一 token + zone 只报一次
/// `toko_product_shown`；页面离开（TickerMode 关）时由页面 [clear]，回来重新计 —— 与 banner 曝光同一规则。
class ShopImpressionTracker {
  final Set<String> _shown = {};

  /// 首次返回 true（应上报），之后同 token + zone 返回 false。
  bool markShown(String token, String zone) => _shown.add('$zone|$token');

  void clear() => _shown.clear();
}

/// 把 [ShopImpressionTracker] 交给页内所有商品卡。没有作用域（单测直挂卡片）时每张卡各报一次。
class ShopImpressionScope extends InheritedWidget {
  const ShopImpressionScope({super.key, required this.tracker, required super.child});

  final ShopImpressionTracker tracker;

  @override
  bool updateShouldNotify(ShopImpressionScope oldWidget) => !identical(tracker, oldWidget.tracker);
}

/// 卡片圆角（AppRounded.lg）。
const double kShopCardRadius = 16;

/// 「＋」热区。
const double kShopCardAddHit = 44;

class ShopProductCard extends ConsumerStatefulWidget {
  const ShopProductCard({
    super.key,
    required this.token,
    required this.name,
    required this.zone,
    required this.entrySource,
    this.imageUrl,
    this.minPrice,
    this.promo = false,
    this.dense = false,
    this.onTapped,
  });

  factory ShopProductCard.fromSummary(ShopProductSummary p,
          {Key? key, required String zone, required String entrySource, bool dense = false}) =>
      ShopProductCard(
        key: key,
        token: p.token,
        name: p.name,
        imageUrl: p.mainImageUrl,
        minPrice: p.minPrice,
        promo: p.promo,
        zone: zone,
        entrySource: entrySource,
        dense: dense,
      );

  final String token;
  final String name;
  final String? imageUrl;
  final int? minPrice;
  final bool promo;

  /// `toko_product_shown.zone`（`all_featured` / `profile_reco` / …）。
  final String zone;

  /// 行级归因：详情 `?from=` 与加购 `entrySource`。
  final String entrySource;

  /// 三列推荐网格用的紧凑字号（说明 13）；默认正文 15。
  final bool dense;

  /// 点击卡片时额外上报（推荐网格的 `toko_profile_reco_tapped`）；跳转本组件自己做。
  final VoidCallback? onTapped;

  static double _nameSize(bool dense) => dense ? 13 : 15;
  static const double _lineHeight = 1.3;
  static const double _pad = AppSpacing.sm;

  /// 价格行字号：常规正文 15 / 紧凑说明 13。
  static double _priceSize(bool dense) => dense ? 13 : 15;

  /// 卡片总高 = 图区（= 宽，1:1）+ 文字区，文字区随 textScaler 伸缩。
  /// - 常规：两行名称 + 价格 / ＋（热区 44）一行；
  /// - 紧凑（三列推荐）：两行名称 + 整行价格，「＋」浮在图区右下角（三列宽度放不下价格与 44 热区并排）。
  static double extentFor(BuildContext context, double width, {bool dense = false}) {
    final ts = MediaQuery.textScalerOf(context);
    final nameH = ts.scale(_nameSize(dense)) * _lineHeight * 2;
    final rowH = dense ? ts.scale(_priceSize(dense)) * _lineHeight : kShopCardAddHit;
    return width + _pad + nameH + AppSpacing.xxs + rowH + (dense ? _pad : AppSpacing.xxs);
  }

  @override
  ConsumerState<ShopProductCard> createState() => _ShopProductCardState();
}

class _ShopProductCardState extends ConsumerState<ShopProductCard> {
  bool _impressionChecked = false;

  /// 曝光在首次 didChangeDependencies 报（要读页面级去重作用域）。
  /// 网格是惰性 sliver：卡片构建 ≈ 进入可视区附近；滚走再滚回会重建卡片，
  /// 所以去重放在页面级 [ShopImpressionScope]（8.1 复审 #5），不靠卡片自己的生命周期。
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_impressionChecked) return;
    _impressionChecked = true;
    final tracker = context.getInheritedWidgetOfExactType<ShopImpressionScope>()?.tracker;
    if (tracker != null && !tracker.markShown(widget.token, widget.zone)) return;
    Analytics.capture('toko_product_shown', {'product_token': widget.token, 'zone': widget.zone});
  }

  void _open() {
    widget.onTapped?.call();
    context.push('/shop/products/${Uri.encodeComponent(widget.token)}?from=${widget.entrySource}');
  }

  void _add() => showSkuPickerSheet(context, ref,
      productToken: widget.token, mode: SkuPickerMode.addToCart, entrySource: widget.entrySource);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final w = widget;
    final ts = MediaQuery.textScalerOf(context);
    final nameSize = ShopProductCard._nameSize(w.dense);
    final nameH = ts.scale(nameSize) * ShopProductCard._lineHeight * 2;
    final hasPrice = w.minPrice != null;
    final price = Text(
      // 🔴 无 SKU 时显占位而非 `Rp 0` —— 那是错的价格，不是缺失的价格。
      hasPrice ? formatIdr(w.minPrice!) : l10n.tokoPriceUnavailable,
      key: ValueKey('shopProductCardPrice_${w.token}'),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: AppTypography.body.copyWith(
        fontSize: ShopProductCard._priceSize(w.dense),
        height: ShopProductCard._lineHeight,
        fontWeight: FontWeight.w700,
        color: hasPrice ? AppColors.mint : AppColors.muted,
        fontFeatures: const [FontFeature.tabularFigures()],
      ),
    );
    final add = Semantics(
      button: true,
      label: l10n.tokoAddToCartConfirm,
      child: ShopPressable(
        key: ValueKey('shopProductCardAdd_${w.token}'),
        onTap: _add,
        minSize: kShopCardAddHit,
        child: Container(
          width: 28,
          height: 28,
          alignment: Alignment.center,
          decoration: const BoxDecoration(color: AppColors.mint, shape: BoxShape.circle),
          child: const Icon(Icons.add, size: 18, color: AppColors.onAccent),
        ),
      ),
    );
    return Material(
      color: AppColors.card,
      borderRadius: BorderRadius.circular(kShopCardRadius),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: ValueKey('shopProductCard_${w.token}'),
        onTap: _open,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AspectRatio(
              aspectRatio: 1,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  ColoredBox(color: AppColors.mintTint, child: _image(w.imageUrl)),
                  if (w.promo)
                    const Positioned(left: AppSpacing.sm, top: AppSpacing.sm, child: _PromoBadge()),
                  if (w.dense) Positioned(right: 0, bottom: 0, child: add),
                ],
              ),
            ),
            Padding(
              padding: EdgeInsets.fromLTRB(_hPad, ShopProductCard._pad,
                  w.dense ? _hPad : AppSpacing.xxs, w.dense ? ShopProductCard._pad : AppSpacing.xxs),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    height: nameH,
                    child: Padding(
                      padding: EdgeInsets.only(right: w.dense ? 0 : AppSpacing.sm - AppSpacing.xxs),
                      child: Text(w.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: AppTypography.body.copyWith(
                              fontSize: nameSize,
                              height: ShopProductCard._lineHeight,
                              color: AppColors.ink)),
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xxs),
                  if (w.dense)
                    SizedBox(height: ts.scale(ShopProductCard._priceSize(true)) * ShopProductCard._lineHeight, child: price)
                  else
                    SizedBox(
                      height: kShopCardAddHit,
                      child: Row(children: [Expanded(child: price), add]),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  static const double _hPad = AppSpacing.sm + AppSpacing.xxs;

  Widget _image(String? url) => LayoutBuilder(builder: (context, c) {
        final side = c.maxWidth.isFinite ? c.maxWidth : 200.0;
        // 无图 / 加载失败：斜纹占位（与全站商品图同一占位）。
        final placeholder = ShopImage(url: null, size: side, fillWidth: true, radius: 0);
        if (url == null || url.isEmpty) return placeholder;
        final dpr = MediaQuery.devicePixelRatioOf(context);
        return Image.network(
          AppImage.ossResized(url, width: (side * dpr).round()),
          // 🔴 contain：不裁切商品主体，宁可留浅紫底。
          fit: BoxFit.contain,
          errorBuilder: (_, _, _) => placeholder,
        );
      });
}

/// 「Lagi promo」角标：红橙渐变，不写金额（PRD §6 A1 11-05）。
class _PromoBadge extends StatelessWidget {
  const _PromoBadge();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('shopProductPromoBadge'),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        gradient: const LinearGradient(colors: [AppColors.popRed, AppColors.gold]),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.local_fire_department, size: 12, color: AppColors.onAccent),
          const SizedBox(width: 2),
          Text(l10n.tokoPromoBadge,
              style: AppTypography.micro.copyWith(color: AppColors.onAccent, fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }
}

/// 两列商品网格（sliver）。首页全部商品与搜索结果共用。
class ShopProductSliverGrid extends StatelessWidget {
  const ShopProductSliverGrid({
    super.key,
    required this.items,
    required this.zone,
    required this.entrySource,
  });

  final List<ShopProductSummary> items;
  final String zone;
  final String entrySource;

  @override
  Widget build(BuildContext context) {
    const gap = AppSpacing.md;
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
      // 🔴 列宽取 sliver 实际横向约束，不取 MediaQuery 宽：两者在平板分屏 / 测试面上并不相等，
      //    按错的宽度算定高会让 1:1 图区把文字区挤出卡片。
      sliver: SliverLayoutBuilder(builder: (context, constraints) {
        final cellW = (constraints.crossAxisExtent - gap) / 2;
        return SliverGrid(
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 2,
            mainAxisSpacing: gap,
            crossAxisSpacing: gap,
            mainAxisExtent: ShopProductCard.extentFor(context, cellW),
          ),
          delegate: SliverChildBuilderDelegate(
            (c, i) => ShopProductCard.fromSummary(items[i], zone: zone, entrySource: entrySource),
            childCount: items.length,
          ),
        );
      }),
    );
  }
}
