/// 购物车 v3（v1.4.2 shop Story 8.3 · UI C1–C8；取代 `cart_page_v2.dart`）。
///
/// ## 🔴 单店模型：没有店铺分组
///
/// 平台自营是唯一卖家，**无店铺分组行、无分店铺小计、无分店铺凑单**，整车只有一个总计。
///
/// ## 🔴 金额只来自服务端（P-1）
///
/// 底栏「Estimasi total」= `estimate.payable`、「Hemat」= `estimate.saved`、免运差额 =
/// `estimate.freeShippingProgress`，全部由后端 `ShopPricingCalculator` 算出。本文件里
/// **不存在任何对行求和、加运费、减券的代码**。旧后端不下发 `estimate` 时回落到
/// `selectedSubtotal`，并标「Belum termasuk ongkir」（不含运费，诚实）。
///
/// ## 自 V1.3.0 沿用的三条硬约束（4-1 / 4-2）
/// - 金额只认服务端下发的数，前端绝不自己把选中行加起来。
/// - **取消勾选只调选择端点，绝不调 `remove`/`DELETE`** —— 取消结算不能丢数据。
/// - 失效行的勾选框**占位**（左边缘不参差）。
///
/// ⚠️ 第三条在 v1.4.2 改线上（PRD 11-05 拍板，取代 2026-09-18 复审 #4 的「如实反映 + 可点」）：
/// 后端 3.1 起失效行对外 `selected` 恒 false、勾选失效行 422 —— 本页失效行勾选框**恒未勾、不可点、
/// 不发请求**。库内历史上仍勾着的失效行会挡结算（`UNAVAILABLE_LINES` / 409），出口是失效分组的
/// 「Hapus semua」/ 行内「Hapus」，以及结算页 7.1 的失效卡片「移出购物车」。
///
/// ## 本页不做（story 拍板）
/// - 合计上拉明细层（定稿未画、`estimate` 无明细字段）。
/// - 购物车内换地址（购物车只按默认地址估算；送达行点开进地址簿）。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../../auth/domain/auth_state.dart';
import '../address/data/address_repository.dart';
import '../address/domain/shipping_address.dart';
import '../data/cart_repository.dart';
import '../data/shipping_estimate_repository.dart';
import '../domain/shop_cart.dart';
import '../domain/shop_product.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_dialog.dart';
import 'widgets/shop_pressable.dart';
import 'widgets/shop_reco_grid.dart';
import 'widgets/shop_surface.dart';

const _tabular = [FontFeature.tabularFigures()];

/// 商品图边长（UI C1：64 → 72）。
const double _kImage = 72;

/// 🔴 有状态只为页面曝光埋点：必须在 initState 而不是 build 里报 ——
/// build 会因 provider 变化被反复调用，那样一次浏览会上报很多条。
class CartPageV3 extends ConsumerStatefulWidget {
  const CartPageV3({super.key});

  @override
  ConsumerState<CartPageV3> createState() => _CartPageV3State();
}

