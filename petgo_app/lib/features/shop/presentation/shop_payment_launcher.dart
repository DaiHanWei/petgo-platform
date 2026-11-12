/// 电商支付启动器 —— **单一入口**（v1.4.2 shop Story 7.2 · AC8）。
///
/// 订单详情「Bayar Sekarang」、结算后 `?autopay=1`（7.1）与 9.1 的订单列表「去支付」/ 详情 v3
/// 都调 [launchShopPayment]，面板形态（E1–E7）、轮询、四态分派与全部埋点只在这里写一份。
///
/// 🔴 逻辑由 `ShopOrderDetailPageV2._pay` / `_onPaymentAborted` **原样搬来**：既有事件
/// （`toko_order_pay_tapped`、`toko_payment_retry_tapped`、`toko_payment_qr_shown`、
/// `toko_payment_sheet_dismissed`、`toko_payment_declined_shown`、`toko_payment_expired_shown`、
/// `toko_order_payment_failed_shown`、`toko_order_payment_succeeded`）事件名、属性、触发条件一律不变，
/// toast 文案不变；唯一的改变是到账不再弹 `shopOrderPaid` toast —— 启动器只**返回结果**，
/// 由调用方决定跳 E6（订单详情 `pushReplacement`，列表可 `push`）。
///
/// 🔴 共用面板 `showQrPaymentSheet` 的电商形态全部经可选参数传入（P-11），面板内部零埋点。
library;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../growth/domain/growth_popup_trigger.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/shop_order_repository.dart';
import '../domain/shop_order_detail.dart';

/// 一次支付启动的结局。
enum ShopPaymentOutcome {
  /// 纯 PawCoin：`/pay` 当场结清，没有二维码。
  settledByCoin,

  /// QRIS 到账（轮询或「Saya sudah bayar」确认）。
  paid,

  /// 用户自己关了面板（出过码、未完成）。
  dismissed,

  /// 网关拒付（订单仍可重试）。
  declined,

  /// 付款窗超时（订单已取消）。
  expired,

  /// 用户在别处取消了订单。
  userCancelled,

  /// 中止了但没带类别（老后端兜底）。
  abortedUnknown,

  /// 中止且带了 App 不认识的失败类别（后端新增）：按通用失败提示。
  /// 与 [failed] 分开：原实现在这一支会把「上次被拒」清掉，`/pay` 抛错则不清（AC8 触发条件逐字一致）。
  abortedUnknownCategory,

  /// 通用失败：`/pay` 抛错（非取码失败），或 E7 取码失败后关面板。
  failed,
}

/// 支付类事件的属性（Story 1-4 AC3）。**只有这四个键。**
///
/// 🔒 绝不放 `order.orderToken` —— 订单号对运营有用但对漏斗没用，且它是对外标识，
/// 进第三方等于把标识面扩出去（SHOP-NFR-01）。要排查有后端接口日志。
/// 🔒 也绝不放 `receiverName` / `receiverPhone` / `addressText` —— 它们就在同一个
/// `ShopOrderDetail` 上，离得最近、最容易手滑。
Map<String, Object> shopPayProps(ShopOrderDetail order, {String? failureCategory}) => {
      // 与既有 toko_order_payment_succeeded 同一写法，保持口径一致。
      'pay_channel': order.payChannel ?? 'UNKNOWN',
      'attribution_source': order.attributionSource,
      'has_pawcoin': (order.coinAmount ?? 0) > 0,
      'failure_category': ?failureCategory,
    };

/// `toko_order_pay_tapped` 的 `items[]`（analytics-pipeline Story 3.4）：每行 `{product_token, sku_id}`，
/// 与 `toko_order_submitted.items[]` 同键名，埋点漏斗「进入支付」一步按商品关联。
///
/// 🔒 只有商品 / SKU 的公开 token（不可枚举，与个人无关）；绝不放订单号、商品名、价格。
/// 后端未下发 token（旧后端 / SKU 已物理删除）的行跳过；一行都没有时返回 null → 整键不带。
List<Map<String, Object>>? shopPayTappedItems(ShopOrderDetail order) {
  final items = [
    for (final l in order.lines)
      if (l.productToken != null || l.skuToken != null)
        {
          'product_token': ?l.productToken,
          'sku_id': ?l.skuToken,
        },
  ];
  return items.isEmpty ? null : items;
}

