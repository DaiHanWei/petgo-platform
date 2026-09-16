/// 购物车 —— **设计稿版式**（V1.4.0 · `01_screens_browse_order.md` 屏 5）。
///
/// ⚠️ 2026-08-28：v1 版式已整体删除，本文件是该页唯一实现（`_v2` 后缀保留以免制造纯改名 diff）。
///
/// ## 🔴 单店模型：没有店铺分组
///
/// 平台自营是唯一卖家（设计稿关键原则 1），因此**无店铺分组行、无分店铺小计、
/// 无分店铺凑单**，整车只有一个总计。照搬第三方电商的多店铺分组只会凭空多一层。
///
/// ## 每行勾选框 —— 已由 4-1 + 4-2 补齐（V1.3.0）
///
/// 这里曾经有一段注释说明「设计稿的每行勾选框刻意不实现」，理由是下单接口
/// `placeOrder(addressToken)` 整车下单、服务端没有行选择的概念，而
/// **画一个不影响下单的勾选框是「能造成资损的谎」**。
///
/// V1.3.0 把这个接口能力缺口补上了：
/// - **4-1（后端）**：`shop_cart_items.selected` 列 + 两个选择端点；
///   `GET /checkout` 与 `POST /shop-orders` 只结算「勾选且有效」的行。
/// - **4-2（本文件）**：每个有效行一个勾选框、底栏左侧「全选」、
///   底栏金额与件数改读 `selectedSubtotal` / `selectedCount`。
///
/// 🔴 当年那三条不能走的路，现在仍然不能走，落到本文件是三条硬约束：
/// - **金额只认后端下发的 `selectedSubtotal`**，前端绝不自己把选中行加起来 ——
///   两套实现只要有一处对失效行的口径不同，用户看到的数就和实际扣的钱不一样。
/// - **取消勾选只调选择端点，绝不调 `remove`/`DELETE`** ——
///   用破坏性操作模拟查询语义，取消结算就丢数据。
/// - **失效行的勾选框是禁用而非隐藏**，判定用 `line.isValid`（不是枚举白名单）。
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
import 'widgets/shop_dialog.dart';
import 'widgets/shop_pressable.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_surface.dart';

/// 🔴 <b>改为有状态只为一件事：页面曝光埋点</b>（2026-08-28 补）。
///
/// `toko_cart_page_viewed` 原本只在 v1 的 CartPage 里上报。v2 自 2026-08-21 成为默认版式后
/// 这个指标其实**已经空了一周多** —— 只是 v1 代码还在，埋点清单对账测试就一直是绿的，
/// 直到 v1 整体删除才暴露出来。
/// ⚠️ 必须在 initState 而不是 build 里报：build 会因 provider 变化被反复调用，
/// 那样一次浏览会上报很多条，页面浏览量直接失真。
class CartPageV2 extends ConsumerStatefulWidget {
  const CartPageV2({super.key});

  @override
  ConsumerState<CartPageV2> createState() => _CartPageV2State();
}

