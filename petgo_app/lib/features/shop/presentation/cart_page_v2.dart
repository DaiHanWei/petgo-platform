/// 购物车 —— **设计稿版式**（V1.4.0 · `01_screens_browse_order.md` 屏 5）。
///
/// 与 [CartPage]（v1 版式）并存，由 `shopUiVariantProvider` 二选一。
///
/// ## 🔴 单店模型：没有店铺分组
///
/// 平台自营是唯一卖家（设计稿关键原则 1），因此**无店铺分组行、无分店铺小计、
/// 无分店铺凑单**，整车只有一个总计。照搬第三方电商的多店铺分组只会凭空多一层。
///
/// ## 🔴 设计稿的「每行勾选框」**刻意不实现**
///
/// 设计稿给每行画了勾选框，底部总计写「已勾选的可购商品件数」。
/// 但下单接口是 `placeOrder(addressToken)` —— **整车下单，没有行选择的概念**。
///
/// 三条路都不能走：
/// - 画勾选框但不影响下单 → 用户勾掉一行仍然会被买走，这是**能造成资损的谎**；
/// - 勾选框全选中且禁用 → 一个点不动的控件，比没有更让人困惑；
/// - 结算前把未勾选的行删掉 → 用破坏性操作模拟一个查询语义，取消结算就丢数据。
///
/// 故本版式**不画勾选框**，底部总计 = 全部有效行。
/// 补齐需要后端支持「行选择」（购物车行加 selected 位，或下单接受 skuToken 列表），
/// 属接口能力缺口，不是版式取舍。
///
/// ## 其余按设计稿空态规则降级
///
/// - **凑单条**（`Tambah Rp X lagi untuk gratis ongkir`）：购物车接口无免运门槛字段
///   → 「无免运活动时整条不渲染」。
/// - **批量管理态**（导航栏右侧 `Ubah`）：无对应能力 → 不渲染入口。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../auth/domain/auth_state.dart';
import '../data/cart_repository.dart';
import '../domain/shop_cart.dart';
import '../domain/shop_product.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_surface.dart';

