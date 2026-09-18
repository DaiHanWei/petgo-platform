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
import 'package:url_launcher/url_launcher.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../../support/presentation/support_whatsapp_button.dart';
import '../data/cart_repository.dart';
import '../data/shop_order_repository.dart';
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
        // 🔴 V1.3.0 Story 3-3：本块由「只在待收货/已完成显示」改为**始终显示**。
        //    理由是它现在装着客服入口，而「找不到客服」这件事在任何订单状态下都可能发生 ——
        //    待支付付不了、待发货迟迟不发货，恰恰是最需要找人的时候。
        //    （2-1 之后本块的文案也已经从「2×24 小时可退」换成了指向客服的中性表述。）
        _helpBlock(l10n, order),
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
                // 🔴 标题必须**跟着订单状态**走（D-6，2026-09-02 stag 电商测试）。
                //    此前这里写死 `shopOrderShippedNow`（On the way）：后台把包裹标记送达、
                //    订单转 DELIVERED 之后，本行仍写「On the way」，而同页下方
                //    Delivery history 的当前态、订单列表卡、后台状态**全都是 Delivered**。
                //    这是 shipped/delivered 订单的**第一个区块**，用户第一眼读到的就是错的。
                //    ⚠️ 送达态复用时间线那条 `shopOrderPackageDelivered` ——
                //       同一页对同一件事必须用同一个词，另起一个 key 迟早两边走散。
                child: Text(
                    order.status == ShopOrderStatus.delivered
                        ? l10n.shopOrderPackageDelivered
                        : l10n.shopOrderShippedNow,
                    key: const ValueKey('shopOrderFulfillmentTitleV2'),
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
    final cancelled = order.status == ShopOrderStatus.cancelled;
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
                        //
                        // 🔴 D-19（2026-09-02 stag）：此前这里是**二元判断**
                        //    `pending ? Held : Paid`，于是**取消态落到了 Paid** ——
                        //    而实测资金是对的（冻结的币已完整解冻退回、顶栏余额也同步了），
                        //    只是这一行写着「Paid」，用户会以为钱被扣了。
                        //    取消 = 钱从来没被拿走，得单说一句「已退回余额」。
                        Text(
                            cancelled
                                ? l10n.shopOrderCoinReturned
                                : (pending ? l10n.shopOrderCoinHeld : l10n.shopOrderPaidLabel),
                            key: const ValueKey('shopOrderCoinStatusV2'),
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
    //
    // 🔴 **全额抵扣是 D-4 最极端的那一形态**（2026-09-02 复测）：PawCoin 覆盖全单时
    //    现金段为 0，旧判据 `cash > 0` 直接把这种单排除在外 ⇒ 又退回显示总额。
    //    实测同一屏：明细区「Total due Rp 70.000」，正下方按钮「**Pay now Rp 0**」——
    //    按钮自己写着付 0，上方却称应付 70.000。差的不再是 999 而是**全额**，
    //    用户会以为还要再付 70.000 而放弃下单。
    //    ⚠️ 所以待支付态的判据只看**有没有币段**：全额抵扣时「Total due Rp 0」
    //       正是对的（现在一分现金都不欠），与那个按钮同数。
    //    ⚠️ 已支付态仍要求 `cash > 0`：纯币单显示「Dibayar Rp 0」读起来像没付钱，
    //       那一支保留原样显示总额。两态判据不同是刻意的，不是漏写。
    final coin = order.coinAmount ?? 0;
    // 🔴 取消态不参与两段式：订单已取消，**不存在"还欠多少"**，
    //    把它拆成「现金段 + 币段」读起来像一笔真发生过的付款（D-19 附带项）。
    final cancelled = order.status == ShopOrderStatus.cancelled;
    final coinSplit = !cancelled && coin > 0 && (pending || _cashSegment(order) > 0);
    return ShopSection(
      child: Column(
        children: [
          for (final line in order.lines) ...[
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ShopImage(
                    url: line.mainImageUrl, size: 50, radius: ShopShape.radiusField),
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
                // 待支付 →「Total due」（现在还欠多少）；已支付 →「Dibayar / Paid」；
                // 🔴 已取消 →「订单金额」：这单不存在"应付"，写 Total due 是无意义的
                //    （D-19 附带项：取消后该行仍显示 Total due Rp 70.000）。
                child: Text(
                    cancelled
                        ? l10n.shopOrderCancelledTotalLabel
                        : (pending || !coinSplit
                            ? l10n.checkoutPayable
                            : l10n.shopOrderPaidLabel),
                    key: const ValueKey('shopOrderTotalLabelV2'),
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
                Text(_displayedOrderNo(order), style: ShopText.serialNo),
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
  /// 🔴 **本页展示给用户的那个订单号，唯一一处**（Story 3-3）。
  ///
  /// 页面上的订单号（`_metaBlock` 里那行等宽字）与 WhatsApp 深链的预填**必须同源**：
  /// 用户拿去跟客服核对的就是他屏幕上看得见的那串字符，深链填另一个号只会让客服
  /// 拿到一个用户那儿找不到的号。
  ///
  /// 🔴 **Story 4-3 已切换**：这里曾经是 `orderToken`（22 位随机串），而订单中心列表
  /// 展示的是 `TOKO-…` —— 同一张单两个字符串，用户报给客服的号后台还搜不到。
  /// 3-3 把它收成这一个 getter，就是为了让今天只改这一行。
  ///
  /// `displayNo` 为空时回落到 `orderToken`：灰度期老后端不下发这个字段，
  /// **显示一个旧格式的号，好过显示一片空白**。
  String _displayedOrderNo(ShopOrderDetail order) =>
      order.displayNo.isEmpty ? order.orderToken : order.displayNo;

  Widget _helpBlock(AppLocalizations l10n, ShopOrderDetail order) => ShopSection(
        key: const ValueKey('shopOrderHelpBlockV2'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.shopOrderHelpTitle, style: ShopText.cardTitle.copyWith(fontSize: 11.5)),
            const SizedBox(height: 2),
            Text(l10n.shopOrderHelpBody, style: ShopText.meta),
            const SizedBox(height: 10),
            // 🔴 客服入口落在**这里**而不是 `_bottomBar`（Story 3-3）：
            //    `_bottomBar` 针对 8 个订单状态返回不同组合、无动作状态直接 return null，
            //    要让入口「始终显示」就得把 null 那支改成「返回一个只含客服按钮的 bar」——
            //    8 个状态的底栏高度与排布全要重新验收，还会和隐藏退货入口的改动撞在同一块代码。
            //    本块本来就是「有问题？」的售后告知块，只有文案没有动作，语义天然吻合。
            //    预填用的是本页 `:580` 展示给用户的那个号，两处同源 ——
            //    将来订单号口径统一（SHOP-FR-29）时深链自动跟随，一行码都不用改。
            SupportWhatsAppButton(
                orderNo: _displayedOrderNo(order), screen: 'shop_order_detail'),
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

    // 🔴 已完成态原先在这里给退货入口，V1.3.0 按 SD-5 整块摘掉 ——
    //    后端退货接口与后台退货页面都还在、都可用，只是 App 侧不给入口：
    //    一个点了走不通的入口会让用户白填一遍表单、传完凭证照片才发现这条路不通。
    //    ⚠️ 下一版恢复只需把 `_returnBar` 从 git 历史取回并加回这一行分支；
    //      `shop_return_repository.dart` / `ReturnRequestPageV2` / `RefundMethodPageV2`
    //      与 `test/shop/return_flow_page_v2_test.dart` 全部原样留着，一个字都不用改。
    return null;
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

  /// 本次会话内看到过「被拒」（Story 1-4 AC2）。
  ///
  /// 🔴 用**上次失败类别**做判据，不是「点了几次支付」的计数器 —— 计数器分不出
  /// 「被拒后重试」和「关了面板待会再付」，而这两件事对运营是完全不同的信号。
  /// 成功 / 超时 / 用户取消一律清回 false（那些结局之后的下一次支付不是重试）。
  ///
  /// ⚠️ 它只是**本次页面停留期间**的记忆。跨页面、跨进程的那一半由
  /// [_isRetry] 从服务端下发的 `paymentFailureCategory` 补齐 —— 只靠这个字段的话，
  /// 用户退出详情页（或杀掉进程）再回来重试就不算重试了，而这一格**没有服务端事件兜底**
  /// （服务端只知道「又创建了一个意图」，不知道用户是不是在重试）。
  bool _lastPaymentDeclined = false;

  /// 这一次点支付算不算「重试」。
  ///
  /// 服务端下发的 `paymentFailureCategory` 才是权威的「上一次失败类别」：
  /// 订单仍在待支付窗内、而它的支付单停在 `GATEWAY_DECLINED` ⇒ 这一次点下去就是重试，
  /// 不管用户中间有没有离开过页面。
  bool _isRetry(ShopOrderDetail order) =>
      _lastPaymentDeclined ||
      order.paymentFailure == ShopPaymentFailure.gatewayDeclined;

  /// 支付类事件的属性（Story 1-4 AC3）。**只有这四个键。**
  ///
  /// 🔒 绝不放 `order.orderToken` —— 订单号对运营有用但对漏斗没用，且它是对外标识，
  /// 进第三方等于把标识面扩出去（SHOP-NFR-01）。要排查有后端接口日志。
  /// 🔒 也绝不放 `receiverName` / `receiverPhone` / `addressText` —— 它们就在同一个
  /// `ShopOrderDetail` 上，离得最近、最容易手滑。
  Map<String, Object> _payProps(ShopOrderDetail order, {String? failureCategory}) => {
        // 与既有 toko_order_payment_succeeded 同一写法，保持口径一致。
        'pay_channel': order.payChannel ?? 'UNKNOWN',
        'attribution_source': order.attributionSource,
        'has_pawcoin': (order.coinAmount ?? 0) > 0,
        'failure_category': ?failureCategory,
      };

  Future<void> _pay(AppLocalizations l10n, ShopOrderDetail order) async {
    // 🔴 既有事件照发不误：漏斗「进入支付」这一格的分母靠它，重试也是一次进入支付。
    Analytics.capture('toko_order_pay_tapped');
    if (_isRetry(order)) {
      Analytics.capture('toko_payment_retry_tapped', _payProps(order));
    }
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
      // 🔴 中止**不是**异常路径：它由 onAborted 回调带出来，走下面的正常分派。
      //    塞进 catch 会让三态一律弹通用失败 toast，正是本 story 要消灭的那个行为。
      // 🔴 **两个变量缺一不可**：`aborted == null` 有两种成因 ——
      //    ① 根本没中止（用户自己关了面板）；② 中止了但没带类别（老后端的兜底分支，
      //    见下面 pollPaid 里那条 `const QrPaymentAborted()`）。
      //    只看类别的话，灰度期每一单被服务端取消的订单都会被记成
      //    `toko_payment_sheet_dismissed`＝「用户自己走掉了」，把那一格彻底污染掉。
      bool abortSignalled = false;
      ShopPaymentFailure? aborted;
      // 🔴 从**电商侧**发，不跑去 sheet 内部发：sheet 是共用组件，在里面埋点会给
      //    AI 解锁与高清身份证两条线凭空多出事件（AC6）。
      //    纯 PawCoin 单不出码，上面已 return，走不到这里。
      Analytics.capture('toko_payment_qr_shown', _payProps(order));
      final paid = await showQrPaymentSheet(
        context,
        payload: result.payload!,
        orderRef: order.orderToken,
        onAborted: (abort) {
          abortSignalled = true;
          aborted = ShopPaymentFailure.fromApi(abort.category);
        },
        // 轮询问的是订单本身的状态 —— 到账由服务端在回调里推进，客户端不自行判定。
        pollPaid: () async {
          final fresh =
              await ref.refresh(shopOrderDetailProvider(widget.orderToken).future);
          // 🔴 先看支付单：网关拒付**只改 payment_intents、不改订单**，订单会一直停在
          //    PENDING_PAYMENT，只看 status 的话二维码要挂到 60 分钟窗口耗尽。
          final failure = fresh.paymentFailure;
          if (failure != null) {
            throw QrPaymentAborted(fresh.paymentFailureCategory);
          }
          // 兜底：后端未升级或纯 PawCoin 单时两字段为 null，行为与改动前完全一致。
          if (fresh.status == ShopOrderStatus.cancelled) {
            throw const QrPaymentAborted();
          }
          return fresh.status.isPaidOrLater;
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
        _lastPaymentDeclined = false;
        return;
      }
      _onPaymentAborted(l10n, order, aborted, abortSignalled: abortSignalled);
    } catch (_) {
      if (mounted) {
        Analytics.capture('toko_order_payment_failed_shown');
        showAppToast(context, l10n.shopOrderPayFailed);
      }
    } finally {
      if (mounted) setState(() => _busyAction = null);
    }
  }

  /// 面板关闭后的四态分派（Story 1-3 AC2~AC5）。
  ///
  /// | 结局 | `aborted` | 重试入口 | 文案 |
  /// |---|---|---|---|
  /// | 支付被拒 | `gatewayDeclined` | **保留**（订单未取消未过期，`_bottomBar` 自然给） | 被拒说明 |
  /// | 超时未付 | `expired` | **不给**（订单已 CANCELLED，`_bottomBar` 返 null） | 已取消告知 |
  /// | 用户取消订单 | `userCancelled` | — | **无**（静默） |
  /// | 仅关闭面板 | **null** | 保留 | **无** |
  ///
  /// 🔴 最后两行是本 story 最容易写错的地方：两者都表现为「面板关闭 + 返回 false」，
  /// 唯一可靠的判据是 `pollPaid` 有没有抛出中止信号 —— 面板的取消按钮走 `pop(false)`
  /// 而不抛异常，所以 `aborted` 为 null 就是「用户自己关掉的」。
  /// @param abortSignalled `pollPaid` 是否抛过中止信号。与 [aborted] 是**两件事**：
  ///     老后端不下发失败类别时会走 `const QrPaymentAborted()`（已中止、无类别），
  ///     此时 [aborted] 同样是 null，但它绝不是「用户自己关掉的」。
  void _onPaymentAborted(
      AppLocalizations l10n, ShopOrderDetail order, ShopPaymentFailure? aborted,
      {required bool abortSignalled}) {
    if (!abortSignalled) {
      // 仅关闭面板：订单原样不动，什么都不做。弹一句失败会让用户以为订单出事了。
      // 🔴 但**要埋点**：这一格在服务端没有任何对应事件（关面板不产生服务端状态变化），
      //    这正是它的价值 —— 「出码后自己走掉」只有客户端看得见。
      Analytics.capture('toko_payment_sheet_dismissed', _payProps(order));
      return;
    }
    if (aborted == null) {
      // 中止了但没带类别 = 老后端（1-1 未上线）。UI 上与改动前完全一致：静默关闭。
      // 🔴 埋点也**保持改动前的样子：什么都不发**。发 sheet_dismissed 是谎
      //    （不是用户走的），发 declined/expired 是猜（不知道到底哪一种）。
      //    灰度期这一格的数据由服务端 1-2 的 shop_payment_* 兜着，它不看 App 版本。
      _lastPaymentDeclined = false;
      return;
    }
    switch (aborted) {
      case ShopPaymentFailure.gatewayDeclined:
        _lastPaymentDeclined = true;
        Analytics.capture('toko_payment_declined_shown',
            _payProps(order, failureCategory: ShopPaymentFailure.gatewayDeclined.api));
        showAppToast(context, l10n.shopPaymentDeclinedNotice);
      case ShopPaymentFailure.expired:
        _lastPaymentDeclined = false;
        Analytics.capture('toko_payment_expired_shown',
            _payProps(order, failureCategory: ShopPaymentFailure.expired.api));
        // 🔴 **不弹** shopOrderPayFailed —— 那是「再试一次」的口吻，而这一单已经没了。
        showAppToast(context, l10n.shopOrderExpiredNotice);
      case ShopPaymentFailure.userCancelled:
        // 是用户自己取消的，他知道发生了什么。只刷新，不弹错误。
        // 埋点走 toko_order_cancel_* 那套（那是用户的动作，不是失败反馈）。
        _lastPaymentDeclined = false;
      case ShopPaymentFailure.unknown:
        // 后端加了 App 不认识的类别：按通用失败处理，不猜它该不该重试。
        _lastPaymentDeclined = false;
        Analytics.capture('toko_order_payment_failed_shown');
        showAppToast(context, l10n.shopOrderPayFailed);
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
      // 取消成功后这一单已经没了，下一次支付（如果有）不是「被拒后重试」。
      _lastPaymentDeclined = false;
      Analytics.capture('toko_order_cancel_succeeded');
      showAppToast(context, l10n.shopOrderCancelled);
    } catch (_) {
      // 2026-08-27：原先复用 shopOrderPayFailed，取消失败会提示「支付失败」。
      if (mounted) showAppToast(context, l10n.shopOrderCancelFailed);
    } finally {
      if (mounted) setState(() => _busyAction = null);
    }
  }

}
