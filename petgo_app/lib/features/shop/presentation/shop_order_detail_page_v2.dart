/// 电商订单详情 —— **设计稿版式**（V1.4.0 · `02_screens_orders_refund.md` 屏 2 待支付 / 屏 3 已发货）。
///
/// ⚠️ 2026-08-28：v1 版式已整体删除，本文件是该页唯一实现（`_v2` 后缀保留以免制造纯改名 diff）。
///
/// ## 🔴 四条硬规则（版式换了，规则一条不改）
///
/// 1. **倒计时由服务端下发到期时刻，前端只渲染**（`ShopOrderDetail.expiresAt`）。
///    切后台回来重算、不本地累加 —— 由 [ShopCountdown] 负责，见那个组件的说明。
/// 2. **PawCoin 已冻结必须明示**：下单即冻结抵扣额，文案要说清「取消 → 自动退回」。
///    不说的话用户会以为币已经花掉了，取消订单时会来客服问「我的币呢」。
/// 3. **已发货态不显示倒计时、不显示玫红价格**：玫红只留给「还需要付钱」的动作，
///    已付的钱降为信息层（墨色）。
/// 4. **支付构成须保留 PawCoin 分段**（`Rp 154.000 + 50.000 PawCoin`）——
///    它是退款拆分的用户侧依据，任何已支付订单页都必须显示。
///
/// ## 🔴 物流时间线的免责行不可省
///
/// 物流是轻量实现，**不接承运商 API**，时间线由后台人工更新。设计稿因此要求在时间线
/// 底部明示「非自动追踪」——不写的话用户会按大盘电商的实时精度来预期，
/// 状态一滞后就是投诉。这条在本页是 [_timelineBlock] 的最后一行，**别删**。
///
/// ## 设计稿要求但当前无数据源
///
/// | 设计元素 | 缺什么 | 降级 |
/// |---|---|---|
/// | 多条物流时间线 | 包裹只有 `shippedAt` / `deliveredAt` 两个时刻 | 用这两条渲染，不编中间节点 |
/// | `Perkiraan tiba 20 Agu` 预计送达 | 无该字段 | 不显示（**不猜日期**） |
/// | `Bantuan ›` 客服工单入口 | 发货态无对应路由 | 只做告知，不给点不动的箭头 |
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/cart_repository.dart';
import '../data/shop_order_repository.dart';
import '../data/shop_return_repository.dart';
import '../domain/shop_order_detail.dart';
import '../domain/shop_product.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_countdown.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_dialog.dart';
import 'widgets/shop_pressable.dart';
import 'widgets/shop_surface.dart';

class ShopOrderDetailPageV2 extends ConsumerStatefulWidget {
  const ShopOrderDetailPageV2({super.key, required this.orderToken});

  final String orderToken;

  @override
  ConsumerState<ShopOrderDetailPageV2> createState() => _ShopOrderDetailPageV2State();
}

/// 正在进行中的动作。用来把转圈画在**被点的那个按钮**上 ——
/// 共用一个 bool 的话，点「取消」会让旁边的「支付」转圈。
enum _OrderAction { pay, cancel, receipt }

class _ShopOrderDetailPageV2State extends ConsumerState<ShopOrderDetailPageV2> {
  _OrderAction? _busyAction;

  bool get _busy => _busyAction != null;