class CartPageV2 extends ConsumerWidget {
  const CartPageV2({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(cartProvider);
    // 🔒 游客态：**页内软性引导，不 redirect**。把游客弹回 /home 等于告诉他
    //    「这里没有购物车」，而真相是「登录后就有」—— 那正是软性引导存在的理由。
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: _title(l10n, async)),
      body: !loggedIn
          ? _guestState(context, l10n)
          : async.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (_, _) => _hint(l10n.cartLoadFailed),
              data: (cart) =>
                  cart.isEmpty ? _emptyState(context, l10n) : _list(context, ref, l10n, cart),
            ),
      bottomNavigationBar: !loggedIn
          ? null
          : async.maybeWhen(
              data: (cart) => cart.lines.isEmpty ? null : _bottomBar(context, l10n, cart),
              orElse: () => null,
            ),
    );
  }

  /// 导航栏标题带计数。🔴 计数**含失效商品** —— 用户加过的东西都该被算进「车里有几样」，
  /// 否则他会以为自己漏加了。
  String _title(AppLocalizations l10n, AsyncValue<CartView> async) => async.maybeWhen(
        data: (c) {
          final n = c.lines.length + c.invalidLines.length;
          return n == 0 ? l10n.cartTitle : '${l10n.cartTitle} ($n)';
        },
        orElse: () => l10n.cartTitle,
      );

  Widget _list(
      BuildContext context, WidgetRef ref, AppLocalizations l10n, CartView cart) {
    return ListView(
      padding: EdgeInsets.zero,
      children: [
        for (final line in cart.lines) _ValidLine(line: line),
        // 🔴 失效行**沉到独立分组**，不混在可购商品里，也不静默消失 ——
        //    悄悄删掉会让用户以为自己记错了。
        if (cart.invalidLines.isNotEmpty) ...[
          _invalidHeader(context, ref, l10n, cart),
          for (final line in cart.invalidLines) _InvalidLine(line: line),
        ],
        // 凑单条：无免运门槛数据源 → 整条不渲染（见文件头）。
        const SizedBox(height: kShopGutter),
      ],
    );
  }

  Widget _invalidHeader(
          BuildContext context, WidgetRef ref, AppLocalizations l10n, CartView cart) =>
      ShopSection(
        padding: const EdgeInsets.fromLTRB(kShopScreenEdge, 10, kShopScreenEdge, 10),
        gutter: false,
        child: Row(
          children: [
            Expanded(
              child: Text(l10n.cartInvalidSection(cart.invalidLines.length),
                  style: ShopText.cardTitle.copyWith(color: ShopColors.text3)),
            ),
            InkWell(
              key: const ValueKey('cartClearInvalidV2'),
              onTap: () async {
                Analytics.capture('toko_cart_clear_invalid_tapped');
                await ref.read(cartProvider.notifier).clearInvalid();
              },
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
                child: Text(l10n.cartClearInvalid,
                    style: ShopText.badge
                        .copyWith(fontSize: 10.5, color: ShopColors.purple)),
              ),
            ),
          ],
        ),
      );

  Widget _bottomBar(BuildContext context, AppLocalizations l10n, CartView cart) =>
      ShopBottomBarWithTotal(
        // 🔴 「n barang」是**件数**不是种类数（FR-96）—— 要跟用户脑子里的
        //    「我买了几件」对上。后端 itemCount 只累计有效行。
        label: l10n.cartTotalLabel(cart.itemCount),
        amount: formatIdr(cart.subtotal),
        action: ShopButton(
          key: const ValueKey('cartCheckoutV2'),
          label: l10n.cartCheckout,
          variant: ShopButtonVariant.pay,
          padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
          onTap: () => context.push('/shop/checkout'),
        ),
      );

  Widget _guestState(BuildContext context, AppLocalizations l10n) => _CenteredState(
        title: l10n.cartGuestTitle,
        message: l10n.cartGuestMessage,
        cta: l10n.cartGuestCta,
        onTap: () => context.push('/login'),
      );

  Widget _emptyState(BuildContext context, AppLocalizations l10n) => _CenteredState(
        title: l10n.cartEmptyTitle,
        message: l10n.cartEmptyMessage,
        cta: l10n.cartEmptyCta,
        onTap: () => context.go('/shop'),
      );

  Widget _hint(String text) => ShopSection(
        padding: const EdgeInsets.symmetric(vertical: 32, horizontal: kShopScreenEdge),
        child: Center(child: Text(text, textAlign: TextAlign.center, style: ShopText.body)),
      );
}

/// 可购商品行。
class _ValidLine extends ConsumerStatefulWidget {
  const _ValidLine({required this.line});

  final CartLine line;

  @override
  ConsumerState<_ValidLine> createState() => _ValidLineState();
}

