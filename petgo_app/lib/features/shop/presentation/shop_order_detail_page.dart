import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/cart_repository.dart';
import '../data/shop_order_repository.dart';
import '../domain/shop_order_detail.dart';
import '../domain/shop_product.dart';

/// 电商订单详情 · 待支付态（Story 3.8，FR-100 / AD-8 / AD-9）。
///
/// 🔴 **倒计时以服务端 `expiresAt` 为权威**：本页每秒重绘的只是「还剩多久」这个显示值，
/// **绝不**在本地判定「已超时」然后自己把状态改成已取消 —— 那样改一下手机时间就能
/// 看到另一套现实。归零时做的唯一动作是**重新拉详情**，让服务端说它到底怎么样了
/// （服务端读到过窗订单会就地取消并释放库存）。
///
/// 🔴 **仅 QRIS**（FR-100）：不支持 GoPay/OVO 直连、不支持银行转账/VA。
/// 副标题必须说明「QRIS 可被各家 e-wallet 扫码」，否则用户会以为自己的钱包不能用。
///
/// Story 4.5 追加履约态：承运商 / 物流单号 / 复制 / 跳承运商官网 · 逐包裹列出（S-2）·
/// 已发货态即给「确认收货」（SPEC-2 出口②）。
///
/// 🔴 **不接承运商 API、不在 App 内渲染物流轨迹**（FR-103）——「查物流」= 跳出去查。
/// 自建轨迹聚合要为三家承运商 API 的可用性长期负责，V1 不承担这个。
///
/// 🔴 **时效文案只说「Reguler 2–4 个工作日」**：C-14 已把配送方式收为一维、砍掉当日达，
/// 承诺「今天送达」就是每天都在制造一批必然失望的用户（UX-DR13）。
class ShopOrderDetailPage extends ConsumerStatefulWidget {
  const ShopOrderDetailPage({super.key, required this.orderToken});

  final String orderToken;

  @override
  ConsumerState<ShopOrderDetailPage> createState() => _ShopOrderDetailPageState();
}

class _ShopOrderDetailPageState extends ConsumerState<ShopOrderDetailPage> {
  Timer? _ticker;
  bool _busy = false;

