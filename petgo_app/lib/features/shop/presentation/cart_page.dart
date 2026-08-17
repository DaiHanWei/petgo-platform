import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../auth/domain/auth_state.dart';
import '../../auth/domain/login_guide_controller.dart';
import '../data/cart_repository.dart';
import '../domain/shop_cart.dart';
import '../domain/shop_product.dart';

/// 购物车页（Story 3.6，FR-96 / FR-95）。
///
/// 🔴 **三条不能写错的规则：**
///
/// 1. **平铺列表，无店铺分组**（FR-96）——平台自营是唯一卖家。照搬 Shopee 的多店铺分组
///    在自营模式下只是凭空多一层，还会让用户以为有别的卖家。
/// 2. **失效商品单独分区、置于底部、不参与合计、不可结算**，但**不静默消失**——
///    用户加过的东西凭空不见会引发客诉；转失效态是明确告知。下架（永久）与售罄（暂时）
///    用不同 badge，给同一句话会让用户做错决定。
/// 3. **数量上限是当下可售库存**，到顶时 `+` 置灰（FR-95 的第一次校验）。
///    第二次校验在提交订单时（Story 3.4 已实现），本页不重复实现库存逻辑。
///
/// 🔒 **游客无购物车**（有意的能力缺席，不是待放开的限制）：游客进本页不发任何请求，
/// 渲染「登录后查看」空态 + 软性登录引导入口（FR-0B）。
class CartPage extends ConsumerStatefulWidget {
  const CartPage({super.key});

  @override
  ConsumerState<CartPage> createState() => _CartPageState();
}

class _CartPageState extends ConsumerState<CartPage> {
  /// 正在写的 SKU（含 [_invalidBusyKey] 表示「清空失效」进行中）——按钮据此禁用，防连点。
  final Set<String> _busy = {};