class _CartPageV3State extends ConsumerState<CartPageV3> {
  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_cart_page_viewed');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(cartProvider);
    // 🔒 游客态：**页内软性引导，不 redirect**，且不发任何 /me 请求（FR-93A）。
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: _title(l10n, async)),
      body: !loggedIn
          ? _guestState(context, l10n)
          : async.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (_, _) => ShopRetryState(
                message: l10n.cartLoadFailed,
                retryLabel: l10n.commonRetry,
                onRetry: () => ref.invalidate(cartProvider),
              ),
              data: (cart) =>
                  cart.isEmpty ? _emptyState(context, l10n) : _list(context, l10n, cart),
            ),
      // 🔴 只剩失效行时不算空、但**无底栏**：一车都是买不了的东西，结算按钮点下去必然失败。
      bottomNavigationBar: !loggedIn
          ? null
          : async.maybeWhen(
              data: (cart) => cart.lines.isEmpty ? null : _CartBottomBar(cart: cart),
              orElse: () => null,
            ),
    );
  }

  /// 删除后的撤销条。**必须挂在页面 State 上，不能挂在行上。**
  ///
  /// 🔴 2026-09-03 stag 回归 R-3「撤销点了没反应」的根因就在这里：原实现把
  /// `_offerUndo` / `_undoRemove` 放在 `_ValidLineState`（每一行自己的 State）上。
  /// 删除一成功，这一行就从 `cart.lines` 里没了 → State 变 defunct → 5 秒后用户点
  /// 「撤销」，第一句 `setState(() => _busy = true)` 当场抛
  /// `setState() called after dispose()`，**加回购物车的请求根本没发出去**。
  /// 现象极具迷惑性：点击本身是通的（回调确实触发了），服务端却只看得到 DELETE，
  /// 界面也毫无反应 —— release 包里这个异常连日志都不留。
  /// ⚠️ 任何「动作要比触发它的控件活得久」的回调，都必须挂在活得更久的那一层。
  ///
  /// 走本仓统一的 [showAppToast]（`app_toast.dart` 的类注释：「全 App 短提示统一入口，
  /// 替代 SnackBar」）。该组件 2026-09-02 加了动作位 —— 此前它被 [IgnorePointer]
  /// 包着、刻意不可交互，而这条提示的**全部意义**就是那个可点的「撤销」。
  ///
  /// ⚠️ 选它而不是 SnackBar 的实际差别：toast 挂在 **root Overlay** 上，
  /// **不参与布局** —— 而本页底部有合计条 + 结算按钮，SnackBar 会从下方顶进来压着它。
  ///
  /// 停留 5 秒（产品定的 4–5 秒区间上限）：短于此，误删的人还没反应过来就没了；
  /// 长于此，它在屏幕上待得比这次操作本身还久。
  ///
  /// ⚠️ 连删两行只留最后一条是 **toast 单实例**天然带来的（新的自动替换旧的），
  /// 不需要像 SnackBar 那样自己先 hide —— 叠着的旧提示条点下去，
  /// 撤销的不是用户以为的那一行。
  void _offerUndo(
      {required String skuToken, required int qty, required String name}) {
    final l10n = AppLocalizations.of(context);
    // 🔴 撤销条挂在根 Overlay 上、会比本页多活 5 秒：用户删完立刻返回，再点「撤销」时
    //    本 State 已销毁，`ref` 不能再用（riverpod 3 抛 StateError，加回请求根本没发）。
    //    所以容器与 overlay 都在这里先取好，撤销走它们，不依赖页面还活着。
    final container = ProviderScope.containerOf(context, listen: false);
    final overlay = Overlay.maybeOf(context, rootOverlay: true);
    if (overlay == null) return;
    showAppToastOnOverlay(
      overlay,
      l10n.cartRemovedUndo(name),
      duration: const Duration(seconds: 5),
      actionLabel: l10n.cartUndo,
      onAction: () => _undoRemove(container, overlay, l10n,
          skuToken: skuToken, qty: qty),
    );
  }

  /// 撤销删除：按原数量把这一行加回去。
  ///
  /// ⚠️ **归因会变成 `CART_UNDO` 而不是原来的入口**（Story 3.10 的 entrySource）：
  /// 后端购物车接口**刻意不下发**归因给客户端（`CartView.CartLine` 的注释写明了理由），
  /// 所以端上拿不到原值、也无从还原。两种选择里取了「写一个自述的值」而不是留 null ——
  /// null 与「归因字段上线前的老数据」长得一模一样，事后没人分得清；
  /// `CART_UNDO` 至少是可解释、可筛掉的。⚠️ 这一条要不要计入转化口径，由数据侧定。
  ///
  /// 🔴 撤销可能**失败**：删除到撤销之间库存被别人买走，`add` 会抛库存错误。
  /// 那时必须出声 —— 静默失败会让用户以为撤销成功了，直到结算时才发现少了一件。
  /// 🔴 **不设 busy 态**：撤销执行时那一行已经不在树上，没有可禁用的按钮；
  /// 而 toast 在调 onAction 前就自我收起了（app_toast 里那句 `_dismiss()`），
  /// 连点两次也不可能。原实现在这里 `setState(() => _busy = true)`，
  /// 正是 2026-09-03 那条「撤销点了没反应」的根因 —— 见 [_offerUndo]。
  static Future<void> _undoRemove(ProviderContainer container,
      OverlayState overlay, AppLocalizations l10n,
      {required String skuToken, required int qty}) async {
    try {
      await container
          .read(cartProvider.notifier)
          .add(skuToken, qty: qty, entrySource: 'CART_UNDO');
    } on CartMutationError catch (e) {
      if (overlay.mounted) {
        showAppToastOnOverlay(overlay,
            e == CartMutationError.stock ? l10n.cartStockError : l10n.cartGenericError);
      }
    }
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

  Widget _list(BuildContext context, AppLocalizations l10n, CartView cart) {
    return ListView(
      padding: const EdgeInsets.only(top: AppSpacing.md, bottom: AppSpacing.lg),
      children: [
        if (cart.lines.isNotEmpty) _ShipToRow(cart: cart),
        if (cart.lines.isNotEmpty)
          // 🔴 同一张白卡片内分隔（UI C1：不再每行一块白底）。
          ShopCard(
            key: const ValueKey('cartValidCard'),
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md),
            child: Column(
              children: [
                for (var i = 0; i < cart.lines.length; i++) ...[
                  if (i > 0) const ShopDivider(),
                  _ValidLine(
                      key: ValueKey(cart.lines[i].skuToken),
                      line: cart.lines[i],
                      onRemoved: _offerUndo),
                ],
              ],
            ),
          ),
        // 🔴 失效行**沉到独立分组**，不混在可购商品里，也不静默消失。
        if (cart.invalidLines.isNotEmpty)
          ShopCard(
            key: const ValueKey('cartInvalidCard'),
            padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.xs, AppSpacing.md, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _invalidHeader(context, l10n, cart),
                for (final line in cart.invalidLines) ...[
                  const ShopDivider(),
                  _InvalidLine(key: ValueKey('invalid_${line.skuToken}'), line: line),
                ],
              ],
            ),
          ),
      ],
    );
  }

  Widget _invalidHeader(BuildContext context, AppLocalizations l10n, CartView cart) => Row(
        children: [
          Expanded(
            child: Text(l10n.cartInvalidSection(cart.invalidLines.length),
                style: AppTypography.body
                    .copyWith(fontWeight: FontWeight.w600, color: ShopColors.text3)),
          ),
          // 🔴 批量删除不可撤销，先确认（2026-08-27）。命中区 ≥ 44。
          ShopPressable(
            key: const ValueKey('cartClearInvalidV3'),
            onTap: () async {
              final ok = await showShopConfirm(
                context,
                dialogKey: const ValueKey('cartClearInvalidConfirmV3'),
                confirmKey: const ValueKey('cartClearInvalidConfirmYesV3'),
                title: l10n.cartClearInvalidConfirmTitle,
                body: l10n.cartClearInvalidConfirmBody,
                confirmLabel: l10n.cartClearInvalidConfirmYes,
                cancelLabel: l10n.commonCancel,
              );
              if (!ok) return;
              Analytics.capture('toko_cart_clear_invalid_tapped');
              try {
                await ref.read(cartProvider.notifier).clearInvalid();
              } on CartMutationError {
                if (context.mounted) showAppToast(context, l10n.cartGenericError);
              }
            },
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 12),
              child: Text(l10n.cartClearInvalid,
                  style: AppTypography.caption
                      .copyWith(fontWeight: FontWeight.w600, color: ShopColors.purple)),
            ),
          ),
        ],
      );

  Widget _guestState(BuildContext context, AppLocalizations l10n) => Center(
        child: _CenteredState(
          title: l10n.cartGuestTitle,
          message: l10n.cartGuestMessage,
          cta: l10n.cartGuestCta,
          onTap: () => context.push('/login'),
        ),
      );

  /// C6 空购物车：原有空态 + 下方推荐（8.1 [ShopRecoGrid]，有档案为宠物推荐、否则 top 6）。
  /// 推荐卡「＋」加购成功 → `cartProvider` 更新 → 本页自然切到列表态。
  Widget _emptyState(BuildContext context, AppLocalizations l10n) => ListView(
        key: const ValueKey('cartEmptyV3'),
        padding: const EdgeInsets.only(bottom: AppSpacing.xl),
        children: [
          _CenteredState(
            title: l10n.cartEmptyTitle,
            message: l10n.cartEmptyMessage,
            cta: l10n.cartEmptyCta,
            onTap: () => context.go('/shop'),
          ),
          const ShopRecoGrid(source: ShopRecoSource.profileOrTop),
        ],
      );
}

