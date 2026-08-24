/// 订单列表 —— **设计稿版式**（V1.4.0 · `02_screens_orders_refund.md` 屏 1）。
///
/// 与 [OrderListPage]（v1 版式）并存，由 `shopUiVariantProvider` 二选一。
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
/// ## ⚠️ 设计稿的状态 Tab 未实现，用的是既有的**类型**筛选
///
/// 设计稿画的是 `Semua / Belum bayar / Dikirim / Selesai`（按状态）。
/// 但列表接口 `GET /orders` **只支持 `type` 筛选，没有状态筛选**，
/// 而列表是游标分页的 —— 在端上按状态过滤当前页会造出「这个 Tab 是空的」的假象，
/// 而真相是符合条件的订单在下一页。那比不做这个 Tab 更糟。
///
/// 故此处保留设计稿的 Tab 行**样式**，承载既有的类型筛选（Semua / Konsultasi /
/// PawCoin / Lainnya）。要做状态 Tab 需要后端加筛选参数。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../shop/domain/shop_product.dart' show formatIdr;
import '../../shop/presentation/widgets/shop_buttons.dart';
import '../../shop/presentation/widgets/shop_decor.dart';
import '../../shop/presentation/widgets/shop_surface.dart';
import '../domain/order_summary.dart';
import 'order_l10n.dart';
import 'order_list_controller.dart';

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
    final current = async.maybeWhen(data: (s) => s.filter, orElse: () => null);

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
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => _hint(l10n.orderLoadFailed),
        data: (state) => state.items.isEmpty
            ? _empty(l10n)
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
      ),
    );
  }

  Widget _loadMore(AppLocalizations l10n, WidgetRef ref, OrderListState state) => ShopSection(
        child: Center(
          child: ShopButton(
            key: const ValueKey('orderLoadMoreV2'),
            label: state.loadingMore ? l10n.orderLoadMore : l10n.orderLoadMore,
            variant: state.loadingMore
                ? ShopButtonVariant.disabled
                : ShopButtonVariant.outlineMuted,
            dense: true,
            onTap: state.loadingMore
                ? null
                : () => ref.read(orderListProvider.notifier).loadMore(),
          ),
        ),
      );

  Widget _empty(AppLocalizations l10n) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(l10n.orderEmpty, style: ShopText.sectionTitle),
              const SizedBox(height: 6),
              Text(l10n.orderEmptyHint,
                  textAlign: TextAlign.center, style: ShopText.body),
            ],
          ),
        ),
      );

  Widget _hint(String text) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Text(text, textAlign: TextAlign.center, style: ShopText.body),
        ),
      );
}

/// 顶栏之下的筛选 Tab 行（墨底，选中项 700 + 2px 玫红下边框）。
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
                  decoration: BoxDecoration(
                    border: Border(
                      bottom: BorderSide(
                        color: f == current ? ShopColors.accent : Colors.transparent,
                        width: 2,
                      ),
                    ),
                  ),
                  child: Text(
                    _label(l10n, f),
                    style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: f == current ? FontWeight.w700 : FontWeight.w400,
                      color: f == current ? ShopColors.surface : ShopColors.onInk60,
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

  Color _statusColor() => switch (order.statusColor) {
        OrderStatusColor.warn => ShopColors.accent,
        OrderStatusColor.info => ShopColors.purple,
        OrderStatusColor.success => ShopColors.text3,
        OrderStatusColor.unknown => ShopColors.text3,
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
