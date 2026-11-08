/// 订单列表 —— **设计稿版式**（V1.4.0 · `02_screens_orders_refund.md` 屏 1）。
///
/// ⚠️ 2026-08-28：v1 版式已整体删除，本文件是该页唯一实现（`_v2` 后缀保留以免制造纯改名 diff）。
///
/// ## 🔴 这是**多业务共享**的订单壳（FR-54）
///
/// 电商只是第 5 类卡片。设计稿明写「卡片壳结构统一，**只有主体与操作按钮按类型变**」——
/// 所以 v2 的卡片壳对五类订单一视同仁，靠类型徽标与主体区分：
/// - `TOKO` 玫红徽标 = 实物，要等快递；
/// - `LAYANAN` 紫徽标 = 虚拟服务（问诊 / 解锁 / 充值），无物流。
///
/// 用户端**混排、按时间倒序、不分业务 Tab** —— 一个人不会按「这是问诊还是买东西」
/// 去找自己的订单，他只记得「前几天那一单」。
///
/// ## 一级 = 类型筛选；「Belanja」下二级 = 电商状态分组（v1.4.2 shop Story 9.1）
///
/// 通用 `GET /orders` 只支持 `type` 筛选，故一级标签仍是类型（Semua / Konsultasi / PawCoin / Belanja）。
/// 只有选中「Belanja」时出现二级标签 `Semua / Belum bayar / Dikemas / Dikirim / Selesai / Dibatalkan`，
/// 数据改走 6.2 的 `GET /me/shop-orders?group=`（服务端按 `displayStatus` 分组、游标分页 ——
/// 不在端上按状态过滤当前页，那会造出「这个 Tab 是空的」的假象）。卡片用 [ShopOrderCard]。
/// 「Semua」混排仍走 `/orders` + [OrderCardV2]，不受影响。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../shop/coupon/data/coupon_repository.dart';
import '../../shop/data/shop_order_repository.dart';
import '../../shop/domain/shop_order_detail.dart' show ShopPaymentFailure;
import '../../shop/domain/shop_order_summary.dart';
import '../../shop/domain/shop_product.dart' show formatIdr;
import '../../shop/presentation/shop_payment_launcher.dart';
import '../../shop/presentation/widgets/shop_buttons.dart';
import '../../shop/presentation/widgets/shop_decor.dart';
import '../../shop/presentation/widgets/shop_surface.dart';
import '../../support/presentation/support_whatsapp_button.dart';
import '../domain/order_summary.dart';
import 'order_l10n.dart';
import 'order_list_controller.dart';
import 'widgets/shop_order_card.dart';

class OrderListPageV2 extends ConsumerWidget {
  const OrderListPageV2({super.key});

