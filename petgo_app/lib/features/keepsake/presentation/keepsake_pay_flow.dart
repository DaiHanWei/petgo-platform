import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../../profile/domain/id_card.dart';
import '../domain/keepsake_purchase_result.dart';

/// [runKeepsakePurchase] 的结局。
enum KeepsakeFlowOutcome {
  /// 本次解锁成功（PawCoin 当场成交 / QRIS 轮询到账）。
  unlocked,

  /// 服务端回 409 `keepsake-already-unlocked`：此前已解锁（如先前关掉面板后到账）→ 调用方静默刷新。
  alreadyUnlocked,

  /// 没完成：关掉抽屉 / 关掉二维码面板未付 / 失败（失败已提示）。页面保持锁态，不再报错。
  notCompleted,
}

/// 一次性解锁的购买流程（V1.3.2 Story 3.2 · AC6.3；3.4 / 3.5 复用）：读余额 → 选渠道抽屉 → 发起 →
/// PawCoin 即得 / QRIS 面板轮询。范式照 KTP 详情页 `_openHdPaywall` / `_purchaseHd`（那边不改）。
///
/// [sheet] 以余额构造抽屉（通常是 `PayChannelPicker`），确认时 `pop(HdPayChannel)`。
/// [start] 调 SKU 的发起接口；[pollPaid] 刷新业务对象、返回是否已解锁。
/// [purchasePurpose] / [cashPriceIdr]：QRIS 现金到账时上报投放归因的付款事件（`Analytics.capturePurchase`）
/// 用的支付用途代号（与后端 `PaymentPurpose` 同名）和本次实付金额。**必填**——新接入的 SKU 不会漏报。
/// [cashPriceIdr] 是取值函数：到账那一刻再读定价（补差价等口径由调用方算好）。
///
/// 🔴 错误按 `ProblemDetail.typeSlug` 分流，**不要**「409 一律当余额不足」：
/// `keepsake-already-unlocked` 也是 409，把它说成余额不足会让已付款用户去充值。
Future<KeepsakeFlowOutcome> runKeepsakePurchase({
  required BuildContext context,
  required WidgetRef ref,
  required Widget Function(int balance) sheet,
  required Future<KeepsakePurchaseResult> Function(HdPayChannel channel) start,
  required Future<bool> Function() pollPaid,
  required String purchasePurpose,
  required int? Function() cashPriceIdr,
  void Function(HdPayChannel channel)? onChannelConfirmed,
}) async {
  int balance = 0;
  try {
    // 限时：余额读失败会被 Riverpod 自动重试，`future` 可能一直不 resolve；读不到按 0 处理（抽屉仍可选 QRIS）。
    balance = (await ref.read(pawCoinProvider.future).timeout(const Duration(seconds: 10))).balance;
  } catch (_) {
    balance = 0;
  }
  if (!context.mounted) return KeepsakeFlowOutcome.notCompleted;
  final channel = await showModalBottomSheet<HdPayChannel>(
    context: context,
    backgroundColor: AppColors.card,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => sheet(balance),
  );
  if (channel == null || !context.mounted) return KeepsakeFlowOutcome.notCompleted;
  onChannelConfirmed?.call(channel);
  final l10n = AppLocalizations.of(context);
  void toast(String msg) {
    if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  try {
    final res = await start(channel);
    if (!context.mounted) return KeepsakeFlowOutcome.notCompleted;
    if (res.unlocked) {
      // PawCoin 即时扣款：失效余额缓存，防他处读到旧余额（bug 20260806 同类）。
      ref.invalidate(pawCoinProvider);
      return KeepsakeFlowOutcome.unlocked;
    }
    final payload = res.payload;
    if (payload == null || payload.isEmpty) {
      toast(l10n.idCardHdPurchaseError);
      return KeepsakeFlowOutcome.notCompleted;
    }
    final paid = await showQrPaymentSheet(context, payload: payload, orderRef: res.paymentRef, pollPaid: pollPaid);
    if (paid) {
      // 投放归因：QRIS 现金到账（PawCoin 当场成交走上面的 unlocked 分支，不计——钱在充值时已计）。
      Analytics.capturePurchase(amountIdr: cashPriceIdr(), purpose: purchasePurpose, orderRef: res.paymentRef);
    }
    if (!paid || !context.mounted) return KeepsakeFlowOutcome.notCompleted;
    ref.invalidate(pawCoinProvider);
    return KeepsakeFlowOutcome.unlocked;
  } on DioException catch (e) {
    switch (ProblemDetail.fromDioException(e)?.typeSlug) {
      case 'keepsake-already-unlocked':
        return KeepsakeFlowOutcome.alreadyUnlocked;
      case 'pawcoin-insufficient':
        toast(l10n.idCardHdInsufficientBalance);
      default:
        toast(l10n.idCardHdPurchaseError);
    }
    return KeepsakeFlowOutcome.notCompleted;
  } catch (_) {
    toast(l10n.idCardHdPurchaseError);
    return KeepsakeFlowOutcome.notCompleted;
  }
}
