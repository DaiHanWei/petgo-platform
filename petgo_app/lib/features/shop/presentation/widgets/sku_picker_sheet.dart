/// 规格弹层（UI B9 / B10；v1.4.2 shop Story 8.1 AC7 首建，8.2 详情复用）。
///
/// 首页 / 推荐 / 搜索卡片的「＋」与详情页的两个主按钮都开这一层：商品图、当前价、库存、
/// 「Dipilih: {规格}」、「Pilih Varian」规格 chip、「Jumlah」步进器、确认键。
///
/// - **只有一个 SKU 也弹**（为了选数量）。
/// - 默认规格「有货 > 低价 > 后台顺序」（[defaultShopSku]，与详情页同一函数）；全部售罄时落第一个。
/// - 售罄规格仍可点（与线上一致），选中后确认键置灰。
/// - `sellable=false` → 确认键整宽灰「Belum bisa dijual」不可点（B5 形态）。
/// - 游客点确认 → 软登录浮层，登录后自动完成本次动作（沿用详情页 `_onAddTapped` 规则）。
/// - buyNow 直进 `/shop/checkout?source=buy_now&sku=…&qty=…`，**不写购物车**。
///
/// 🔴 金额只显示服务端 SKU 价，不乘数量、不算优惠（App 不自算）。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/router/route_intent.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../../auth/domain/auth_state.dart';
import '../../../auth/domain/login_guide_controller.dart';
import '../../data/cart_repository.dart';
import '../../data/shop_repository.dart';
import '../../domain/shop_product.dart';
import '../../domain/shop_product_detail.dart';
import 'shop_buttons.dart';
import 'shop_controls.dart';
import 'shop_decor.dart';

enum SkuPickerMode { addToCart, buyNow }

/// 弹层确认后的选择。
class SkuPick {
  const SkuPick({required this.sku, required this.qty});

  final ShopSku sku;
  final int qty;
}

/// 无 `remaining`（库存充足，服务端不给数字）时的步进器上限：**不设客户端上限**，
/// 用一个足够大的数让 `+` 不被挡（超卖由加购 / 下单接口的 409 兜住）。
const int kSkuPickerUnboundedQty = 9999;

/// 打开规格弹层并在确认后执行动作。[context] / [ref] 取调用方的（弹层关闭后仍要用它们弹 toast、登录、跳转）。
Future<void> showSkuPickerSheet(
  BuildContext context,
  WidgetRef ref, {
  required String productToken,
  required SkuPickerMode mode,
  String? entrySource,
  String? initialSkuToken,
  ValueChanged<ShopSku>? onSkuChanged,
}) async {
  final pick = await pickShopSku(context,
      productToken: productToken,
      mode: mode,
      initialSkuToken: initialSkuToken,
      onSkuChanged: onSkuChanged);
  if (pick == null || !context.mounted) return;
  final isBuy = mode == SkuPickerMode.buyNow;
  Analytics.capture(isBuy ? 'toko_buy_now_tapped' : 'toko_add_to_cart_tapped', {
    'product_token': productToken,
    'sku_token': pick.sku.token,
  });
  void perform() {
    if (!context.mounted) return;
    if (isBuy) {
      context.push(buyNowCheckoutLocation(pick.sku.token, pick.qty, entrySource));
    } else {
      _performAdd(context, ref, pick, entrySource);
    }
  }

  if (ref.read(authControllerProvider).isLoggedIn) {
    perform();
    return;
  }
  await ref.read(loginGuideControllerProvider).showSoftSheet(
        context,
        pendingAction: RouteIntent(onResume: perform),
        entrySource: isBuy ? 'toko_buy_now' : 'toko_add_to_cart',
        allowRepeat: true,
      );
}

/// 立即购买直达结算的路由（不写购物车）。[entrySource] 非空时带 `&from=`（归因，同加购 `entrySource` 值域）。
String buyNowCheckoutLocation(String skuToken, int qty, String? entrySource) =>
    '/shop/checkout?source=buy_now'
    '&sku=${Uri.encodeQueryComponent(skuToken)}&qty=$qty'
    '${entrySource == null || entrySource.isEmpty ? '' : '&from=${Uri.encodeQueryComponent(entrySource)}'}';

/// 只开弹层、返回用户确认的选择（null = 关掉没选）。不埋点、不登录、不执行动作 ——
/// 详情页（8.2）自己管在途锁与动作（bug 516：请求中胶囊坞按钮转圈、不可重复点）。
///
/// [initialSkuToken]：弹层默认选中项（详情页传本页当前规格）；null / 不在列表 → [defaultShopSku]。
/// [onSkuChanged]：用户点规格 chip 即回调（详情页据此回写本页规格、重估运费）。
Future<SkuPick?> pickShopSku(
  BuildContext context, {
  required String productToken,
  required SkuPickerMode mode,
  String? initialSkuToken,
  ValueChanged<ShopSku>? onSkuChanged,
}) =>
    showModalBottomSheet<SkuPick>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (_) => SkuPickerSheet(
        productToken: productToken,
        mode: mode,
        initialSkuToken: initialSkuToken,
        onSkuChanged: onSkuChanged,
      ),
    );

Future<void> _performAdd(
    BuildContext context, WidgetRef ref, SkuPick pick, String? entrySource) async {
  final l10n = AppLocalizations.of(context);
  try {
    await ref.read(cartProvider.notifier).add(pick.sku.token, qty: pick.qty, entrySource: entrySource);
    if (context.mounted) showAppToast(context, l10n.cartAdded);
  } on CartMutationError catch (e) {
    if (context.mounted) {
      showAppToast(context, e == CartMutationError.stock ? l10n.cartStockError : l10n.cartGenericError);
    }
  }
}