/// 现在真要扫码付的现金段（币段下单时已冻结）。非混合单 `cashAmount` 为 null → 总额减币段。
int shopCashSegment(ShopOrderDetail order) =>
    order.cashAmount ?? (order.totalAmount - (order.coinAmount ?? 0));

/// `/pay` 失败的分类（E7）。
enum _CodeIssue {
  /// 网络错误 / 5xx（网关取码失败）：E7 + 「Coba lagi」。
  retryable,

  /// 409 `shop-order-not-payable`（建单超 10 分钟不再出码 / 已过付款时间）：E7，不给重试。
  closed,
}

_CodeIssue? _codeIssueOf(Object e) {
  if (e is! DioException) return null;
  final status = e.response?.statusCode;
  if (status == null) {
    // 没拿到响应 = 网络层失败（超时 / 断网）。取消请求不算。
    return e.type == DioExceptionType.cancel ? null : _CodeIssue.retryable;
  }
  if (status >= 500) return _CodeIssue.retryable;
  if (status == 409 && ProblemDetail.fromDioException(e)?.typeSlug == 'shop-order-not-payable') {
    return _CodeIssue.closed;
  }
  return null;
}

/// 拉起电商支付（`/pay` → 纯币结清 / QRIS 面板 E1–E7 → 四态分派）。
///
/// [isRetry]：这一次点支付算不算「被拒后重试」—— 由调用方给（它持有本页记忆 + 服务端失败类别）。
///
/// 🔴 本函数不跳路由；到账后跳 E6 由调用方按返回值做。
Future<ShopPaymentOutcome> launchShopPayment(
  BuildContext context,
  WidgetRef ref,
  ShopOrderDetail order, {
  required bool isRetry,
}) async {
  final l10n = AppLocalizations.of(context);
  final token = order.orderToken;
  final repo = ref.read(shopOrderRepositoryProvider);

  // 🔴 既有事件照发不误：漏斗「进入支付」这一格的分母靠它，重试也是一次进入支付。
  //    触发时机不变；analytics-pipeline Story 3.4 只加 items[]（漏斗按商品关联）。
  final payItems = shopPayTappedItems(order);
  //    取不到任何 token（旧后端）时与原先一样不带属性。
  Analytics.capture('toko_order_pay_tapped', payItems == null ? null : {'items': payItems});
  if (isRetry) {
    Analytics.capture('toko_payment_retry_tapped', shopPayProps(order));
  }

  ShopPayResult? result;
  _CodeIssue? codeIssue;
  try {
    result = await repo.pay(token);
  } catch (e) {
    codeIssue = _codeIssueOf(e);
    if (codeIssue == null) {
      if (context.mounted) {
        Analytics.capture('toko_order_payment_failed_shown');
        showAppToast(context, l10n.shopOrderPayFailed);
      }
      return ShopPaymentOutcome.failed;
    }
  }
  if (!context.mounted) return ShopPaymentOutcome.failed;

  if (result != null && result.settledImmediately) {
    // 纯 PawCoin：当场结清，没有二维码。余额缓存失效，免得别处读到旧余额。
    ref.invalidate(pawCoinProvider);
    ref.invalidate(shopOrderDetailProvider(token));
    Analytics.capture('toko_order_payment_succeeded',
        {'pay_channel': 'PAWCOIN', 'attribution_source': order.attributionSource});
    growthPopupTrigger.afterAction(); // V1.4.0 ①：消费返成长值后可能升级
    return ShopPaymentOutcome.settledByCoin;
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
  // 出过码（首次或 E7 重试成功）才有「出码后自己走掉」可言。
  bool qrShown = false;
  // 🔴 从**电商侧**发，不跑去 sheet 内部发：sheet 是共用组件，在里面埋点会给
  //    AI 解锁与高清身份证两条线凭空多出事件（AC6）。
  //    纯 PawCoin 单不出码，上面已 return，走不到这里。
  if (codeIssue == null) {
    Analytics.capture('toko_payment_qr_shown', shopPayProps(order));
    qrShown = true;
  } else {
    // E7：取码失败仍沿用原失败事件（不再只弹 toast，改为以失败态打开面板）。
    Analytics.capture('toko_order_payment_failed_shown');
  }
  final coin = order.coinAmount ?? 0;
  final paid = await showQrPaymentSheet(
    context,
    payload: result?.payload ?? '',
    // V1.4.1 QRIS 支付页：展示可读订单号（TOKO-…），不再是 22 位内部 token。
    orderRef: order.displayNo.isNotEmpty ? order.displayNo : order.orderToken,
    amountIdr: shopCashSegment(order),
    // 🔴 S-1：App 倒计时仍按订单 expiresAt（建单 + 60 分钟），不显示码的 65 分钟。
    expiresAt: order.expiresAt,
    title: l10n.qrPaySheetTitle,
    amountLabel: l10n.qrPayViaQrisLabel,
    coinHeldIdr: coin > 0 ? coin : null,
    showPaidCheck: true,
    // P-3：到账以服务端为准。
    onPaidCheck: () async => (await repo.paymentCheck(token)).paid,
    notPaidMessage: l10n.qrPayNotPaidYet,
    hideCancel: true,
    collapsibleGuide: true,
    adaptiveHeight: true,
    savedToastMessage: l10n.qrPaySavedOpenWallet,
    // 存图**成功后**才报（无属性）；共用组件内部不埋点。
    onSaved: () => Analytics.capture('toko_payment_qr_saved'),
    expiredBody: l10n.shopOrderExpiredNotice,
    codeFailed: codeIssue != null,
    onRetryCode: codeIssue == _CodeIssue.retryable
        ? () async {
            final r = await repo.pay(token);
            if (r.settledImmediately) return null; // 现金单不该出现；保持 E7 不猜
            Analytics.capture('toko_payment_qr_shown', shopPayProps(order));
            qrShown = true;
            return r.payload;
          }
        : null,
    // 重试时服务端已不再出码（409 shop-order-not-payable）→ 面板切到无重试的 E7。
    isRetryClosed: (e) => _codeIssueOf(e) == _CodeIssue.closed,
    onAborted: (abort) {
      abortSignalled = true;
      aborted = ShopPaymentFailure.fromApi(abort.category);
    },
    // 轮询问的是订单本身的状态 —— 到账由服务端在回调里推进，客户端不自行判定。
    pollPaid: () async {
      final fresh = await ref.refresh(shopOrderDetailProvider(token).future);
      // 🔴 先看支付单：网关拒付**只改 payment_intents、不改订单**，订单会一直停在
      //    PENDING_PAYMENT，只看 status 的话二维码要挂到 60 分钟窗口耗尽。
      final failure = fresh.paymentFailure;
      // UI E7（v1.4.2 还原度）：取码就失败（面板正停在「Kode QR gagal dibuat · Coba lagi」、还没出过码）时，
      // 网关拒付**不中止面板** —— 拒付的正是这次取码，用户要的是留在面板里点「Coba lagi」，
      // 而不是 3 秒后面板自己消失。超时 / 取消仍照常中止（下面的判据不变）。
      final declinedWhileCodeFailed = codeIssue != null &&
          !qrShown &&
          ShopPaymentFailure.fromApi(fresh.paymentFailureCategory) ==
              ShopPaymentFailure.gatewayDeclined;
      if (failure != null && !declinedWhileCodeFailed) {
        throw QrPaymentAborted(fresh.paymentFailureCategory);
      }
      // 兜底：后端未升级或纯 PawCoin 单时两字段为 null，行为与改动前完全一致。
      // 🔴 S-7：中止判据用原始 status，**不用 displayStatus** —— 宽限期（60–65 分钟）displayStatus 已是
      //    CANCELLED，但这期间到账服务端会把订单转待发货，过期态面板的 10 秒轮询要能接住并进 E6。
      if (fresh.status == ShopOrderStatus.cancelled) {
        throw const QrPaymentAborted();
      }
      return fresh.status.isPaidOrLater;
    },
  );
  if (!context.mounted) return paid ? ShopPaymentOutcome.paid : ShopPaymentOutcome.failed;
  ref.invalidate(shopOrderDetailProvider(token));
  ref.invalidate(pawCoinProvider);
  if (paid) {
    // 投放归因：只计现金段（混合支付的 PawCoin 段在充值时已计）；非混合单 cashAmount 为 null → 全额现金。
    Analytics.capturePurchase(
      amountIdr: order.cashAmount ?? order.totalAmount,
      purpose: 'SHOP_ORDER',
      orderRef: order.orderToken,
    );
    growthPopupTrigger.afterAction(); // V1.4.0 ①：消费返成长值后可能升级
    Analytics.capture('toko_order_payment_succeeded', {
      'pay_channel': order.payChannel ?? 'UNKNOWN',
      'attribution_source': order.attributionSource,
    });
    // v1.4.2 Story 7.2 AC6：不再弹 shopOrderPaid toast —— 调用方跳 E6。
    return ShopPaymentOutcome.paid;
  }
  if (!abortSignalled && !qrShown) {
    // E7 取码失败后直接关面板：失败事件开面板时已发，这里不再发 sheet_dismissed（从没出过码）。
    return ShopPaymentOutcome.failed;
  }
  return _onPaymentAborted(context, l10n, order, aborted, abortSignalled: abortSignalled);
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
ShopPaymentOutcome _onPaymentAborted(BuildContext context, AppLocalizations l10n,
    ShopOrderDetail order, ShopPaymentFailure? aborted,
    {required bool abortSignalled}) {
  if (!abortSignalled) {
    // 仅关闭面板：订单原样不动，什么都不做。弹一句失败会让用户以为订单出事了。
    // 🔴 但**要埋点**：这一格在服务端没有任何对应事件（关面板不产生服务端状态变化），
    //    这正是它的价值 —— 「出码后自己走掉」只有客户端看得见。
    Analytics.capture('toko_payment_sheet_dismissed', shopPayProps(order));
    return ShopPaymentOutcome.dismissed;
  }
  if (aborted == null) {
    // 中止了但没带类别 = 老后端（1-1 未上线）。UI 上与改动前完全一致：静默关闭。
    // 🔴 埋点也**保持改动前的样子：什么都不发**。发 sheet_dismissed 是谎
    //    （不是用户走的），发 declined/expired 是猜（不知道到底哪一种）。
    //    灰度期这一格的数据由服务端 1-2 的 shop_payment_* 兜着，它不看 App 版本。
    return ShopPaymentOutcome.abortedUnknown;
  }
  switch (aborted) {
    case ShopPaymentFailure.gatewayDeclined:
      Analytics.capture('toko_payment_declined_shown',
          shopPayProps(order, failureCategory: ShopPaymentFailure.gatewayDeclined.api));
      // V1.4.2 验收修复（BUG-142-03）：长句 + 面板刚关，默认 2.6 秒读不完 → 4 秒。
      showAppToast(context, l10n.shopPaymentDeclinedNotice, duration: const Duration(seconds: 4));
      return ShopPaymentOutcome.declined;
    case ShopPaymentFailure.expired:
      Analytics.capture('toko_payment_expired_shown',
          shopPayProps(order, failureCategory: ShopPaymentFailure.expired.api));
      // 🔴 **不弹** shopOrderPayFailed —— 那是「再试一次」的口吻，而这一单已经没了。
      showAppToast(context, l10n.shopOrderExpiredNotice);
      return ShopPaymentOutcome.expired;
    case ShopPaymentFailure.userCancelled:
      // 是用户自己取消的，他知道发生了什么。只刷新，不弹错误。
      // 埋点走 toko_order_cancel_* 那套（那是用户的动作，不是失败反馈）。
      return ShopPaymentOutcome.userCancelled;
    case ShopPaymentFailure.unknown:
      // 后端加了 App 不认识的类别：按通用失败处理，不猜它该不该重试。
      Analytics.capture('toko_order_payment_failed_shown');
      showAppToast(context, l10n.shopOrderPayFailed);
      return ShopPaymentOutcome.abortedUnknownCategory;
  }
}