  /// 倒计时归零后只刷新一次 —— 归零时刻页面还在，得让服务端告诉我们它真的关单了。
  bool _refreshedOnExpiry = false;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_order_detail_viewed');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(shopOrderDetailProvider(widget.orderToken));

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.shopOrderTitle),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => ShopRetryState(
          message: l10n.shopOrderLoadFailed,
          retryLabel: l10n.commonRetry,
          onRetry: () => ref.invalidate(shopOrderDetailProvider(widget.orderToken)),
        ),
        data: (order) => RefreshIndicator(
          // 履约状态由运营在后台推进 —— 页面自己不会知道，得让用户能主动拉。
          onRefresh: () => ref.refresh(shopOrderDetailProvider(widget.orderToken).future),
          child: _content(l10n, order),
        ),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (order) => _bottomBar(l10n, order),
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ShopOrderDetail order) {
    final pending = order.status.isPendingPayment;
    return ListView(
      padding: EdgeInsets.zero,
      children: [
        if (pending) _countdownBlock(l10n, order),
        if (order.status == ShopOrderStatus.shipped ||
            order.status == ShopOrderStatus.delivered) ...[
          _fulfillmentBlock(l10n, order),
          _timelineBlock(l10n, order),
        ],
        _paymentBlock(l10n, order),
        _itemsBlock(l10n, order),
        _shipToBlock(l10n, order),
        _metaBlock(l10n, order),
        if (order.status.canConfirmReceipt || order.status == ShopOrderStatus.completed)
          _helpBlock(l10n),
        const SizedBox(height: kShopGutter),
      ],
    );
  }

  // ---------------------------------------------------------------- 倒计时

  /// 倒计时块 —— **页面第一屏内容**（设计稿），满宽玫红，居中。
  ///
  /// 过期后整块转灰、文案换成「已自动取消」，且底部条不再给支付入口。
  Widget _countdownBlock(AppLocalizations l10n, ShopOrderDetail order) {
    final expiresAt = order.expiresAt;
    if (expiresAt == null) return const SizedBox.shrink();
    final expired = !expiresAt.isAfter(DateTime.now().toUtc());

    return Container(
      width: double.infinity,
      color: expired ? ShopColors.border2 : ShopColors.accent,
      padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge, vertical: 14),
      child: Column(
        children: [
          Text(
            expired ? l10n.shopOrderExpiredNotice : l10n.shopOrderCountdownTitle,
            textAlign: TextAlign.center,
            style: ShopText.body.copyWith(
                fontSize: 11,
                // 过期块的底是 border2(#EFECF7)，text3 在它上面只有 4.33:1；
                // 「订单已自动取消」是状态信息不是装饰，改用 text2（7.5:1）。
                color: expired ? ShopColors.text2 : ShopColors.onInk85),
          ),
          if (!expired) ...[
            const SizedBox(height: 2),
            ShopCountdown(
              key: const ValueKey('shopOrderCountdownV2'),
              expiresAt: expiresAt,
              style: ShopText.countdownHero,
              // 归零 → 拉一次服务端，由它把状态推到「已过期」。
              // 🔴 前端不自行把状态改成过期：关单是服务端的事，端上只是显示。
              onExpired: () {
                if (_refreshedOnExpiry || !mounted) return;
                _refreshedOnExpiry = true;
                ref.invalidate(shopOrderDetailProvider(widget.orderToken));
              },
            ),
            const SizedBox(height: 2),
            Text(l10n.shopOrderCountdownHint,
                textAlign: TextAlign.center,
                style: ShopText.meta.copyWith(color: ShopColors.onInk60)),
          ],
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 物流

  Widget _fulfillmentBlock(AppLocalizations l10n, ShopOrderDetail order) {
    final pkg = order.packages.isEmpty ? null : order.packages.first;
    if (pkg == null) return const SizedBox.shrink();
    return ShopSection(
      gutter: false,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(l10n.shopOrderShippedNow,
                    style: ShopText.sectionTitle
                        .copyWith(fontSize: 13, color: ShopColors.purple)),
              ),
              ShopPressable(
                key: const ValueKey('shopOrderCopyResiV2'),
                onTap: () => _copyTracking(l10n, pkg),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 12),
                  child: Text(l10n.shopOrderCopy,
                      style: ShopText.badge
                          .copyWith(fontSize: 10.5, color: ShopColors.purple)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 9),
          // 🔴 中性色条：物流与钱无关，不能借用 PawCoin 的紫（2026-08-27 修）。
          ShopLeftAccentBlock.neutral(
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(pkg.carrierName, style: ShopText.meta),
                      Text(pkg.trackingNo,
                          // 🔴 运单号用等宽 —— 用户要逐位核对，比例字体下 1/l/I 分不清。
                          style: const TextStyle(
                              fontFamily: ShopText.mono,
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                              color: ShopColors.text)),
                    ],
                  ),
                ),
                if (pkg.trackingUrl.isNotEmpty)
                  ShopPressable(
                    key: const ValueKey('shopOrderTrackSiteV2'),
                    onTap: () => _openCarrierSite(l10n, pkg),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 12),
                      child: Text('${l10n.shopOrderTrackOnCarrierSite} ›',
                          style: ShopText.badge
                              .copyWith(fontSize: 11, color: ShopColors.purple)),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 物流时间线。
  ///
  /// 🔴 **末尾的免责行不可省**（见文件头）。设计稿把它列为必须项，因为物流不接
  /// 承运商 API —— 时间线是后台人工更新的，不明示就会被按实时追踪的精度预期。
  Widget _timelineBlock(AppLocalizations l10n, ShopOrderDetail order) {
    final pkg = order.packages.isEmpty ? null : order.packages.first;
    // 只有两个已知时刻；没有任何一个就整块不渲染（不画一条空时间线）。
    final events = <(String, DateTime)>[
      if (pkg?.deliveredAt != null) (l10n.shopOrderPackageDelivered, pkg!.deliveredAt!),
      if (pkg?.shippedAt != null) (l10n.shopOrderPackageInTransit, pkg!.shippedAt!),
    ];
    if (events.isEmpty) return const SizedBox.shrink();

    return ShopSection(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.shopOrderTimelineTitle,
              style: ShopText.sectionTitle.copyWith(fontSize: 12)),
          const SizedBox(height: 10),
          for (var i = 0; i < events.length; i++)
            _timelineRow(events[i].$1, events[i].$2, current: i == 0, last: i == events.length - 1),
          const ShopDivider(margin: EdgeInsets.only(top: 6, bottom: 8)),
          Text(l10n.shopOrderManualTrackingNotice,
              key: const ValueKey('shopOrderManualTrackingNoticeV2'),
              style: ShopText.meta.copyWith(color: ShopColors.text4)),
        ],
      ),
    );
  }

  Widget _timelineRow(String label, DateTime at, {required bool current, required bool last}) =>
      IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 16,
              child: Column(
                children: [
                  Container(
                    width: 9,
                    height: 9,
                    margin: const EdgeInsets.only(top: 4),
                    decoration: BoxDecoration(
                      color: current ? ShopColors.purple : ShopColors.border,
                      shape: BoxShape.circle,
                    ),
                  ),
                  if (!last)
                    Expanded(
                      child: Container(width: 1.5, color: ShopColors.border),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Padding(
                padding: EdgeInsets.only(bottom: last ? 0 : 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(label,
                        style: ShopText.cardTitle.copyWith(
                            fontSize: 11.5,
                            fontWeight: current ? FontWeight.w600 : FontWeight.w400,
                            color: current ? ShopColors.text : ShopColors.text2)),
                    Text(formatDayMonthTime(context, at.toLocal()), style: ShopText.meta),
                  ],
                ),
              ),
            ),
          ],
        ),
      );

  // ---------------------------------------------------------------- 支付构成

  /// 支付方式块。
  ///
  /// 待支付：状态陈述（PawCoin 已冻结 / QRIS 等待中）
  /// 已支付：**保留 PawCoin 分段**，它是退款拆分的用户侧依据。
  Widget _paymentBlock(AppLocalizations l10n, ShopOrderDetail order) {
    final coin = order.coinAmount ?? 0;
    final cash = order.cashAmount ?? 0;
    final pending = order.status.isPendingPayment;
    if (coin == 0 && cash == 0) return const SizedBox.shrink();

    return ShopSection(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.checkoutPaymentSection,
              style: ShopText.sectionTitle.copyWith(fontSize: 12)),
          const SizedBox(height: 9),
          if (coin > 0) ...[
            ShopLeftAccentBlock.pawcoin(
              key: const ValueKey('shopOrderCoinRowV2'),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(l10n.checkoutPawcoin,
                            style: ShopText.cardTitle.copyWith(fontSize: 11.5)),
                        // 🔴 待支付时必须说明「已冻结、取消会退回」 ——
                        //    不说的话用户以为币已经花掉了。
                        Text(pending ? l10n.shopOrderCoinHeld : l10n.shopOrderPaidLabel,
                            style: ShopText.meta),
                      ],
                    ),
                  ),
                  Text('− ${formatIdr(coin)}',
                      style: ShopText.priceInline.copyWith(color: ShopColors.purple)),
                ],
              ),
            ),
            const SizedBox(height: 7),
          ],
          if (cash > 0)
            ShopLeftAccentBlock.money(
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(pending ? l10n.shopOrderQrisWaiting : l10n.checkoutQris,
                            style: ShopText.cardTitle.copyWith(fontSize: 11.5)),
                        Text(l10n.shopOrderQrisOnly, style: ShopText.meta),
                      ],
                    ),
                  ),
                  Text(formatIdr(cash),
                      style: ShopText.priceInline.copyWith(
                          // 🔴 已付的钱用墨色 —— 玫红只留给「还需要付钱」的动作。
                          color: pending ? ShopColors.accent : ShopColors.ink)),
                ],
              ),
            ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 商品与金额

  /// 现金段金额。
  ///
  /// 🔴 `cashAmount` 为 null 表示**非混合支付**（见 `ShopOrderDetailView` 的注释），
  /// 此时用「总额 − 币段」兜底而不是当 0 —— 当 0 会在纯币单上显示「Dibayar Rp 0」。
  static int _cashSegment(ShopOrderDetail order) =>
      order.cashAmount ?? (order.totalAmount - (order.coinAmount ?? 0));

  Widget _itemsBlock(AppLocalizations l10n, ShopOrderDetail order) {
    final pending = order.status.isPendingPayment;
    // **两段都非零** → 金额行改「现金段 + N PawCoin」两段式（设计稿 02 §3）。
    // 🔴 纯币单（现金段 0）不走这一支：那会显示成「Dibayar Rp 0 + Rp 204.000 PawCoin」，
    //    而且「+ 币」这行本身就是把总额换个说法再写一遍。纯币单保留「Total bayar 总额」。
    //
    // 🔴 **待支付同样要拆**（D-4，2026-09-02 stag 电商测试，P0）。此前这里带着 `!pending`，
    //    于是待支付态既不减币段、也根本不列币段：同屏「Total due Rp 305.000」配着按钮
    //    「Pay now Rp 304.001」—— 那 999 的差额，页面上没有任何一处解释得了。
    //    ⚠️ 原先有条测试写着「待支付金额行仍是总额，此时标题是 Total bayar，语义正确」。
    //    那个理由**只在印尼语下成立**：同一个 key 的英文是 **Total due**（现在还欠多少），
    //    而币段在下单时已冻结、用户此刻真要付的只有现金段。语言一换，同一个数就成了错的。
    //    已支付态本来就按现金段显示 —— 两态口径统一之后，这一页不再自相矛盾。
    final coinSplit = (order.coinAmount ?? 0) > 0 && _cashSegment(order) > 0;
    return ShopSection(
      child: Column(
        children: [
          for (final line in order.lines) ...[
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const ShopImage(url: null, size: 50, radius: ShopShape.radiusField),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('${line.productName} · ${line.specName}',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: ShopText.productNameCard),
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          Expanded(
                            child: Text(formatIdr(line.unitPrice),
                                style: ShopText.priceInline.copyWith(
                                    color: pending ? ShopColors.accent : ShopColors.ink)),
                          ),
                          Text('×${line.qty}', style: ShopText.meta),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const ShopDivider(margin: EdgeInsets.symmetric(vertical: 10)),
          ],
          _amountRow(l10n.checkoutSubtotal, formatIdr(order.goodsSubtotal)),
          const SizedBox(height: 4),
          _amountRow(l10n.checkoutShippingFee, formatIdr(order.shippingFee)),
          if (order.shippingDiscount != 0) ...[
            const SizedBox(height: 4),
            _amountRow(l10n.checkoutFreeShipping, formatIdr(-order.shippingDiscount.abs()),
                color: ShopColors.purple),
          ],
          const ShopDivider(margin: EdgeInsets.symmetric(vertical: 9)),
          Row(
            children: [
              Expanded(
                // 待支付 →「Total due」（现在还欠多少）；已支付 →「Dibayar / Paid」。
                child: Text(
                    pending || !coinSplit
                        ? l10n.checkoutPayable
                        : l10n.shopOrderPaidLabel,
                    style: ShopText.cardTitle.copyWith(fontSize: 12)),
              ),
              // 🔴 拆两段显示时这里给的是**现金段**，不是订单总额。
              //    总额已经含了 PawCoin 段，再跟一行「+ 50.000 PawCoin」就是把币算了两遍
              //    （2026-08-19 上机：同屏 QRIS 块写 33.000、这里写 83.000，自相矛盾）。
              //    设计稿 02 §3 的原文是 `Dibayar Rp 154.000 + 50.000 PawCoin` —— 现金 + 币。
              Text(formatIdr(coinSplit ? _cashSegment(order) : order.totalAmount),
                  key: const ValueKey('shopOrderTotalV2'),
                  style: ShopText.priceGrid.copyWith(
                      color: pending ? ShopColors.accent : ShopColors.ink)),
            ],
          ),
          // 🔴 混合支付必须列出 PawCoin 分段 —— 已支付时是退款拆分的用户侧依据，
          //    待支付时是「合计为什么比商品总额少 999」的**唯一**解释（D-4）。
          //    条件与上面的金额行同源：只有真混合支付才有「两段」可言。
          //    ⚠️ ValueKey 仍叫 `…PaidCoinSplitV2`（测试与既有引用的稳定钩子），
          //       但它现在**两态都渲染**，名字里的 Paid 已不再表示只在已支付时出现。
          if (coinSplit) ...[
            const SizedBox(height: 4),
            Align(
              alignment: Alignment.centerRight,
              child: Text('+ ${formatIdr(order.coinAmount!)} ${l10n.checkoutPawcoin}',
                  key: const ValueKey('shopOrderPaidCoinSplitV2'),
                  style: ShopText.badge
                      .copyWith(fontSize: 10.5, color: ShopColors.purple)),
            ),
          ],
        ],
      ),
    );
  }

  Widget _amountRow(String label, String value, {Color? color}) => Row(
        children: [
          Expanded(child: Text(label, style: ShopText.body.copyWith(fontSize: 11))),
          Text(value,
              style: ShopText.body.copyWith(
                  fontSize: 11,
                  fontWeight: color == null ? FontWeight.w400 : FontWeight.w600,
                  color: color ?? ShopColors.text2)),
        ],
      );

  Widget _shipToBlock(AppLocalizations l10n, ShopOrderDetail order) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.shopOrderShipTo, style: ShopText.sectionTitle.copyWith(fontSize: 12)),
            const SizedBox(height: 5),
            Text('${order.receiverName} · ${order.receiverPhone}',
                style: ShopText.cardTitle.copyWith(fontSize: 11.5, fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text(order.addressText, style: ShopText.body),
          ],
        ),
      );

  Widget _metaBlock(AppLocalizations l10n, ShopOrderDetail order) => ShopSection(
        child: Column(
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(l10n.orderNumberLabel, style: ShopText.body.copyWith(fontSize: 10.5)),
                ),
                // 🔴 订单号用等宽 —— 用户要报给客服、要逐位核对。
                Text(order.orderToken, style: ShopText.serialNo),
              ],
            ),
            if (order.createdAt != null) ...[
              const SizedBox(height: 4),
              Row(
                children: [
                  Expanded(
                    child: Text(l10n.orderCreatedAtLabel,
                        style: ShopText.body.copyWith(fontSize: 10.5)),
                  ),
                  Text(formatDayMonthYearTime(context, order.createdAt!.toLocal()),
                      style: ShopText.meta),
                ],
              ),
            ],
          ],
        ),
      );

  /// 售后告知块。
  ///
  /// 🔴 发货态**只做告知不给退货按钮** —— 货还没到手，退不了。
  /// 设计稿因此把这里做成「有问题？」的说明而不是操作入口。
  Widget _helpBlock(AppLocalizations l10n) => ShopSection(
        key: const ValueKey('shopOrderHelpBlockV2'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.shopOrderHelpTitle, style: ShopText.cardTitle.copyWith(fontSize: 11.5)),
            const SizedBox(height: 2),
            Text(l10n.shopOrderHelpBody, style: ShopText.meta),
          ],
        ),
      );

  // ---------------------------------------------------------------- 底部条

  Widget? _bottomBar(AppLocalizations l10n, ShopOrderDetail order) {
    final expiresAt = order.expiresAt;
    final expired = order.status.isPendingPayment &&
        expiresAt != null &&
        !expiresAt.isAfter(DateTime.now().toUtc());

    // 🔴 过期后**不保留支付入口**：一个点下去必然失败的按钮比没有更糟。
    if (order.status.isPendingPayment && !expired) {
      // 设计稿：`Batalkan`（浅描边、固定宽）+ `Bayar {金额}`（玫红，更宽）。
      // 主操作带上金额 —— 用户在倒计时里最需要确认的就是「点下去要付多少」。
      return ShopBottomBarActions(
        secondaryFlex: 1,
        primaryFlex: 2,
        secondary: ShopButton(
          key: const ValueKey('shopOrderCancelV2'),
          // 设计稿写的是单词 `Batalkan`：次操作是固定宽的，两个词会折成两行、
          // 把整条底部条撑高，次操作看着比主操作还重（2026-08-19 上机）。
          label: l10n.shopOrderCancelShort,
          variant: _busy && _busyAction != _OrderAction.cancel
              ? ShopButtonVariant.disabled
              : ShopButtonVariant.outlineMuted,
          loading: _busyAction == _OrderAction.cancel,
          onTap: _busy ? null : () => _cancel(l10n),
        ),
        primary: ShopButton(
          key: const ValueKey('shopOrderPayV2'),
          // 🔴 金额是**现在真要付的现金段**，不是订单总额：币段下单时已冻结，
          //    按钮写总额会让用户以为币白冻结了、还要再付一次全款。
          label: '${l10n.shopOrderPayNow} ${formatIdr(_cashSegment(order))}',
          // 🔴 支付请求进行中保持强调色 + 转圈，**不置灰**（2026-08-27）：置灰与
          //    「这单已经付不了了」是同一个视觉，用户会重复点击 —— 而这是支付链路。
          variant: _busy && _busyAction != _OrderAction.pay
              ? ShopButtonVariant.disabled
              : ShopButtonVariant.pay,
          loading: _busyAction == _OrderAction.pay,
          onTap: _busy ? null : () => _pay(l10n, order),
        ),
      );
    }

    if (order.status.canConfirmReceipt) {
      final pkg = order.packages.isEmpty ? null : order.packages.first;
      return ShopBottomBarActions(
        secondary: pkg == null || pkg.trackingUrl.isEmpty
            ? null
            : ShopButton(
                key: const ValueKey('shopOrderTrackV2'),
                label: l10n.shopOrderTrackOnCarrierSite,
                variant: ShopButtonVariant.outlinePurple,
                onTap: () => _openCarrierSite(l10n, pkg),
              ),
        primary: ShopButton(
          key: const ValueKey('shopOrderConfirmReceiptV2'),
          label: l10n.shopOrderConfirmReceipt,
          variant: _busy && _busyAction != _OrderAction.receipt
              ? ShopButtonVariant.disabled
              : ShopButtonVariant.ink,
          loading: _busyAction == _OrderAction.receipt,
          onTap: _busy ? null : () => _confirmReceipt(l10n),
        ),
      );
    }

    // 已完成：退货入口（退货窗口内才给）。
    if (order.status == ShopOrderStatus.completed) return _returnBar(l10n, order);
    return null;
  }

  /// 退货入口。
  ///
  /// 🔴 已有进行中的退货申请时**置灰并说明**（UX-DR3 / C-12），不是隐藏 ——
  /// 隐藏会让用户以为自己没提交成功，转头再提交一次。
  Widget? _returnBar(AppLocalizations l10n, ShopOrderDetail order) {
    final e = ref.watch(returnEligibilityProvider(order.orderToken)).maybeWhen(
          data: (v) => v,
          orElse: () => null,
        );
    if (e == null) return null;
    final blocked = !e.eligible || e.activeRequestToken != null;
    return ShopBottomBarActions(
      primary: ShopButton(
        key: const ValueKey('shopOrderReturnV2'),
        label: e.activeRequestToken != null
            ? l10n.shopOrderReturnInProgress
            : l10n.shopOrderRequestReturn,
        variant: blocked ? ShopButtonVariant.disabled : ShopButtonVariant.pay,
        onTap: blocked
            ? null
            : () => context.push('/shop/orders/${order.orderToken}/return'),
      ),
    );
  }

  // ---------------------------------------------------------------- 动作
  // 🔴 以下四个方法与 v1 逐字相同（含埋点与失败处置）——
  //    版式是本文件的全部差异，业务动作一行都不该变。

  Future<void> _copyTracking(AppLocalizations l10n, ShopOrderPackage pkg) async {
    await Clipboard.setData(ClipboardData(text: pkg.trackingNo));
    Analytics.capture('toko_order_tracking_copy_tapped');
    if (mounted) showAppToast(context, l10n.shopOrderCopied);
  }

  Future<void> _openCarrierSite(AppLocalizations l10n, ShopOrderPackage pkg) async {
    Analytics.capture('toko_order_tracking_site_tapped');
    final uri = Uri.tryParse(pkg.trackingUrl);
    final ok = uri == null
        ? false
        : await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!ok && mounted) showAppToast(context, l10n.shopOrderTrackOpenFailed);
  }

  Future<void> _pay(AppLocalizations l10n, ShopOrderDetail order) async {
    Analytics.capture('toko_order_pay_tapped');
    setState(() => _busyAction = _OrderAction.pay);
    try {
      final result = await ref.read(shopOrderRepositoryProvider).pay(widget.orderToken);
      if (!mounted) return;
      if (result.settledImmediately) {
        // 纯 PawCoin：当场结清，没有二维码。余额缓存失效，免得别处读到旧余额。
        ref.invalidate(pawCoinProvider);
        ref.invalidate(shopOrderDetailProvider(widget.orderToken));
        Analytics.capture('toko_order_payment_succeeded',
            {'pay_channel': 'PAWCOIN', 'attribution_source': order.attributionSource});
        showAppToast(context, l10n.shopOrderPaid);
        return;
      }
      final paid = await showQrPaymentSheet(
        context,
        payload: result.payload!,
        orderRef: order.orderToken,
        // 轮询问的是订单本身的状态 —— 到账由服务端在回调里推进，客户端不自行判定。
        pollPaid: () async {
          final fresh =
              await ref.refresh(shopOrderDetailProvider(widget.orderToken).future);
          return !fresh.status.isPendingPayment;
        },
      );
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      ref.invalidate(pawCoinProvider);
      if (paid) {
        Analytics.capture('toko_order_payment_succeeded', {
          'pay_channel': order.payChannel ?? 'UNKNOWN',
          'attribution_source': order.attributionSource,
        });
        showAppToast(context, l10n.shopOrderPaid);
      }
    } catch (_) {
      if (mounted) {
        Analytics.capture('toko_order_payment_failed_shown');
        showAppToast(context, l10n.shopOrderPayFailed);
      }
    } finally {
      if (mounted) setState(() => _busyAction = null);
    }
  }

  Future<void> _confirmReceipt(AppLocalizations l10n) async {
    final confirmed = await showShopConfirm(
      context,
      dialogKey: const ValueKey('shopOrderConfirmReceiptDialogV2'),
      confirmKey: const ValueKey('shopOrderConfirmReceiptYesV2'),
      title: l10n.shopOrderConfirmReceiptTitle,
      body: l10n.shopOrderConfirmReceiptBody,
      confirmLabel: l10n.shopOrderConfirmReceiptYes,
      cancelLabel: l10n.shopOrderConfirmReceiptNo,
    );
    if (!confirmed || !mounted) return;

    // 🔴 「点了」与「成功了」是两个事件：确认收货会失败（网络 / 状态已变），
    //    只埋一个的话，漏斗上分不清是没人点还是点了没成。
    Analytics.capture('toko_order_receipt_confirm_tapped');
    setState(() => _busyAction = _OrderAction.receipt);
    try {
      await ref.read(shopOrderRepositoryProvider).confirmReceipt(widget.orderToken);
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      Analytics.capture('toko_order_receipt_confirm_succeeded');
      showAppToast(context, l10n.shopOrderReceiptConfirmed);
    } catch (_) {
      if (mounted) showAppToast(context, l10n.shopOrderConfirmReceiptFailed);
    } finally {
      if (mounted) setState(() => _busyAction = null);
    }
  }

  Future<void> _cancel(AppLocalizations l10n) async {
    // 🔴 确认按钮**不用 [ShopButtonVariant.pay]**（2026-08-27 修）：那个变体按 token
    //    文档是「未完成的付款动作」，用它确认「取消订单」既与语义相反，也让最该让人
    //    停一拍的按钮长得最像转化 CTA。同页的确认收货用的就是 ink，两处现在一致了。
    final confirmed = await showShopConfirm(
      context,
      dialogKey: const ValueKey('shopOrderCancelDialogV2'),
      confirmKey: const ValueKey('shopOrderCancelConfirmYesV2'),
      title: l10n.shopOrderCancelConfirm,
      body: l10n.shopOrderCancelConfirmBody,
      confirmLabel: l10n.shopOrderCancelConfirmYes,
      cancelLabel: l10n.shopOrderCancelConfirmNo,
    );
    if (!confirmed || !mounted) return;

    Analytics.capture('toko_order_cancel_tapped');
    setState(() => _busyAction = _OrderAction.cancel);
    try {
      await ref.read(shopOrderRepositoryProvider).cancel(widget.orderToken);
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      // 取消会把库存还回去，购物车角标不受影响，但订单相关缓存要刷。
      ref.invalidate(cartProvider);
      showAppToast(context, l10n.shopOrderCancelled);
    } catch (_) {
      // 2026-08-27：原先复用 shopOrderPayFailed，取消失败会提示「支付失败」。
      if (mounted) showAppToast(context, l10n.shopOrderCancelFailed);
    } finally {
      if (mounted) setState(() => _busyAction = null);
    }
  }

}