/// 顶部送达行（UI C1 / C2）。
///
/// 地区名：默认地址的 kecamatan → 否则本机记住的送达地区（8.2）。
/// 服务端给不出运费（`lowestFee == null` 且不是「一件没选」）、或两处都没有地区 →
/// 「Pilih alamat untuk hitung ongkir」。点击：有地址 → 地址簿；无地址 → 新增地址；
/// 返回后刷新地址列表并按新的默认地址重估购物车。
///
/// 复审修复：地址列表**加载中**显示「—」（不先闪本机地区，再跳成默认地址）；加载中 / 出错时
/// 不知道有没有地址，点击一律进地址簿（那里自带新增入口），不误进「新增地址」造出第二个地址。
class _ShipToRow extends ConsumerWidget {
  const _ShipToRow({required this.cart});

  final CartView cart;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final addrAsync = ref.watch(addressListProvider);
    final addrLoading = addrAsync.isLoading && !addrAsync.hasValue && !addrAsync.hasError;
    final addrUnknown = !addrAsync.hasValue; // 加载中或出错
    final addresses = addrAsync.maybeWhen(
        data: (v) => v, orElse: () => const <ShippingAddress>[]);
    final def = addresses.where((a) => a.isDefault).firstOrNull;
    final area = ref.watch(shopDeliveryAreaProvider).asData?.value;
    final kec = (def != null && def.kecamatan.isNotEmpty) ? def.kecamatan : area?.kecamatan;
    final est = cart.estimate;
    final noFee = est != null &&
        est.lowestFee == null &&
        est.reason != CartEstimate.reasonNoSelection;
    final choose = kec == null || noFee;
    return ShopCard(
      padding: EdgeInsets.zero,
      child: ShopPressable(
        key: const ValueKey('cartShipToRow'),
        onTap: () async {
          await context.push(addrUnknown || addresses.isNotEmpty
              ? '/me/addresses'
              : '/me/addresses/new');
          ref.invalidate(addressListProvider);
          await ref.read(cartProvider.notifier).refresh();
        },
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.md),
          child: Row(
            children: [
              const Icon(Icons.location_on, size: 18, color: ShopColors.purple),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  addrLoading
                      ? '—'
                      : (choose ? l10n.cartChooseAddressForShipping : l10n.cartShipTo(kec)),
                  key: const ValueKey('cartShipToText'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.body.copyWith(fontSize: 14, color: ShopColors.text),
                ),
              ),
              const Icon(Icons.chevron_right_rounded, color: ShopColors.text4),
            ],
          ),
        ),
      ),
    );
  }
}