  /// 倒计时已归零且已触发过一次重拉 —— 防止归零后每秒都去打一次接口。
  bool _refreshedOnExpiry = false;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_order_detail_viewed');
    // 只驱动重绘；判定权不在这里。
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(shopOrderDetailProvider(widget.orderToken));

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(title: Text(l10n.shopOrderTitle), backgroundColor: AppColors.cream),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.xl),
            child: Text(l10n.shopOrderLoadFailed, textAlign: TextAlign.center),
          ),
        ),
        data: (order) => _content(l10n, order),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (order) {
          if (order.status.isPendingPayment) return _bottomBar(l10n, order);
          // 🔴 SPEC-2 出口②：**已发货态就给确认收货**，不必等系统标记送达。
          if (order.status.canConfirmReceipt) return _confirmBar(l10n);
          return null;
        },
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ShopOrderDetail order) {
    final remaining = order.remaining(DateTime.now());
    // 🔴 归零 → 重新问服务端，而不是自己改状态
    if (order.status.isPendingPayment && remaining == Duration.zero && !_refreshedOnExpiry) {
      _refreshedOnExpiry = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      });
    }

    return ListView(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
      children: [
        _statusHeader(l10n, order, remaining),
        if (order.status.hasFulfillmentInfo && order.packages.isNotEmpty) ...[
          _section(l10n.shopOrderShippingSection),
          for (var i = 0; i < order.packages.length; i++)
            _packageCard(l10n, order.packages[i], i, order.packages.length),
        ],
        _section(l10n.shopOrderShipTo),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
          child: Card(
            margin: EdgeInsets.zero,
            child: ListTile(
              title: Text('${order.receiverName} · ${order.receiverPhone}',
                  style: const TextStyle(fontWeight: FontWeight.w600)),
              subtitle: Text(order.addressText),
            ),
          ),
        ),
        _section(l10n.shopOrderItems),
        for (final line in order.lines)
          Padding(
            padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.lg, vertical: AppSpacing.xs),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(line.productName,
                          style: const TextStyle(fontWeight: FontWeight.w600)),
                      Text('${line.specName} × ${line.qty}',
                          style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                    ],
                  ),
                ),
                Text(formatIdr(line.lineTotal)),
              ],
            ),
          ),
        const SizedBox(height: AppSpacing.md),
        _amounts(l10n, order),
        Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Text(l10n.shopOrderNumber(order.orderToken),
              style: const TextStyle(fontSize: 12, color: AppColors.muted)),
        ),
      ],
    );
  }

  Widget _statusHeader(AppLocalizations l10n, ShopOrderDetail order, Duration remaining) {
    final label = switch (order.status) {
      ShopOrderStatus.pendingPayment => l10n.shopOrderStatusPendingPayment,
      ShopOrderStatus.pendingShipment => l10n.shopOrderStatusPendingShipment,
      ShopOrderStatus.shipped => l10n.shopOrderStatusShipped,
      ShopOrderStatus.delivered => l10n.shopOrderStatusDelivered,
      ShopOrderStatus.completed => l10n.shopOrderStatusCompleted,
      ShopOrderStatus.cancelled => l10n.shopOrderStatusCancelled,
      // 🔴 认不出的状态给中性文案且不给任何动作（见 domain 注释）
      _ => l10n.shopOrderStatusOther,
    };
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(
          color: AppColors.mintTint,
          borderRadius: BorderRadius.circular(AppSpacing.sm),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label,
                key: const ValueKey('shopOrderStatus'),
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
            if (order.status.isPendingPayment && order.expiresAt != null) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(l10n.shopOrderCountdown(_mmss(remaining)),
                  key: const ValueKey('shopOrderCountdown'),
                  style: const TextStyle(color: AppColors.mint600, fontWeight: FontWeight.w600)),
            ],
            if (order.status == ShopOrderStatus.cancelled) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(l10n.shopOrderExpiredNotice,
                  key: const ValueKey('shopOrderExpiredNotice'),
                  style: const TextStyle(fontSize: 13)),
            ],
            // 🎨 UX-DR7：已发货 / 已送达 / 已完成三态无视觉稿，沿用待支付态的
            //    「大标题 + 一行副文案」结构范式，不自创新版式。
            if (order.status == ShopOrderStatus.shipped) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(l10n.shopOrderEtaReguler,
                  key: const ValueKey('shopOrderEta'),
                  style: const TextStyle(fontSize: 13)),
            ],
            if (order.status == ShopOrderStatus.delivered) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(l10n.shopOrderDeliveredHint,
                  key: const ValueKey('shopOrderDeliveredHint'),
                  style: const TextStyle(fontSize: 13)),
            ],
            if (order.status == ShopOrderStatus.completed) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(l10n.shopOrderCompletedHint,
                  key: const ValueKey('shopOrderCompletedHint'),
                  style: const TextStyle(fontSize: 13)),
            ],
            // 🔴 退货窗口用【服务端下发的截止日】，App 不自己加 7 天。
            //    「已完成」不等于「不能再退」—— 不写出来，用户会以为确认收货就放弃了退货权。
            if (order.returnWindowEndsAt != null) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(l10n.shopOrderReturnWindow(_ymd(order.returnWindowEndsAt!)),
                  key: const ValueKey('shopOrderReturnWindow'),
                  style: const TextStyle(fontSize: 12, color: AppColors.muted)),
            ],
          ],
        ),
      ),
    );
  }

  Widget _amounts(AppLocalizations l10n, ShopOrderDetail order) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        child: Column(
          children: [
            _row(l10n.checkoutSubtotal, formatIdr(order.goodsSubtotal)),
            _row(l10n.checkoutShippingFee, formatIdr(order.shippingFee)),
            if (order.shippingDiscount != 0)
              _row(l10n.checkoutFreeShipping, formatIdr(order.shippingDiscount)),
            const Divider(),
            // 🔴 两段金额分开显示（FR-100A 规则 2）：混合支付时用户必须看清各扣多少
            if (order.isMixed) ...[
              _row(l10n.checkoutPawcoin, '− ${formatIdr(order.coinAmount!)}',
                  key: const ValueKey('shopOrderCoinSegment')),
              _row(l10n.checkoutQris, formatIdr(order.cashAmount!),
                  key: const ValueKey('shopOrderCashSegment')),
            ],
            _row(l10n.checkoutPayable, formatIdr(order.totalAmount),
                key: const ValueKey('shopOrderTotal'), strong: true),
            const SizedBox(height: AppSpacing.xs),
            // 🔴 FR-100：仅 QRIS，且说明它可被各家 e-wallet 扫码
            Text(l10n.shopOrderQrisOnly,
                style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          ],
        ),
      );

  Widget _row(String label, String value, {Key? key, bool strong = false}) => Padding(
        key: key,
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xxs),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label,
                style: TextStyle(
                    fontSize: strong ? 15 : 13,
                    fontWeight: strong ? FontWeight.w700 : FontWeight.w400)),
            Text(value,
                style: TextStyle(
                    fontSize: strong ? 16 : 13,
                    fontWeight: strong ? FontWeight.w700 : FontWeight.w500,
                    color: strong ? AppColors.mint : AppColors.ink)),
          ],
        ),
      );

  Widget _bottomBar(AppLocalizations l10n, ShopOrderDetail order) => SafeArea(
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.lg),
          decoration: const BoxDecoration(
            color: AppColors.cream,
            border: Border(top: BorderSide(color: AppColors.line)),
          ),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  key: const ValueKey('shopOrderCancel'),
                  onPressed: _busy ? null : () => _cancel(l10n),
                  child: Text(l10n.shopOrderCancel),
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: FilledButton(
                  key: const ValueKey('shopOrderPay'),
                  onPressed: _busy ? null : () => _pay(l10n, order),
                  child: Text(l10n.shopOrderPayNow),
                ),
              ),
            ],
          ),
        ),
      );

  // ---------- 物流区块（Story 4.5） ----------

  /// 一个包裹。S-2 一单多包时逐条列出，各自标明送达状态。
  Widget _packageCard(
      AppLocalizations l10n, ShopOrderPackage pkg, int index, int total) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.sm),
      child: Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 只有一个包裹时不显示「第 1 / 共 1 包」—— 那是噪音
              if (total > 1)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.xs),
                  child: Text(l10n.shopOrderPackageIndex(index + 1, total),
                      key: ValueKey('shopOrderPackageIndex_$index'),
                      style: const TextStyle(
                          fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.muted)),
                ),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(l10n.shopOrderCarrier,
                      style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                  Text(pkg.carrierName,
                      key: ValueKey('shopOrderCarrier_$index'),
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                ],
              ),
              const SizedBox(height: AppSpacing.xxs),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(l10n.shopOrderTrackingNo,
                      style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                  Flexible(
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        Flexible(
                          child: Text(pkg.trackingNo,
                              key: ValueKey('shopOrderTrackingNo_$index'),
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontWeight: FontWeight.w600)),
                        ),
                        const SizedBox(width: AppSpacing.xs),
                        TextButton(
                          key: ValueKey('shopOrderCopy_$index'),
                          onPressed: () => _copyTracking(l10n, pkg),
                          child: Text(l10n.shopOrderCopy),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.xxs),
              Text(
                  pkg.delivered
                      ? l10n.shopOrderPackageDelivered
                      : l10n.shopOrderPackageInTransit,
                  key: ValueKey('shopOrderPackageState_$index'),
                  style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: pkg.delivered ? AppColors.mint600 : AppColors.muted)),
              const SizedBox(height: AppSpacing.sm),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  key: ValueKey('shopOrderTrack_$index'),
                  onPressed: () => _openCarrierSite(l10n, pkg),
                  child: Text(l10n.shopOrderTrackOnCarrierSite),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _confirmBar(AppLocalizations l10n) => SafeArea(
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.lg),
          decoration: const BoxDecoration(
            color: AppColors.cream,
            border: Border(top: BorderSide(color: AppColors.line)),
          ),
          child: SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('shopOrderConfirmReceipt'),
              onPressed: _busy ? null : () => _confirmReceipt(l10n),
              child: Text(l10n.shopOrderConfirmReceipt),
            ),
          ),
        ),
      );

  // ---------- 动作 ----------

  Future<void> _copyTracking(AppLocalizations l10n, ShopOrderPackage pkg) async {
    await Clipboard.setData(ClipboardData(text: pkg.trackingNo));
    if (!mounted) return;
    Analytics.capture('toko_order_tracking_copy_tapped');
    showAppToast(context, l10n.shopOrderCopied);
  }

  /// 🔴 跳承运商官网（FR-103）—— App 内不渲染轨迹。
  /// 用 [LaunchMode.externalApplication] 交给系统浏览器：站内 WebView 会让用户以为
  /// 这仍是我们的页面，而承运商页面上的任何异常都会算到我们头上。
  Future<void> _openCarrierSite(AppLocalizations l10n, ShopOrderPackage pkg) async {
    Analytics.capture('toko_order_tracking_site_tapped');
    final uri = Uri.tryParse(pkg.trackingUrl);
    final ok = uri == null
        ? false
        : await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!ok && mounted) showAppToast(context, l10n.shopOrderTrackOpenFailed);
  }

  /// 确认收货。
  ///
  /// 🔴 **要二次确认**：确认后订单直接完成，且它会写下签收时刻 —— 退货窗口从那一刻起算。
  /// 弹窗正文必须写明「之后 7 天仍可申请退货」，否则用户会因为怕失去退货权而不敢点，
  /// 于是订单又靠自动确认才脱离已发货态。
  Future<void> _confirmReceipt(AppLocalizations l10n) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dlgCtx) => AlertDialog(
        key: const ValueKey('shopOrderConfirmReceiptDialog'),
        title: Text(l10n.shopOrderConfirmReceiptTitle),
        content: Text(l10n.shopOrderConfirmReceiptBody),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dlgCtx).pop(false),
            child: Text(l10n.shopOrderConfirmReceiptNo),
          ),
          FilledButton(
            key: const ValueKey('shopOrderConfirmReceiptYes'),
            onPressed: () => Navigator.of(dlgCtx).pop(true),
            child: Text(l10n.shopOrderConfirmReceiptYes),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    // 🔴 「点了」与「成功了」是两个事件：确认收货会失败（网络 / 状态已变），
    //    只埋一个的话，漏斗上分不清是没人点还是点了没成。
    Analytics.capture('toko_order_receipt_confirm_tapped');
    setState(() => _busy = true);
    try {
      await ref.read(shopOrderRepositoryProvider).confirmReceipt(widget.orderToken);
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      Analytics.capture('toko_order_receipt_confirm_succeeded');
      showAppToast(context, l10n.shopOrderReceiptConfirmed);
    } catch (_) {
      if (mounted) showAppToast(context, l10n.shopOrderConfirmReceiptFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pay(AppLocalizations l10n, ShopOrderDetail order) async {
    Analytics.capture('toko_order_pay_tapped');
    setState(() => _busy = true);
    try {
      final result = await ref.read(shopOrderRepositoryProvider).pay(widget.orderToken);
      if (!mounted) return;
      if (result.settledImmediately) {
        // 纯 PawCoin：当场结清，没有二维码。余额缓存失效，免得别处读到旧余额。
        ref.invalidate(pawCoinProvider);
        ref.invalidate(shopOrderDetailProvider(widget.orderToken));
        Analytics.capture('toko_order_payment_succeeded', {'pay_channel': 'PAWCOIN'});
        showAppToast(context, l10n.shopOrderPaid);
        return;
      }
      final paid = await showQrPaymentSheet(
        context,
        payload: result.payload!,
        orderRef: order.orderToken,
        // 轮询问的是订单本身的状态 —— 到账由服务端在回调里推进，客户端不自行判定
        pollPaid: () async {
          final fresh = await ref
              .refresh(shopOrderDetailProvider(widget.orderToken).future);
          return !fresh.status.isPendingPayment;
        },
      );
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      ref.invalidate(pawCoinProvider);
      if (paid) {
        Analytics.capture(
            'toko_order_payment_succeeded', {'pay_channel': order.payChannel ?? 'UNKNOWN'});
        showAppToast(context, l10n.shopOrderPaid);
      }
    } catch (_) {
      if (mounted) {
        // 🔴 报的是「向用户展示了失败」——失败本身可能只是网络抖动，
        //    而漏斗上要看的是有多少人在这一步被挡住。
        Analytics.capture('toko_order_payment_failed_shown');
        showAppToast(context, l10n.shopOrderPayFailed);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 🔴 取消要二次确认：库存会被释放且订单不可恢复 —— 这是不可逆动作，
  /// 而「去支付」与「取消」两个按钮就挨着。
  Future<void> _cancel(AppLocalizations l10n) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dlgCtx) => AlertDialog(
        key: const ValueKey('shopOrderCancelDialog'),
        title: Text(l10n.shopOrderCancelConfirm),
        content: Text(l10n.shopOrderCancelConfirmBody),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dlgCtx).pop(false),
            child: Text(l10n.shopOrderCancelConfirmNo),
          ),
          FilledButton(
            key: const ValueKey('shopOrderCancelConfirmYes'),
            onPressed: () => Navigator.of(dlgCtx).pop(true),
            child: Text(l10n.shopOrderCancelConfirmYes),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    Analytics.capture('toko_order_cancel_tapped');
    setState(() => _busy = true);
    try {
      await ref.read(shopOrderRepositoryProvider).cancel(widget.orderToken);
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      // 取消会把库存还回去，购物车角标不受影响，但订单相关缓存要刷
      ref.invalidate(cartProvider);
      showAppToast(context, l10n.shopOrderCancelled);
    } catch (_) {
      if (mounted) showAppToast(context, l10n.shopOrderPayFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _section(String text) => Padding(
        padding: const EdgeInsets.fromLTRB(
            AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.sm),
        child: Text(text, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
      );

  /// 只到日：退货窗口是个日期概念，给到分秒只会让用户去数小时。
  static String _ymd(DateTime d) {
    final mm = d.month.toString().padLeft(2, '0');
    final dd = d.day.toString().padLeft(2, '0');
    return '${d.year}-$mm-$dd';
  }

  static String _mmss(Duration d) {
    final m = d.inMinutes.toString().padLeft(2, '0');
    final s = (d.inSeconds % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }
}