  /// Tab 顺序 == 既有筛选顺序。null = 全部。
  static const List<OrderType?> _filters = [
    null,
    OrderType.vetConsult,
    OrderType.pawcoinTopup,
    OrderType.ecommerce,
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(orderListProvider);
    final notifier = ref.read(orderListProvider.notifier);
    // 加载中 state 无值 → 用控制器记住的筛选，标签不闪回「全部」。
    final current = async.value?.filter ?? notifier.currentFilter;
    final shopGroup = async.value?.shopGroup ?? notifier.currentShopGroup;
    final isShop = current == OrderType.ecommerce;

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(
        title: l10n.orderMyTitle,
        large: true,
        bottom: _FilterTabs(
          filters: _filters,
          current: current,
          onSelected: (f) => ref.read(orderListProvider.notifier).setFilter(f),
        ),
      ),
      body: Column(
        children: [
          // bug 20261006-582：PawCoin 页签只列**充值单**（订单中心收的是订单，不是钱包流水）。
          // 分享奖励 / 新手任务 / 消费等入账出账在 PawCoin 明细页（/me/pawcoin），这里给一条明确的入口，
          // 免得用户以为奖励没到账。
          if (current == OrderType.pawcoinTopup) _PawcoinHistoryBanner(l10n: l10n),
          // v1.4.2 shop Story 9.1：只有「Belanja」下才有二级分组与「我的券」入口。
          if (isShop) ...[
            _ShopGroupTabs(
              current: shopGroup,
              onSelected: (g) => ref.read(orderListProvider.notifier).setShopGroup(g),
            ),
            const _CouponEntryBar(),
          ],
          Expanded(child: _body(context, ref, l10n, async, isShop: isShop)),
        ],
      ),
    );
  }

  Widget _body(BuildContext context, WidgetRef ref, AppLocalizations l10n,
          AsyncValue<OrderListState> async, {required bool isShop}) =>
      async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        // F18：失败卡 + 「Coba lagi」（修线上「失败只有一行字、没有重试」）。
        error: (_, _) => _failed(l10n, ref),
        data: (state) => state.isEmpty
            ? _empty(context, l10n, isShop: state.isShop)
            : state.isShop
                ? _ShopOrderList(state: state)
                : RefreshIndicator(
                onRefresh: () => ref.read(orderListProvider.notifier).refresh(),
                child: ListView.builder(
                  padding: EdgeInsets.zero,
                  itemCount: state.items.length + (state.hasMore ? 1 : 0),
                  itemBuilder: (c, i) {
                    if (i >= state.items.length) {
                      return _loadMore(l10n, ref, state);
                    }
                    return OrderCardV2(order: state.items[i]);
                  },
                ),
              ),
      );

  Widget _loadMore(AppLocalizations l10n, WidgetRef ref, OrderListState state) =>
      orderLoadMoreTile(l10n, ref, state);

  /// F3 空态（`.empty`）：图标块 72×72 圆角 24 + 标题 + 副句；**只在「Belanja」下**加「Mulai belanja」。
  Widget _empty(BuildContext context, AppLocalizations l10n, {required bool isShop}) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            key: const ValueKey('orderEmptyState'),
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 72,
                height: 72,
                decoration: BoxDecoration(
                  color: AppColors.mintTint,
                  borderRadius: BorderRadius.circular(24),
                ),
                child: const Icon(Icons.receipt_long, size: 32, color: AppColors.mint),
              ),
              const SizedBox(height: 16),
              Text(l10n.orderEmpty, style: AppTypography.title),
              const SizedBox(height: 6),
              Text(l10n.orderEmptyHint, textAlign: TextAlign.center, style: AppTypography.caption),
              if (isShop) ...[
                const SizedBox(height: 20),
                ShopButton(
                  key: const ValueKey('orderStartShopping'),
                  label: l10n.shopOrderMulaiBelanja,
                  variant: ShopButtonVariant.pay,
                  onTap: () => context.go('/shop'),
                ),
              ],
            ],
          ),
        ),
      );

  /// F18 失败卡：`cloud_off` + 「Gagal memuat pesanan」+「Coba lagi」。
  Widget _failed(AppLocalizations l10n, WidgetRef ref) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            key: const ValueKey('orderLoadFailedState'),
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off, size: 40, color: AppColors.muted),
              const SizedBox(height: 12),
              Text(l10n.orderLoadFailed, textAlign: TextAlign.center, style: AppTypography.body),
              const SizedBox(height: 16),
              ShopButton(
                key: const ValueKey('orderLoadRetry'),
                label: l10n.commonRetry,
                variant: ShopButtonVariant.outlinePurple,
                onTap: () => ref.read(orderListProvider.notifier).refresh(),
              ),
            ],
          ),
        ),
      );
}

/// 列表底部「加载更多」行：失败时给「Coba lagi」（`loadMoreFailed` 此前有状态无 UI，9.1 补上）。
Widget orderLoadMoreTile(AppLocalizations l10n, WidgetRef ref, OrderListState state) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Center(
        child: state.loadMoreFailed
            ? ShopButton(
                key: const ValueKey('orderLoadMoreRetry'),
                label: l10n.commonRetry,
                variant: ShopButtonVariant.outlinePurple,
                dense: true,
                onTap: () => ref.read(orderListProvider.notifier).loadMore(),
              )
            : ShopButton(
                key: const ValueKey('orderLoadMoreV2'),
                label: l10n.orderLoadMore,
                variant: state.loadingMore ? ShopButtonVariant.disabled : ShopButtonVariant.outlineMuted,
                dense: true,
                loading: state.loadingMore,
                onTap: state.loadingMore ? null : () => ref.read(orderListProvider.notifier).loadMore(),
              ),
      ),
    );

/// 「Belanja」二级标签行（UI `.sub`）：项间距 24、左右 16、白底、底部 1px 分隔；
/// 选中 `mint600` w600 + 2px `mint` 下划线；「Belum bayar」「Dikirim」后接红色角标（counts，0 / 失败不显示）。
class _ShopGroupTabs extends ConsumerWidget {
  const _ShopGroupTabs({required this.current, required this.onSelected});