class _CartPageV2State extends ConsumerState<CartPageV2> {
  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_cart_page_viewed');
  }

  @override
  Widget build(BuildContext context) {
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
              error: (_, _) => ShopRetryState(
                message: l10n.cartLoadFailed,
                retryLabel: l10n.commonRetry,
                onRetry: () => ref.invalidate(cartProvider),
              ),
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
    showAppToast(
      context,
      l10n.cartRemovedUndo(name),
      duration: const Duration(seconds: 5),
      actionLabel: l10n.cartUndo,
      onAction: () => _undoRemove(l10n, skuToken: skuToken, qty: qty),
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
  Future<void> _undoRemove(AppLocalizations l10n,
      {required String skuToken, required int qty}) async {
    try {
      await ref
          .read(cartProvider.notifier)
          .add(skuToken, qty: qty, entrySource: 'CART_UNDO');
    } on CartMutationError catch (e) {
      if (mounted) {
        showAppToast(context,
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

  Widget _list(
      BuildContext context, WidgetRef ref, AppLocalizations l10n, CartView cart) {
    return ListView(
      padding: EdgeInsets.zero,
      children: [
        for (final line in cart.lines)
          _ValidLine(key: ValueKey(line.skuToken), line: line, onRemoved: _offerUndo),
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
            // 🔴 批量删除不可撤销，先确认（2026-08-27）。此前点一下整组直接消失。
            //    padding 由 4/6 提到 8/12：原命中区约 30×23，够不到 44。
            ShopPressable(
              key: const ValueKey('cartClearInvalidV2'),
              onTap: () async {
                final ok = await showShopConfirm(
                  context,
                  dialogKey: const ValueKey('cartClearInvalidConfirmV2'),
                  confirmKey: const ValueKey('cartClearInvalidConfirmYesV2'),
                  title: l10n.cartClearInvalidConfirmTitle,
                  body: l10n.cartClearInvalidConfirmBody,
                  confirmLabel: l10n.cartClearInvalidConfirmYes,
                  cancelLabel: l10n.commonCancel,
                );
                if (!ok) return;
                Analytics.capture('toko_cart_clear_invalid_tapped');
                await ref.read(cartProvider.notifier).clearInvalid();
              },
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 12),
                child: Text(l10n.cartClearInvalid,
                    style: ShopText.badge
                        .copyWith(fontSize: 10.5, color: ShopColors.purple)),
              ),
            ),
          ],
        ),
      );

  /// 底栏。
  ///
  /// 🔴 <b>金额与件数只来自 `selectedSubtotal` / `selectedCount`</b>（Story 4-2 AC2）——
  /// 本文件里不存在任何对选中行求和的代码。前端求一遍、后端 `CheckoutService` 求一遍
  /// 是两套独立实现，只要有一处口径不同，用户看到的数就和实际扣的钱不一样。
  ///
  /// 🔴 <b>一件没勾时结算按钮禁用，且不发任何请求</b>（AC4）：
  /// 「点了才由后端 422 告诉你」是把一次本可以在端上说清的事，换成一次失败的往返。
  Widget _bottomBar(BuildContext context, AppLocalizations l10n, CartView cart) {
    final nothingSelected = cart.selectedCount == 0;
    return ShopBottomBarWithTotal(
      // 全选控件。🔴 选中态判定**排除失效行**（见 CartView.allValidSelected）——
      //    车里有一个永远选不上的失效行时，它必须仍能显示成「已全选」，否则永远点不亮。
      leading: _SelectAllToggle(cart: cart),
      // 🔴 「n barang」是**件数**不是种类数（FR-96）—— 要跟用户脑子里的
      //    「我买了几件」对上。改读 selectedCount：底栏说的是「这一单买几件」。
      label: nothingSelected
          ? l10n.cartSelectNoneHint
          : l10n.cartTotalLabel(cart.selectedCount),
      amount: formatIdr(cart.selectedSubtotal),
      // 一件没勾时金额转灰：玫红是「待付款」的颜色，而此刻没有任何东西待付。
      amountColor: nothingSelected ? ShopColors.text4 : ShopColors.accent,
      action: ShopButton(
        key: const ValueKey('cartCheckoutV2'),
        label: l10n.cartCheckout,
        variant: ShopButtonVariant.pay,
        padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
        onTap: nothingSelected ? null : () => context.push('/shop/checkout'),
      ),
    );
  }

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

}

/// 底栏左侧的「全选」控件（Story 4-2 AC1）。
///
/// 🔴 <b>选中态判定排除失效行</b>：读的是 [CartView.allValidSelected]，也就是
/// 「全部**有效**行都已勾选」。若把失效行算进去，车里只要有一件下架商品，
/// 这个框就永远点不亮 —— 用户会以为控件坏了，而他其实什么都没做错。
///
/// 🔴 点击语义是「全选 / 全不选」，作用于**车内全部行（含失效行）**，与后端一致：
/// 失效行的 `selected` 照实记着，只是永不计入合计。这样用户在商品补货后，
/// 会发现自己当初点过的全选确实生效了。
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
          key: const ValueKey('cartSelectAllV2'),
          value: all,
          onChanged: _busy ? null : (v) => _toggle(l10n, v),
          semanticLabel: l10n.cartSelectAll,
        ),
        const SizedBox(width: 4),
        Text(l10n.cartSelectAll, style: ShopText.meta.copyWith(fontSize: 9.5)),
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

/// 可购商品行。
class _ValidLine extends ConsumerStatefulWidget {
  const _ValidLine({super.key, required this.line, required this.onRemoved});

  final CartLine line;

  /// 删成功后回调**页面**去弹撤销条。
  ///
  /// 🔴 撤销不能由本行自己管：删掉的那一瞬间这一行就从列表里没了、State 变 defunct，
  /// 而撤销条要在它死后再活 5 秒。详见 [_CartPageV2State._offerUndo]。
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

    return ShopSection(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 🔴 勾选框在最左，与失效行的占位对齐 —— 两组的左边缘不能参差。
          Padding(
            padding: const EdgeInsets.only(top: 22),
            child: ShopCheckbox(
              key: ValueKey('cartLineCheckbox_${line.skuToken}'),
              value: line.selected,
              onChanged: _busy ? null : (v) => _setSelected(l10n, line, v),
              semanticLabel:
                  l10n.cartSelectLine(line.productName ?? line.specName),
            ),
          ),
          const SizedBox(width: 9),
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
                      // 🔴 触底再减 = 删除整行（2026-08-27 补回）。此前 v2 的有效行
                      //    **没有任何删除入口**，而 v2 自 2026-08-21 起是默认变体 ——
                      //    加错东西的用户只能整单买下或放弃结算。v1 一直有这个能力
                      //    （cart_page.dart 的 `_qtyStepper`），改版时漏了。
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

  /// 删除整行。走的是失效行 `Hapus` 用的同一个 `remove()`，不是新增能力。
  ///
  /// 🔴 **删完必须给 undo**（R-3，2026-09-02 产品拍板）。
  /// 风险不在「删除」本身，在**按钮位复用**：[ShopStepper] 在 `value <= min` 时
  /// 把同一个位置的「−」换成垃圾桶。用户连点减号往下收数量，**最后一下必然落在
  /// 已经变成垃圾桶的同一坐标上** —— 这不是手滑，是控件设计决定的必然结果。
  ///
  /// ⚠️ **不用二次确认弹窗**：那会拖慢正常的收数量操作（每次减到 1 都弹一次），
  /// 而误删的代价用 undo 完全覆盖得住。产品明确否掉了确认框这条路。
  Future<void> _remove(AppLocalizations l10n, CartLine line) async {
    setState(() => _busy = true);
    // 🔴 删之前把这一行记下来：删完服务端就没有它了，undo 只能靠这份快照重建。
    final skuToken = line.skuToken;
    final qty = line.qty;
    final name = line.productName ?? line.specName;
    // 🔴 回调必须在 await **之前**捕获：remove 一成功，列表就少了这一行、
    //    本 State 立刻 defunct，那之后再读 `widget.onRemoved` 是在碰一个已死对象。
    final offerUndo = widget.onRemoved;
    try {
      await ref.read(cartProvider.notifier).remove(skuToken);
      // ⚠️ 这里**不判 mounted**：删成功后本行本就该消失，判了等于撤销条永远不弹。
      //    回调指向的是页面（还活着），不是本行。
      offerUndo(skuToken: skuToken, qty: qty, name: name);
    } on CartMutationError {
      if (mounted) showAppToast(context, l10n.cartGenericError);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 勾选 / 取消勾选本行。
  ///
  /// 🔴 <b>绝不调 `remove`</b>（AC5）：取消勾选是「这次不买」，不是「不要了」。
  /// 商品仍在列表里、数量不变，下单后也仍留在购物车（由 4-1 的清车范围保证）。
  ///
  /// 🔴 <b>不做乐观更新</b>：勾选是服务端状态，直接用端点返回的整份 CartView 覆盖
  /// （见 `CartController.setSelected`）。先在本地勾上再发请求，一旦失败就会留下
  /// 「看着勾上了其实没勾上」—— 而底栏金额按服务端的 selectedSubtotal 显示，
  /// 两者会当场对不上。
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

/// 失效商品行。
///
/// 信息部分 `opacity: .75`；图上盖蒙层；价格转灰；**不参与合计**。
///
/// 🔴 <b>降权只罩「信息」，不罩「操作」</b>（2026-08-27）：原先整行 `.75` 把两个按钮
/// 一起压暗，`outlineMuted` 的字色因此掉到约 2.8:1 —— 一个本来就低对比的次按钮被再乘
/// 一次。降权要表达的是「这些东西买不到」，不是「这两个出口也不太好用」。
/// 两个出口：`Cari mirip`（紫描边，**优先级更高**）与 `Hapus`（浅描边）——
/// 🔴 失效不等于流失：一件买不到的东西，用户真正想要的是「有没有别的」。
class _InvalidLine extends ConsumerWidget {
  const _InvalidLine({required this.line});

  final CartLine line;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return ShopSection(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 🔴 <b>禁用而不是隐藏</b>（AC3）：位置对齐才不会让列表左边缘参差。
          //    判定用 `line.isValid`（本组行恒为 false），**不是枚举白名单** ——
          //    Epic 6 加第三种失效原因时，未知值会落进 `unavailable`，
          //    `isValid` 自动为 false；写成 `reason == delisted || reason == outOfStock`
          //    则会让停用品类的行变成「可勾选、可结算、然后被后端 422 打回」。
          const Padding(
            padding: EdgeInsets.only(top: 22),
            child: ShopCheckbox(value: false, enabled: false, onChanged: null),
          ),
          const SizedBox(width: 9),
          Opacity(
            opacity: .75,
            child: SizedBox(
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
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Opacity(
                  opacity: .75,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(line.productName ?? line.specName,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style:
                              ShopText.productNameCard.copyWith(color: ShopColors.text3)),
                      const SizedBox(height: 2),
                      // 🔴 价格转灰 —— 买不到的价格不该用玫红做促销刺激（三色分工）。
                      Text(formatIdr(line.price),
                          style: ShopText.priceRail.copyWith(color: ShopColors.text4)),
                    ],
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    ShopButton(
                      key: ValueKey('cartFindSimilar_${line.skuToken}'),
                      label: l10n.cartFindSimilar,
                      variant: ShopButtonVariant.outlinePurple,
                      dense: true,
                      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 8),
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
                      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 8),
                      onTap: () => ref.read(cartProvider.notifier).remove(line.skuToken),
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