/// 底栏（UI C1 / C2 / C4 / C5）：免运进度一行 + 「全选 | 金额两行 | Checkout (n)」。
///
/// 🔴 **一件没勾时 Checkout 禁用，且不发任何请求**；金额变灰、不画免运进度与「Hemat」。
class _CartBottomBar extends StatelessWidget {
  const _CartBottomBar({required this.cart});

  final CartView cart;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final nothingSelected = cart.selectedCount == 0;
    final est = cart.estimate;
    // 旧后端（无 estimate）回落 selectedSubtotal —— 它本就不含运费，标签照实说。
    final amount = est?.payable ?? cart.selectedSubtotal;
    final label = nothingSelected
        ? l10n.cartSelectNoneHint
        : (est != null && est.shippingKnown
            ? l10n.cartEstimatedTotal
            : l10n.cartShippingNotIncluded);
    final saved = (!nothingSelected && est != null) ? est.saved : 0;
    final progress = nothingSelected ? null : est?.freeShippingProgress;
    final String? progressText = (progress == null || progress.threshold <= 0)
        ? null
        : (progress.remaining > 0
            ? l10n.tokoFreeShipRemaining(formatIdrAmount(progress.remaining))
            : l10n.tokoFreeShipReached);
    final safeBottom = MediaQuery.viewPaddingOf(context).bottom;