  final ShopOrderGroup current;
  final ValueChanged<ShopOrderGroup> onSelected;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final counts = ref.watch(shopOrderCountsProvider).value;
    final groups = <(ShopOrderGroup, String, int)>[
      (ShopOrderGroup.all, l10n.orderFilterAll, 0),
      (ShopOrderGroup.pendingPayment, l10n.shopOrderGroupUnpaid, counts?.pendingPayment ?? 0),
      (ShopOrderGroup.pendingShipment, l10n.shopOrderGroupPacking, 0),
      (ShopOrderGroup.toReceive, l10n.shopOrderGroupShipped, counts?.toReceive ?? 0),
      (ShopOrderGroup.completed, l10n.shopOrderStatusCompleted, 0),
      (ShopOrderGroup.cancelled, l10n.shopOrderStatusCancelled, 0),
    ];
    return Container(
      key: const ValueKey('shopOrderGroupTabs'),
      height: 44,
      decoration: const BoxDecoration(
        color: ShopColors.surface,
        border: Border(bottom: BorderSide(color: AppColors.line2)),
      ),
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        children: [
          for (final (g, label, n) in groups)
            Padding(
              padding: const EdgeInsets.only(right: 24),
              child: InkWell(
                key: ValueKey('shopOrderGroup_${g.api}'),
                onTap: () => onSelected(g),
                child: Container(
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    border: Border(
                      bottom: BorderSide(
                          color: g == current ? AppColors.mint : Colors.transparent, width: 2),
                    ),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(label,
                          style: AppTypography.caption.copyWith(
                              color: g == current ? AppColors.mint600 : AppColors.ink2,
                              fontWeight: g == current ? FontWeight.w600 : FontWeight.w400)),
                      if (n > 0) ...[
                        const SizedBox(width: 4),
                        Container(
                          key: ValueKey('shopOrderGroupBadge_${g.api}'),
                          constraints: const BoxConstraints(minWidth: 16),
                          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                          decoration: BoxDecoration(
                            color: AppColors.popRed,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Text(n > 99 ? '99+' : '$n',
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                  fontSize: 10, fontWeight: FontWeight.w600, color: Colors.white)),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// 「我的券」入口（只在「Belanja」下）：浅紫横条 → `/me/coupons`（8.1 已注册路由，9.3 换真页）。
/// 计数取 `/me/coupons/summary`；失败或 0 只显示「Kupon Saya」。
class _CouponEntryBar extends ConsumerWidget {
  const _CouponEntryBar();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final n = ref.watch(couponSummaryProvider).value ?? 0;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: Material(
        color: AppColors.mintTint,
        borderRadius: BorderRadius.circular(12),
        child: InkWell(
          key: const ValueKey('shopOrderCouponEntry'),
          borderRadius: BorderRadius.circular(12),
          onTap: () => context.push('/me/coupons'),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            child: Row(
              children: [
                const Icon(Icons.confirmation_number_outlined, size: 20, color: AppColors.mint),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(n > 0 ? l10n.shopOrderCouponEntry(n) : l10n.couponMyCoupons,
                      key: const ValueKey('shopOrderCouponEntryText'),
                      style: AppTypography.caption.copyWith(color: AppColors.ink)),
                ),
                const Icon(Icons.chevron_right, size: 20, color: AppColors.muted),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 「Belanja」下的电商订单列表：卡上「去支付」直接拉起支付面板、「确认收货」直接调接口（无弹窗）。
class _ShopOrderList extends ConsumerStatefulWidget {
  const _ShopOrderList({required this.state});

  final OrderListState state;

  @override
  ConsumerState<_ShopOrderList> createState() => _ShopOrderListState();
}

class _ShopOrderListState extends ConsumerState<_ShopOrderList> {
  /// 正在处理的卡（转圈 + 防重复点）。
  String? _busyToken;

  void _refreshLists() {
    ref.invalidate(orderListProvider);
    ref.invalidate(shopOrderCountsProvider);
  }

  /// F1：列表上下文先取详情（现金段 / 到期 / 展示号），再走 7.2 单一入口 [launchShopPayment]。
  Future<void> _pay(ShopOrderSummary s) async {
    if (_busyToken != null) return;
    final l10n = AppLocalizations.of(context);
    setState(() => _busyToken = s.orderToken);
    try {
      final detail = await ref.read(shopOrderRepositoryProvider).detail(s.orderToken);
      if (!mounted) return;
      final outcome = await launchShopPayment(context, ref, detail,
          isRetry: detail.paymentFailure == ShopPaymentFailure.gatewayDeclined);
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(s.orderToken));
      _refreshLists();
      if (outcome == ShopPaymentOutcome.paid || outcome == ShopPaymentOutcome.settledByCoin) {
        context.push('/shop/orders/${s.orderToken}/paid');
      }
    } catch (_) {
      if (mounted) {
        Analytics.capture('toko_order_payment_failed_shown');
        showAppToast(context, l10n.shopOrderPayFailed);
      }
    } finally {
      if (mounted) setState(() => _busyToken = null);
    }
  }

  /// 确认收货：**无二次确认**（PRD 11-05），埋点同详情页。
  Future<void> _confirm(ShopOrderSummary s) async {
    if (_busyToken != null) return;
    final l10n = AppLocalizations.of(context);
    Analytics.capture('toko_order_receipt_confirm_tapped');
    setState(() => _busyToken = s.orderToken);
    try {
      await ref.read(shopOrderRepositoryProvider).confirmReceipt(s.orderToken);
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(s.orderToken));
      _refreshLists();
      Analytics.capture('toko_order_receipt_confirm_succeeded');
      showAppToast(context, l10n.shopOrderReceiptConfirmed);
    } catch (e) {
      if (mounted) {
        showAppToast(
            context,
            isReturnInboundConfirmedError(e)
                ? l10n.shopOrderConfirmReceiptReturnInbound
                : l10n.shopOrderConfirmReceiptFailed);
      }
    } finally {
      if (mounted) setState(() => _busyToken = null);
    }
  }

  void _contactCs(ShopOrderSummary s) {
    // 列表项不下发 exceptionType（6.2 `ShopOrderListItem` 只有 bool）→ 'unknown'（待确认）；列表不报 _shown。
    Analytics.capture('toko_delivery_exception_cs_tapped', {'exception_type': 'unknown'});
    openSupportWhatsApp(context, ref, orderNo: s.shownNo, screen: 'order_list');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final state = widget.state;
    return RefreshIndicator(
      onRefresh: () async {
        ref.invalidate(shopOrderCountsProvider);
        await ref.read(orderListProvider.notifier).refresh();
      },
      child: ListView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.zero,
        itemCount: state.shopItems.length + (state.hasMore ? 1 : 0),
        itemBuilder: (c, i) {
          if (i >= state.shopItems.length) return orderLoadMoreTile(l10n, ref, state);
          final o = state.shopItems[i];
          return ShopOrderCard(
            order: o,
            busy: _busyToken == o.orderToken,
            onPay: () => _pay(o),
            onConfirmReceipt: () => _confirm(o),
            onContactCs: () => _contactCs(o),
            // 倒计时归零：按订单 token 只刷新一次（记在控制器里，列表重建也不重复 → 不会因为
            // 服务端还没把它挪出待付款而反复刷新；另一张单归零照样刷）。
            onCountdownExpired: () {
              if (!mounted) return;
              if (ref.read(orderListProvider.notifier).markExpiryRefreshed(o.orderToken)) {
                _refreshLists();
              }
            },
          );
        },
      ),
    );
  }
}

/// 顶栏之下的筛选 Tab 行（墨底，选中项 700 + 2px 玫红下边框）。
/// PawCoin 页签顶部的「去看 PawCoin 明细」入口（bug 20261006-582）。
class _PawcoinHistoryBanner extends StatelessWidget {
  const _PawcoinHistoryBanner({required this.l10n});

  final AppLocalizations l10n;

  @override
  Widget build(BuildContext context) => Material(
        color: ShopColors.surface,
        child: InkWell(
          key: const ValueKey('orderPawcoinHistoryLink'),
          onTap: () => context.push('/me/pawcoin'),
          child: ShopSection(
            child: Row(
              children: [
                Expanded(
                  child: Text(l10n.orderPawcoinHistoryHint, style: ShopText.body),
                ),
                const SizedBox(width: 8),
                Text(l10n.orderPawcoinHistoryLink,
                    style: ShopText.body.copyWith(color: ShopColors.accent, fontWeight: FontWeight.w600)),
                const Icon(Icons.chevron_right_rounded, size: 18, color: ShopColors.accent),
              ],
            ),
          ),
        ),
      );
}

class _FilterTabs extends StatelessWidget implements PreferredSizeWidget {
  const _FilterTabs({
    required this.filters,
    required this.current,
    required this.onSelected,
  });

  final List<OrderType?> filters;
  final OrderType? current;
  final ValueChanged<OrderType?> onSelected;

  @override
  Size get preferredSize => const Size.fromHeight(38);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SizedBox(
      height: 38,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge),
        children: [
          for (final f in filters)
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: InkWell(
                key: ValueKey('orderFilterV2_${f?.name ?? 'all'}'),
                onTap: () => onSelected(f),
                child: Container(
                  alignment: Alignment.center,
                  padding: const EdgeInsets.only(bottom: 8),
                  // 🔴 下划线**不能**再用 [ShopColors.accent]：它与顶栏 2026-09-03
                  //    换上的底色是同一个 #845EC9 —— 紫压紫，选中指示直接消失。
                  //    白色在紫底上 4.70:1，是这条顶栏上唯一读得出的指示色。
                  decoration: BoxDecoration(
                    border: Border(
                      bottom: BorderSide(
                        color: f == current ? ShopColors.surface : Colors.transparent,
                        width: 2,
                      ),
                    ),
                  ),
                  child: Text(
                    _label(l10n, f),
                    // 🔴 未选中态**不能**再用 onInk60（白 60%）：顶栏 2026-09-03 由墨底
                    //    换成品牌紫后，白 60% 压在 #845EC9 上只剩 2.77:1 —— 11.5px 的
                    //    小字要 4.5。纯白是 4.70:1，是这条紫底上唯一达标的白。
                    //    选中态改由**字重 + 玫红下划线**表达，不再靠颜色深浅。
                    style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: f == current ? FontWeight.w700 : FontWeight.w400,
                      color: ShopColors.surface,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  String _label(AppLocalizations l10n, OrderType? f) => switch (f) {
        null => l10n.orderFilterAll,
        OrderType.vetConsult => l10n.orderFilterKonsultasi,
        OrderType.pawcoinTopup => l10n.orderFilterPawcoin,
        OrderType.ecommerce => l10n.orderTypeEcommerce,
        _ => l10n.orderFilterOther,
      };
}

/// 订单卡（设计稿版式）。
///
/// 壳结构对五类订单完全一致：
/// ```
/// [类型徽标] [单号]                    [状态]
/// ────────────────────────────────────  ← 1px 分隔线
/// [52×52 图]  商品名                     Total 9.5px灰
///             元信息 10.5px灰            金额 14px/800
/// [次操作 描边]  [主操作 实色]           ← 最多 2 个
/// ```
///
/// 🔴 **操作条最多 2 个**（设计稿）：右侧主操作实色、左侧次操作描边。
/// 第三个及以后收进详情页，**列表不放溢出菜单** —— 一个列表页出现「⋯」菜单，
/// 就等于承认这一屏的信息架构没想清楚。
class OrderCardV2 extends StatelessWidget {
  const OrderCardV2({super.key, required this.order});

  final OrderSummary order;

  bool get _isShop => order.orderType == OrderType.ecommerce;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return InkWell(
      onTap: () => context.push(_isShop
          ? '/shop/orders/${order.orderToken}'
          : '/me/orders/${order.orderToken}'),
      child: ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _isShop
                    ? ShopBadge.toko(l10n.orderBadgeToko)
                    : ShopBadge.service(l10n.orderBadgeService),
                const SizedBox(width: 7),
                Expanded(
                  // 🔴 单号用等宽 —— 用户要报给客服、要逐位核对。
                  child: Text(order.displayNo,
                      maxLines: 1, overflow: TextOverflow.ellipsis, style: ShopText.serialNo),
                ),
                Text(orderStatusLabel(l10n, order.statusCode),
                    style: ShopText.badge
                        .copyWith(fontSize: 10.5, color: _statusColor())),
              ],
            ),
            const ShopDivider(margin: EdgeInsets.symmetric(vertical: 9)),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _leading(l10n),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(order.itemTitle ?? orderTypeLabel(l10n, order.orderType),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: ShopText.productNameCard),
                      if (_metaLine(l10n) != null) ...[
                        const SizedBox(height: 2),
                        Text(_metaLine(l10n)!, style: ShopText.meta),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(l10n.orderAmountLabel,
                        style: ShopText.meta.copyWith(fontSize: 9.5)),
                    Text(
                      // 🔴 金额可空（泛化/HD 预留）→ 显占位而不是 Rp 0。
                      order.amount == null
                          ? l10n.orderNoPayment
                          : formatIdr(order.amount!),
                      style: ShopText.badge.copyWith(
                          fontSize: 14, fontWeight: FontWeight.w800, color: _amountColor()),
                    ),
                  ],
                ),
              ],
            ),
            ..._actions(context, l10n),
          ],
        ),
      ),
    );
  }

  Widget _leading(AppLocalizations l10n) {
    if (_isShop) {
      return ShopImage(
          url: order.thumbnailUrl, size: 52, radius: ShopShape.radiusField);
    }
    // 虚拟服务：紫底图标块（设计稿）—— 它本就没有商品图，不是「暂时缺图」。
    return Container(
      width: 52,
      height: 52,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: ShopColors.purpleTagBg,
        borderRadius: BorderRadius.circular(ShopShape.radiusField),
      ),
      child: const Icon(Icons.receipt_long_outlined, size: 22, color: ShopColors.purple),
    );
  }

  String? _metaLine(AppLocalizations l10n) {
    final n = order.itemCount;
    if (n != null && n > 1) return l10n.orderCardMoreItems(n - 1);
    if (order.createdAt != null) {
      final d = order.createdAt!.toLocal();
      String two(int v) => v.toString().padLeft(2, '0');
      return '${two(d.day)}/${two(d.month)}';
    }
    return null;
  }

  /// 🔴 <b>金额颜色按「还需不需要付钱」定</b>（三色分工）：
  /// 待支付玫红、其余墨色。已付的钱是信息，不是行动召唤。
  Color _amountColor() => _awaitingPayment ? ShopColors.accent : ShopColors.ink;

  /// 🔴 待支付判定**必须覆盖两个状态码空间**：虚拟单是 `PENDING`，
  /// 电商单是 `PENDING_PAYMENT`（见 [orderStatusLabel] 的注释「同名空间」）。
  /// 只判 `PENDING` 会让电商待支付单既拿不到玫红金额、也拿不到 `Bayar Sekarang` ——
  /// 2026-08-19 上机抓到：列表里那张待支付单一个按钮都没有。
  bool get _awaitingPayment =>
      order.statusCode == 'PENDING' || order.statusCode == 'PENDING_PAYMENT';

  /// 状态文字色。
  ///
  /// 🔴 <b>四个状态必须落在四个可分辨的值上</b>（2026-08-27 修）。原映射是
  /// `warn → accent`、`info → purple`、`success/unknown → text3` —— 而 2026-08-21
  /// `accent` 改成品牌紫之后它与 `purple` **同值**，于是四个语义塌成两个颜色，
  /// 且塌在一起的两对恰好意思不同（「等你付钱」vs「我们在处理」、「已完成」vs「状态未知」）。
  /// 颜色这条通道当时等于没在传信息。
  ///
  /// 现在按**需不需要你动手**排序，由重到轻：
  /// 紫（等你行动）> 深墨（平台处理中）> 中灰（已完成，不用管）> 浅灰（状态未知）。
  /// 四个值在白底上依次是 4.75 / 8.44 / 5.05 / 3.52，都还读得出来。
  Color _statusColor() => switch (order.statusColor) {
        OrderStatusColor.warn => ShopColors.accent,
        OrderStatusColor.info => ShopColors.text2,
        OrderStatusColor.success => ShopColors.text3,
        OrderStatusColor.unknown => ShopColors.text4,
      };

  /// 操作条。
  ///
  /// ⚠️ 只渲染**能兑现**的动作。设计稿还画了 `Lacak` / `Barang Diterima` /
  /// `Ajukan Kembali` / `Beli Lagi`，但列表接口给的是订单中心的粗粒度状态
  /// （`PENDING` / `IN_PROGRESS` / `COMPLETED`），**分不出「待发货」与「已发货」**；
  /// `Beli Lagi` 还需要商品 token，列表里也没有。
  /// 在分不清状态时给出这些按钮，点下去会落到一个做不了该动作的页面 ——
  /// 那比少一个按钮糟得多。补齐需要列表接口下发电商子状态与首个商品 token。
  List<Widget> _actions(BuildContext context, AppLocalizations l10n) {
    if (!_awaitingPayment) return const [];
    return [
      const SizedBox(height: 10),
      Row(
        children: [
          const Spacer(),
          ShopButton(
            key: ValueKey('orderPayNowV2_${order.orderToken}'),
            label: l10n.orderPayNow,
            variant: ShopButtonVariant.pay,
            dense: true,
            // 跳详情页而不是就地支付：那里有倒计时、支付构成与取消入口，
            // 就地拉起支付会把「还剩多久」这个关键信息藏起来。
            onTap: () => context.push(_isShop
                ? '/shop/orders/${order.orderToken}'
                : '/me/orders/${order.orderToken}'),
          ),
        ],
      ),
    ];
  }
}