class _ValidLineState extends ConsumerState<_ValidLine> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final line = widget.line;
    // 🔴 库存不明（availableStock == null）时**禁止加** —— 宁可挡一次购买，
    //    不可放过一次超卖。后端仍会二次校验，这里只是不让用户白点。
    final maxQty = line.availableStock ?? line.qty;

    return ShopSection(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ShopImage(
              url: line.mainImageUrl, size: 64, radius: ShopShape.radiusField),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.productName ?? line.specName,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: ShopText.productNameCard),
                const SizedBox(height: 2),
                Text(line.specName, style: ShopText.meta),
                const SizedBox(height: 5),
                Text(formatIdr(line.price),
                    style: ShopText.priceRail.copyWith(color: ShopColors.accent)),
                const SizedBox(height: 6),
                Row(
                  children: [
                    Expanded(child: _stockTag(l10n, line)),
                    ShopStepper(
                      value: line.qty,
                      min: 1,
                      max: maxQty,
                      onChanged: _busy ? null : (v) => _setQty(l10n, line, v),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 库存警告标签。🔴 只在**触顶或接近触顶**时出现 —— 常驻的「还剩 N 件」
  /// 会退化成背景噪音，真正紧张时反而没人看。
  Widget _stockTag(AppLocalizations l10n, CartLine line) {
    final stock = line.availableStock;
    if (stock == null || line.qty < stock) return const SizedBox.shrink();
    return Container(
      key: const ValueKey('cartStockTag'),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
      decoration: BoxDecoration(
        color: ShopColors.warnBg,
        borderRadius: BorderRadius.circular(ShopShape.radiusBadge),
      ),
      child: Text(l10n.cartMaxStock(stock),
          style: ShopText.badge.copyWith(fontSize: 9.5, color: ShopColors.warnTitle)),
    );
  }

  Future<void> _setQty(AppLocalizations l10n, CartLine line, int qty) async {
    setState(() => _busy = true);
    try {
      await ref.read(cartProvider.notifier).setQty(line.skuToken, qty);
    } on CartMutationError catch (e) {
      if (mounted) {
        showAppToast(
            context, e == CartMutationError.stock ? l10n.cartStockError : l10n.cartGenericError);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

/// 失效商品行。
///
/// 整组 `opacity: .75`；图上盖蒙层；价格转灰；**不参与合计**。
/// 两个出口：`Cari mirip`（紫描边，**优先级更高**）与 `Hapus`（浅描边）——
/// 🔴 失效不等于流失：一件买不到的东西，用户真正想要的是「有没有别的」。
class _InvalidLine extends ConsumerWidget {
  const _InvalidLine({required this.line});

  final CartLine line;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Opacity(
      opacity: .75,
      child: ShopSection(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 64,
              height: 64,
              child: Stack(
                children: [
                  ShopImage(
                      url: line.mainImageUrl, size: 64, radius: ShopShape.radiusField),
                  ShopSoldOutOverlay(
                    label: _reasonShort(l10n, line.invalidReason),
                    scrim: ShopColors.soldOutScrimCart,
                    labelSize: 9,
                    radius: ShopShape.radiusField,
                  ),
                ],
              ),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(line.productName ?? line.specName,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: ShopText.productNameCard.copyWith(color: ShopColors.text3)),
                  const SizedBox(height: 2),
                  // 🔴 价格转灰 —— 买不到的价格不该用玫红做促销刺激（三色分工）。
                  Text(formatIdr(line.price),
                      style: ShopText.priceRail.copyWith(color: ShopColors.text4)),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      ShopButton(
                        key: ValueKey('cartFindSimilar_${line.skuToken}'),
                        label: l10n.cartFindSimilar,
                        variant: ShopButtonVariant.outlinePurple,
                        dense: true,
                        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
                        // 无「同类目替代品」端点 → 跳全部精选列表。
                        // 语义仍是「看看别的」，不编一个不存在的推荐。
                        onTap: () => context.go('/shop'),
                      ),
                      const SizedBox(width: 7),
                      ShopButton(
                        key: ValueKey('cartRemoveInvalid_${line.skuToken}'),
                        label: l10n.cartRemoveShort,
                        variant: ShopButtonVariant.outlineMuted,
                        dense: true,
                        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
                        onTap: () => ref.read(cartProvider.notifier).remove(line.skuToken),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 蒙层上的短原因。
  ///
  /// 🔴 认不出的原因照样算失效，只给一句**不承诺任何事**的通用文案 ——
  /// 反过来（认不出就当有效）会把一件卖不了的东西放进合计。
  String _reasonShort(AppLocalizations l10n, CartInvalidReason? reason) => switch (reason) {
        CartInvalidReason.delisted => l10n.cartReasonDelisted,
        CartInvalidReason.outOfStock => l10n.cartReasonOutOfStock,
        _ => l10n.cartReasonUnavailable,
      };
}

class _CenteredState extends StatelessWidget {
  const _CenteredState({
    required this.title,
    required this.message,
    required this.cta,
    required this.onTap,
  });

  final String title;
  final String message;
  final String cta;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(title, textAlign: TextAlign.center, style: ShopText.sectionTitle),
              const SizedBox(height: 6),
              Text(message, textAlign: TextAlign.center, style: ShopText.body),
              const SizedBox(height: 16),
              ShopButton(label: cta, variant: ShopButtonVariant.purple, onTap: onTap),
            ],
          ),
        ),
      );
}