class SkuPickerSheet extends ConsumerStatefulWidget {
  const SkuPickerSheet({
    super.key,
    required this.productToken,
    required this.mode,
    this.initialSkuToken,
    this.onSkuChanged,
  });

  final String productToken;
  final SkuPickerMode mode;

  /// 默认选中项（8.2 详情页传本页当前规格）；null → [defaultShopSku]。
  final String? initialSkuToken;

  /// 用户点规格 chip 的回调（8.2 回写详情页规格）。
  final ValueChanged<ShopSku>? onSkuChanged;

  @override
  ConsumerState<SkuPickerSheet> createState() => _SkuPickerSheetState();
}

class _SkuPickerSheetState extends ConsumerState<SkuPickerSheet> {
  late String? _selectedToken = widget.initialSkuToken;
  int _qty = 1;

  ShopSku? _selected(ShopProductDetail d) {
    if (d.skus.isEmpty) return null;
    for (final s in d.skus) {
      if (s.token == _selectedToken) return s;
    }
    return defaultShopSku(d.skus) ?? d.skus.first;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final detail = ref.watch(shopProductDetailProvider(widget.productToken));
    return Padding(
      key: const ValueKey('skuPickerSheet'),
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: detail.when(
        loading: () => const SizedBox(
            height: 200, child: Center(child: CircularProgressIndicator())),
        error: (_, _) => SizedBox(
          height: 200,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(l10n.tokoLoadFailed, style: AppTypography.body),
                TextButton(
                  onPressed: () => ref.invalidate(shopProductDetailProvider(widget.productToken)),
                  child: Text(l10n.commonRetry),
                ),
              ],
            ),
          ),
        ),
        data: (d) => _content(l10n, d),
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ShopProductDetail d) {
    final sku = _selected(d);
    final max = sku?.remaining ?? kSkuPickerUnboundedQty;
    final qty = _qty.clamp(1, max < 1 ? 1 : max);
    final outOfStock = sku == null || !sku.stockStatus.purchasable;
    final sellable = sku?.sellable ?? false;
    final String stockLine;
    if (sku == null || sku.stockStatus == StockStatus.outOfStock) {
      stockLine = l10n.tokoOutOfStock;
    } else if (sku.stockStatus == StockStatus.lowStock) {
      stockLine = sku.remaining != null ? l10n.tokoLowStock(sku.remaining!) : l10n.tokoLowStockNoCount;
    } else {
      stockLine = '';
    }

    final Widget confirm;
    if (sku != null && !sku.sellable) {
      confirm = ShopButton(
        key: const ValueKey('skuPickerConfirm'),
        label: l10n.tokoNotSellable,
        variant: ShopButtonVariant.disabled,
      );
    } else {
      final isBuy = widget.mode == SkuPickerMode.buyNow;
      final can = !outOfStock && sellable;
      confirm = ShopButton(
        key: const ValueKey('skuPickerConfirm'),
        label: isBuy ? l10n.tokoBuyNow : l10n.tokoAddToCartConfirm,
        variant: can ? (isBuy ? ShopButtonVariant.pay : ShopButtonVariant.purple) : ShopButtonVariant.disabled,
        onTap: can ? () => Navigator.of(context).pop(SkuPick(sku: sku, qty: qty)) : null,
      );
    }

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.screenEdge, AppSpacing.lg, AppSpacing.screenEdge, AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ShopImage(url: d.mainImageUrl, size: 80, fit: BoxFit.contain, radius: 12),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      sku == null ? l10n.tokoPriceUnavailable : formatIdr(sku.price),
                      key: const ValueKey('skuPickerPrice'),
                      style: AppTypography.title.copyWith(color: AppColors.mint),
                    ),
                    if (stockLine.isNotEmpty)
                      Text(stockLine,
                          key: const ValueKey('skuPickerStock'),
                          style: AppTypography.caption.copyWith(
                              color: outOfStock ? ShopColors.errorText : AppColors.ink2)),
                    if (sku != null)
                      Text(l10n.tokoSelectedVariant(sku.specName),
                          key: const ValueKey('skuPickerSelected'),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: AppTypography.caption),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.lg),
          Text(l10n.tokoChooseVariant,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600, color: AppColors.ink)),
          const SizedBox(height: AppSpacing.sm),
          Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: [
              for (final s in d.skus)
                ShopChip(
                  key: ValueKey('skuChip_${s.token}'),
                  label: s.specName,
                  selected: s.token == sku?.token,
                  selectedColor: AppColors.mint,
                  onTap: () {
                    setState(() {
                      _selectedToken = s.token;
                      _qty = 1;
                    });
                    widget.onSkuChanged?.call(s);
                  },
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.lg),
          Row(
            children: [
              Expanded(
                child: Text(l10n.tokoQuantity,
                    style: AppTypography.body.copyWith(fontWeight: FontWeight.w600, color: AppColors.ink)),
              ),
              ShopStepper(
                key: const ValueKey('skuPickerStepper'),
                value: qty,
                min: 1,
                max: max < 1 ? 1 : max,
                onChanged: (v) => setState(() => _qty = v),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.xl),
          SizedBox(width: double.infinity, child: confirm),
        ],
      ),
    );
  }
}