    return DecoratedBox(
      decoration: const BoxDecoration(
        color: ShopColors.surface,
        border: Border(top: BorderSide(color: ShopColors.border2)),
      ),
      child: Padding(
        padding: EdgeInsets.fromLTRB(
            AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, AppSpacing.sm + safeBottom),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (progressText != null)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                child: Row(
                  children: [
                    const Icon(Icons.local_shipping, size: 16, color: ShopColors.purple),
                    const SizedBox(width: AppSpacing.sm),
                    Expanded(
                      child: Text(progressText,
                          key: const ValueKey('cartFreeShipProgress'),
                          style: AppTypography.caption.copyWith(color: ShopColors.purpleText)),
                    ),
                  ],
                ),
              ),
            Row(
              children: [
                _SelectAllToggle(cart: cart),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(label,
                          key: const ValueKey('cartTotalLabelV3'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: AppTypography.caption),
                      Text(formatIdr(amount),
                          key: const ValueKey('cartPayableV3'),
                          style: AppTypography.title.copyWith(
                              fontWeight: FontWeight.w700,
                              fontFeatures: _tabular,
                              color: nothingSelected ? ShopColors.text4 : ShopColors.purple)),
                      if (saved > 0)
                        Text(l10n.tokoSavedAmount(formatIdrAmount(saved)),
                            key: const ValueKey('cartSavedV3'),
                            style: AppTypography.caption.copyWith(
                                color: ShopColors.saving, fontWeight: FontWeight.w600)),
                    ],
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                ConstrainedBox(
                  constraints: const BoxConstraints(minHeight: 48),
                  child: ShopButton(
                    key: const ValueKey('cartCheckoutV3'),
                    // 件数 = selectedCount（与 UI C1「(3)」= 1 + 2 件一致；拼法同标题）。
                    label: nothingSelected
                        ? l10n.cartCheckout
                        : '${l10n.cartCheckout} (${cart.selectedCount})',
                    // ShopButton 的配色只看 variant —— 光把 onTap 置 null，按钮仍是实心紫（2026-09-21 stag 验收）。
                    variant:
                        nothingSelected ? ShopButtonVariant.disabled : ShopButtonVariant.pay,
                    padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
                    // CART 模式：结算哪些行由服务端按「selected 且有效」决定。
                    onTap: nothingSelected ? null : () => context.push('/shop/checkout'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// 底栏左侧「全选」。
///
/// 🔴 <b>选中态判定排除失效行</b>（[CartView.allValidSelected]）：车里有一件下架商品时，
/// 这个框仍要能亮 —— 否则用户会以为控件坏了。
/// 点击走 `setAllSelected`（不是逐行）：全选服务端只勾有效行（3.1），全不选作用于全部行。
class _SelectAllToggle extends ConsumerStatefulWidget {
  const _SelectAllToggle({required this.cart});

  final CartView cart;

  @override
  ConsumerState<_SelectAllToggle> createState() => _SelectAllToggleState();
}

class _SelectAllToggleState extends ConsumerState<_SelectAllToggle> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final all = widget.cart.allValidSelected;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        ShopCheckbox(
          key: const ValueKey('cartSelectAllV3'),
          value: all,
          onChanged: _busy ? null : (v) => _toggle(l10n, v),
          semanticLabel: l10n.cartSelectAll,
        ),
        const SizedBox(width: AppSpacing.xs),
        Text(l10n.cartSelectAll, style: AppTypography.caption),
      ],
    );
  }

  Future<void> _toggle(AppLocalizations l10n, bool selected) async {
    setState(() => _busy = true);
    try {
      await ref.read(cartProvider.notifier).setAllSelected(selected);
    } on CartMutationError {
      if (mounted) showAppToast(context, l10n.cartGenericError);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

/// 可购商品行（卡片内一行）。
class _ValidLine extends ConsumerStatefulWidget {
  const _ValidLine({super.key, required this.line, required this.onRemoved});

  final CartLine line;

  /// 删成功后回调**页面**去弹撤销条。
  ///
  /// 🔴 撤销不能由本行自己管：删掉的那一瞬间这一行就从列表里没了、State 变 defunct，
  /// 而撤销条要在它死后再活 5 秒。详见 [_CartPageV3State._offerUndo]。
  final void Function({required String skuToken, required int qty, required String name})
      onRemoved;

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

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 🔴 勾选框在最左，与失效行的占位对齐 —— 两组的左边缘不能参差。
          Padding(
            padding: const EdgeInsets.only(top: 26),
            child: ShopCheckbox(
              key: ValueKey('cartLineCheckbox_${line.skuToken}'),
              value: line.selected,
              onChanged: _busy ? null : (v) => _setSelected(l10n, line, v),
              semanticLabel: l10n.cartSelectLine(line.productName ?? line.specName),
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          ShopImage(url: line.mainImageUrl, size: _kImage, radius: AppRounded.sm),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.productName ?? line.specName,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: AppTypography.body.copyWith(
                        fontSize: 13, height: 1.35, fontWeight: FontWeight.w600,
                        color: ShopColors.text)),
                const SizedBox(height: AppSpacing.xxs),
                Text(line.specName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: AppTypography.micro.copyWith(color: ShopColors.text3)),
                const SizedBox(height: AppSpacing.xs),
                Text(formatIdr(line.price),
                    style: AppTypography.body.copyWith(
                        fontWeight: FontWeight.w700,
                        fontFeatures: _tabular,
                        color: ShopColors.accent)),
                const SizedBox(height: AppSpacing.xs),
                Row(
                  children: [
                    Expanded(child: _stockTag(l10n, line)),
                    ShopStepper(
                      value: line.qty,
                      min: 1,
                      max: maxQty,
                      onChanged: _busy ? null : (v) => _setQty(l10n, line, v),
                      // 🔴 触底再减 = 删除整行（qty 1 时「−」变删除）。
                      onRemove: _busy ? null : () => _remove(l10n, line),
                      decrementLabel: l10n.cartDecrease,
                      incrementLabel: l10n.cartIncrease,
                      removeLabel: l10n.cartRemoveLine,
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

  /// 库存警告标签。🔴 只在**触顶**时出现 —— 常驻的「还剩 N 件」会退化成背景噪音。
  Widget _stockTag(AppLocalizations l10n, CartLine line) {
    final stock = line.availableStock;
    if (stock == null || line.qty < stock) return const SizedBox.shrink();
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        key: const ValueKey('cartStockTag'),
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
        decoration: BoxDecoration(
          color: ShopColors.warnBg,
          borderRadius: BorderRadius.circular(ShopShape.radiusBadge),
        ),
        child: Text(l10n.cartMaxStock(stock),
            style: AppTypography.badge.copyWith(color: ShopColors.warnTitle)),
      ),
    );
  }

  /// 删除整行。
  ///
  /// 🔴 **删完必须给 undo**（R-3，2026-09-02 产品拍板）。风险在**按钮位复用**：
  /// [ShopStepper] 在 `value <= min` 时把同一个位置的「−」换成垃圾桶，连点减号收数量，
  /// 最后一下必然落在已经变成垃圾桶的同一坐标上。
  /// ⚠️ **不用二次确认弹窗**：会拖慢正常的收数量操作，误删的代价用 undo 完全覆盖得住。
  Future<void> _remove(AppLocalizations l10n, CartLine line) async {
    setState(() => _busy = true);
    // 🔴 删之前把这一行记下来：删完服务端就没有它了，undo 只能靠这份快照重建。
    final skuToken = line.skuToken;
    final qty = line.qty;
    final name = line.productName ?? line.specName;
    // 🔴 回调必须在 await **之前**捕获：remove 一成功本 State 立刻 defunct。
    final offerUndo = widget.onRemoved;
    try {
      await ref.read(cartProvider.notifier).remove(skuToken);
      // ⚠️ 这里**不判 mounted**：删成功后本行本就该消失，回调指向的是页面（还活着）。
      offerUndo(skuToken: skuToken, qty: qty, name: name);
    } on CartMutationError {
      if (mounted) showAppToast(context, l10n.cartGenericError);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 勾选 / 取消勾选本行。🔴 <b>绝不调 `remove`</b>：取消勾选是「这次不买」，不是「不要了」。
  /// 🔴 <b>不做乐观更新</b>：直接用端点返回的整份 CartView（含新的 `estimate`）覆盖。
  Future<void> _setSelected(AppLocalizations l10n, CartLine line, bool selected) async {
    setState(() => _busy = true);
    try {
      await ref.read(cartProvider.notifier).setSelected(line.skuToken, selected);
    } on CartMutationError {
      if (mounted) showAppToast(context, l10n.cartGenericError);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
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

/// 失效商品行（UI C3）。
///
/// - 失效原因是**图片底部一颗深色小标签**（11-05），不再整块蒙层压在图中间。
/// - 价格转灰 —— 买不到的价格不该用玫红做促销刺激。
/// - 🔴 勾选框**恒未勾、不可点、不发请求**（PRD 11-05，取代 2026-09-18 的「如实反映 + 可点」）：
///   后端 3.1 起失效行对外 `selected` 恒 false、勾选失效行 422，失效行不参与结算。
/// - 两个出口：`Cari mirip`（去 Toko 看别的，优先级更高）与 `Hapus`。
/// 🔴 降权只罩「信息」，不罩「操作」：两个按钮不跟着压暗。
class _InvalidLine extends ConsumerStatefulWidget {
  const _InvalidLine({super.key, required this.line});

  final CartLine line;

  @override
  ConsumerState<_InvalidLine> createState() => _InvalidLineState();
}

class _InvalidLineState extends ConsumerState<_InvalidLine> {
  /// 复审修复：「Hapus」防重入 —— await 期间按钮 loading、不可点，双击只发一次 DELETE。
  bool _busy = false;

  Future<void> _remove(AppLocalizations l10n) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await ref.read(cartProvider.notifier).remove(widget.line.skuToken);
    } on CartMutationError {
      if (mounted) showAppToast(context, l10n.cartGenericError);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final line = widget.line;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 26),
            child: ShopCheckbox(
              key: ValueKey('cartInvalidLineCheckbox_${line.skuToken}'),
              value: false,
              enabled: false,
              onChanged: null,
              semanticLabel: l10n.cartSelectLine(line.productName ?? line.specName),
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          SizedBox(
            width: _kImage,
            height: _kImage,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(AppRounded.sm),
              child: Stack(
                children: [
                  Opacity(
                    opacity: .6,
                    child: ShopImage(url: line.mainImageUrl, size: _kImage, radius: AppRounded.sm),
                  ),
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 0,
                    child: Container(
                      key: ValueKey('cartInvalidReasonTag_${line.skuToken}'),
                      padding: const EdgeInsets.symmetric(vertical: 3, horizontal: 4),
                      color: ShopColors.ink.withValues(alpha: .78),
                      alignment: Alignment.center,
                      child: Text(_reasonShort(l10n, line.invalidReason),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: AppTypography.badge.copyWith(color: ShopColors.surface)),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.productName ?? line.specName,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: AppTypography.body.copyWith(
                        fontSize: 13, height: 1.35, fontWeight: FontWeight.w600,
                        color: ShopColors.text3)),
                const SizedBox(height: AppSpacing.xxs),
                Text(formatIdr(line.price),
                    style: AppTypography.body.copyWith(
                        fontWeight: FontWeight.w700,
                        fontFeatures: _tabular,
                        color: ShopColors.text4)),
                const SizedBox(height: AppSpacing.sm),
                Wrap(
                  spacing: AppSpacing.sm,
                  runSpacing: AppSpacing.xs,
                  children: [
                    ShopButton(
                      key: ValueKey('cartFindSimilar_${line.skuToken}'),
                      label: l10n.cartFindSimilar,
                      variant: ShopButtonVariant.outlinePurple,
                      dense: true,
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                      // 无「同类目替代品」端点 → 去 Toko 看别的（`/shop` 一律 go）。
                      onTap: () => context.go('/shop'),
                    ),
                    ShopButton(
                      key: ValueKey('cartRemoveInvalid_${line.skuToken}'),
                      label: l10n.cartRemoveShort,
                      variant: ShopButtonVariant.outlineMuted,
                      dense: true,
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                      loading: _busy,
                      onTap: _busy ? null : () => _remove(l10n),
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

  /// 短原因。🔴 认不出的原因照样算失效，只给一句**不承诺任何事**的通用文案。
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
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.all(AppSpacing.xxl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(title,
                textAlign: TextAlign.center,
                style: AppTypography.title.copyWith(color: ShopColors.text)),
            const SizedBox(height: AppSpacing.sm),
            Text(message,
                textAlign: TextAlign.center,
                style: AppTypography.body.copyWith(color: ShopColors.text2)),
            const SizedBox(height: AppSpacing.lg),
            ShopButton(label: cta, variant: ShopButtonVariant.purple, onTap: onTap),
          ],
        ),
      );
}