  static const String _invalidBusyKey = '__invalid__';

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_cart_page_viewed');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(title: Text(l10n.cartTitle), backgroundColor: AppColors.cream),
      body: auth.isLoggedIn ? _body(l10n) : _guestBody(l10n),
      bottomNavigationBar: auth.isLoggedIn
          ? ref.watch(cartProvider).maybeWhen(
                data: (c) => c.isEmpty ? null : _bottomBar(l10n, c),
                orElse: () => null,
              )
          : null,
    );
  }

  /// 🔒 游客态：**不发请求**（打 `/me/cart` 会 401 → 拦截器强弹窗，等于登录墙）。
  Widget _guestBody(AppLocalizations l10n) => EmptyState(
        icon: Icons.shopping_cart_outlined,
        title: l10n.cartGuestTitle,
        message: l10n.cartGuestMessage,
        actionLabel: l10n.cartGuestCta,
        onAction: () => ref.read(loginGuideControllerProvider).showSoftSheet(
              context,
              // 登录成功后停在购物车页并自动拉车（provider watch 登录态，自行重建）。
              pendingAction: const RouteIntent(),
              entrySource: 'toko_cart',
              allowRepeat: true,
            ),
      );

  Widget _body(AppLocalizations l10n) {
    final async = ref.watch(cartProvider);
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (_, _) => RefreshIndicator(
        onRefresh: () => ref.read(cartProvider.notifier).refresh(),
        child: ListView(
          children: [
            const SizedBox(height: AppSpacing.section),
            Padding(
              padding: const EdgeInsets.all(AppSpacing.xl),
              child: Text(l10n.cartLoadFailed, textAlign: TextAlign.center),
            ),
          ],
        ),
      ),
      data: (cart) => RefreshIndicator(
        onRefresh: () => ref.read(cartProvider.notifier).refresh(),
        child: cart.isEmpty ? _emptyList(l10n) : _list(l10n, cart),
      ),
    );
  }

  /// 空车空态（🎨 UX-DR9 无视觉稿，沿用既有 [EmptyState] 范式）。
  /// 套一层可滚动容器，否则下拉刷新在空态下失效。
  Widget _emptyList(AppLocalizations l10n) => LayoutBuilder(
        builder: (context, box) => SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          child: SizedBox(
            height: box.maxHeight,
            child: EmptyState(
              icon: Icons.shopping_cart_outlined,
              title: l10n.cartEmptyTitle,
              message: l10n.cartEmptyMessage,
              actionLabel: l10n.cartEmptyCta,
              onAction: () => context.canPop() ? context.pop() : context.go('/shop'),
            ),
          ),
        ),
      );

  Widget _list(AppLocalizations l10n, CartView cart) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
      children: [
        // 🔴 平铺，无店铺分组
        for (final line in cart.lines) _validLine(l10n, line),
        // 🔴 失效分区恒在**底部**
        if (cart.invalidLines.isNotEmpty) ...[
          const SizedBox(height: AppSpacing.md),
          _invalidHeader(l10n, cart.invalidLines.length),
          for (final line in cart.invalidLines) _invalidLine(l10n, line),
        ],
        const SizedBox(height: AppSpacing.lg),
      ],
    );
  }

  // ---------- 有效行 ----------

  Widget _validLine(AppLocalizations l10n, CartLine line) {
    final busy = _busy.contains(line.skuToken);
    final atCeiling = !line.canIncrease;

    return Padding(
      key: ValueKey('cartLine_${line.skuToken}'),
      padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.lg, vertical: AppSpacing.sm),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _thumb(line.mainImageUrl),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.productName ?? line.specName,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                const SizedBox(height: AppSpacing.xxs),
                Text(line.specName,
                    style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                const SizedBox(height: AppSpacing.xs),
                Text(formatIdr(line.price),
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.mint)),
                // 🔴 到顶提示用**真实剩余数**（FR-95），不编数字；库存不明时不展示这一行。
                if (atCeiling && line.availableStock != null) ...[
                  const SizedBox(height: AppSpacing.xxs),
                  Text(l10n.cartMaxStock(line.availableStock!),
                      style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                ],
              ],
            ),
          ),
          _qtyStepper(l10n, line, busy: busy),
        ],
      ),
    );
  }

  /// 数量步进器。🔴 **减到 1 再减就是删除**（后端 `qty<=0` 即删，不需要第二个端点），
  /// 图标同步换成垃圾桶——否则用户点了「−」东西却整行消失，会以为点错了。
  Widget _qtyStepper(AppLocalizations l10n, CartLine line, {required bool busy}) {
    final isRemove = line.qty <= 1;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          key: ValueKey('cartDec_${line.skuToken}'),
          tooltip: isRemove ? l10n.cartRemoveLine : l10n.cartDecrease,
          icon: Icon(isRemove ? Icons.delete_outline : Icons.remove, size: 18),
          onPressed: busy ? null : () => _setQty(l10n, line, line.qty - 1),
        ),
        Text('${line.qty}',
            key: ValueKey('cartQty_${line.skuToken}'),
            style: const TextStyle(fontWeight: FontWeight.w600)),
        IconButton(
          key: ValueKey('cartInc_${line.skuToken}'),
          tooltip: l10n.cartIncrease,
          icon: const Icon(Icons.add, size: 18),
          // 🔴 达可售库存上限即置灰（FR-95 第一次校验）。库存不明同样置灰——
          //    宁可挡一次购买，不可放过一次超卖。
          onPressed: (busy || !line.canIncrease) ? null : () => _setQty(l10n, line, line.qty + 1),
        ),
      ],
    );
  }

  // ---------- 失效分区 ----------

  Widget _invalidHeader(AppLocalizations l10n, int count) => Container(
        color: AppColors.cream2,
        padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.lg, vertical: AppSpacing.sm),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(l10n.cartInvalidSection(count),
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
            TextButton(
              key: const ValueKey('cartClearInvalid'),
              onPressed: _busy.contains(_invalidBusyKey) ? null : () => _clearInvalid(l10n),
              child: Text(l10n.cartClearInvalid),
            ),
          ],
        ),
      );

  /// 失效行：整行降饱和 + 原因 badge，**无步进器、无勾选**（不可购买的东西不给可操作控件）。
  Widget _invalidLine(AppLocalizations l10n, CartLine line) => Opacity(
        opacity: 0.55,
        child: Padding(
          key: ValueKey('cartInvalidLine_${line.skuToken}'),
          padding: const EdgeInsets.symmetric(
              horizontal: AppSpacing.lg, vertical: AppSpacing.sm),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _thumb(line.mainImageUrl),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(line.productName ?? line.specName,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                    const SizedBox(height: AppSpacing.xxs),
                    Row(
                      children: [
                        Text('${line.specName} · ',
                            style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                        Text(_reasonLabel(l10n, line.invalidReason),
                            style: const TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                                color: AppColors.danger)),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.xs),
                    Text(formatIdr(line.price),
                        style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                  ],
                ),
              ),
            ],
          ),
        ),
      );

  /// 🔴 下架（永久）与售罄（暂时）措辞必须不同；认不出的原因给不承诺任何事的通用词。
  String _reasonLabel(AppLocalizations l10n, CartInvalidReason? reason) => switch (reason) {
        CartInvalidReason.delisted => l10n.cartReasonDelisted,
        CartInvalidReason.outOfStock => l10n.cartReasonOutOfStock,
        _ => l10n.cartReasonUnavailable,
      };

  // ---------- 底部合计 ----------

  Widget _bottomBar(AppLocalizations l10n, CartView cart) {
    // 🔴 合计与件数都取**有效行**口径（后端已排除失效行）——本页不自己再算一遍，
    //    两处各算必漂移。
    final canCheckout = cart.lines.isNotEmpty;
    return SafeArea(
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: const BoxDecoration(
          color: AppColors.cream,
          border: Border(top: BorderSide(color: AppColors.line)),
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(l10n.cartTotalLabel(cart.itemCount),
                      style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                  Text(formatIdr(cart.subtotal),
                      key: const ValueKey('cartSubtotal'),
                      style: const TextStyle(
                          fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.mint)),
                ],
              ),
            ),
            SizedBox(
              height: 44,
              child: FilledButton(
                key: const ValueKey('cartCheckout'),
                onPressed: canCheckout ? () => _checkout(l10n) : null,
                child: Text(l10n.cartCheckout),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ---------- 动作 ----------

  Future<void> _setQty(AppLocalizations l10n, CartLine line, int qty) async {
    setState(() => _busy.add(line.skuToken));
    try {
      await ref.read(cartProvider.notifier).setQty(line.skuToken, qty);
    } on CartMutationError catch (e) {
      if (mounted) showAppToast(context, _errorText(l10n, e));
    } finally {
      if (mounted) setState(() => _busy.remove(line.skuToken));
    }
  }

  Future<void> _clearInvalid(AppLocalizations l10n) async {
    Analytics.capture('toko_cart_clear_invalid_tapped');
    setState(() => _busy.add(_invalidBusyKey));
    try {
      await ref.read(cartProvider.notifier).clearInvalid();
    } on CartMutationError catch (e) {
      if (mounted) showAppToast(context, _errorText(l10n, e));
    } finally {
      if (mounted) setState(() => _busy.remove(_invalidBusyKey));
    }
  }

  void _checkout(AppLocalizations l10n) {
    Analytics.capture('toko_cart_checkout_tapped');
    // 结算页属 Story 3.7。此处只提示，**不跳不存在的页面**（同 1.6 对购物车入口的处理）。
    showAppToast(context, l10n.cartCheckoutComingSoon);
  }

  String _errorText(AppLocalizations l10n, CartMutationError e) => switch (e) {
        CartMutationError.stock => l10n.cartStockError,
        CartMutationError.generic => l10n.cartGenericError,
      };

  Widget _thumb(String? url) {
    const size = 64.0;
    return ClipRRect(
      borderRadius: BorderRadius.circular(AppSpacing.sm),
      child: SizedBox(
        width: size,
        height: size,
        child: url == null
            ? const ColoredBox(color: AppColors.mintTint2)
            : Image.network(url,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => const ColoredBox(color: AppColors.mintTint2)),
      ),
    );
  }
}
