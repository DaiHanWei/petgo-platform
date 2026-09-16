import 'dart:async';

import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';

/// 通用 QRIS 二维码支付面板（AI 解锁 / 身份证HD 等现金一次性购买复用）。
///
/// 展示后端下发的 EMVCo 二维码串（本地生成，照 recharge/vet_timed_pay 范式）+ 每 3s 轮询到账。
/// [pollPaid] 抛出本异常 = 付款已不可能完成（订单被取消 / 付款窗到期）：
/// 弹层关闭并 resolve `false`，与用户主动取消同路，**不**当作到账。
class QrPaymentAborted implements Exception {
  /// 🔴 **无参 const 构造必须保留**：三个不在本次改造范围内的调用点（AI 解锁 /
  /// 高清身份证创建页 / 高清身份证详情页）以及电商侧的兜底分支都在用 `const QrPaymentAborted()`。
  const QrPaymentAborted([this.category]);

  /// 中止原因（Story 1-3，电商侧传 `PaymentFailureCategory` 的字面量）。
  ///
  /// 刻意用 `String?` 而不是某个业务枚举：本组件在 `shared/` 下，不该知道 shop 的领域类型。
  /// 调用方自己把它解回枚举。
  final String? category;
}

/// [pollPaid] 返回 true 即到账 → 关闭并 resolve `true`；用户取消 → resolve `false`（纯关闭，
/// 不调后端——这些场景 pending 可复用重复支付、不清理，下次再发起复用同 intent）。
///
/// 🔴 **返回类型恒为 `Future<bool>`**（Story 1-3 AC7）：改成枚举/记录会逼 4 个调用点全改，
/// 其中 3 个不在本次改造范围内 —— 风险 G-4「支付面板改动波及其它链路」说的就是这个。
/// 新能力一律走「可选具名参数 + 默认关闭」。
///
/// [onAborted] 仅在 [pollPaid] **抛出** [QrPaymentAborted] 时回调一次（关闭之前）。
/// 🔴 用户点面板上的取消按钮**不会**触发它 —— 「付款已不可能完成」与「我先关掉待会再付」
/// 是两件事，混在一起就会把「关个面板」显示成「订单没了」。
Future<bool> showQrPaymentSheet(
  BuildContext context, {
  required String payload,
  required Future<bool> Function() pollPaid,
  String? orderRef,
  void Function(QrPaymentAborted abort)? onAborted,
}) async {
  final bool? result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    showDragHandle: true,
    builder: (BuildContext ctx) => _QrPaymentSheet(
        payload: payload, pollPaid: pollPaid, orderRef: orderRef, onAborted: onAborted),
  );
  return result ?? false;
}

class _QrPaymentSheet extends StatefulWidget {
  const _QrPaymentSheet(
      {required this.payload, required this.pollPaid, this.orderRef, this.onAborted});

  final String payload;
  final Future<bool> Function() pollPaid;

  /// 支付号（bug 326）：客服对账用，展示在二维码下方（可空）。
  final String? orderRef;

  /// 见 [showQrPaymentSheet] 的同名参数。默认 null＝行为与改动前完全一致。
  final void Function(QrPaymentAborted abort)? onAborted;

  @override
  State<_QrPaymentSheet> createState() => _QrPaymentSheetState();
}

class _QrPaymentSheetState extends State<_QrPaymentSheet> {
  Timer? _poll;
  bool _closing = false;

  @override
  void initState() {
    super.initState();
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => _tick());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  bool _inFlight = false;

  Future<void> _tick() async {
    if (_closing || _inFlight) return; // 慢网时不让多次轮询并发堆叠
    _inFlight = true;
    try {
      final bool paid = await widget.pollPaid();
      if (!mounted || _closing) return;
      if (paid) {
        _closing = true;
        _poll?.cancel();
        Navigator.of(context).pop(true);
      }
    } on QrPaymentAborted catch (abort) {
      if (!mounted || _closing) return;
      _closing = true;
      _poll?.cancel();
      // 在 pop 之前回调：调用方据此区分「付款已不可能完成」与「用户自己关掉了面板」——
      // 两者都 resolve false，中止信号是唯一可靠的判据。
      widget.onAborted?.call(abort);
      Navigator.of(context).pop(false);
    } catch (_) {
      // 忽略单次轮询失败，下个 tick 再试。
    } finally {
      _inFlight = false;
    }
  }

  void _cancel() {
    if (_closing) return;
    _closing = true;
    _poll?.cancel();
    Navigator.of(context).pop(false);
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 4, 24, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(l10n.vetPayProcessing,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 6),
            Text(l10n.vetPayScanHint,
                style: const TextStyle(fontSize: 12, color: AppColors.muted)),
            const SizedBox(height: 20),
            Container(
              width: 240,
              height: 240,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.line),
              ),
              child: QrImageView(
                key: const ValueKey('qrPayImage'),
                data: widget.payload,
                version: QrVersions.auto,
                backgroundColor: Colors.white,
              ),
            ),
            // 支付号（bug 326）：客服对账用，可长按复制。
            if ((widget.orderRef ?? '').isNotEmpty) ...[
              const SizedBox(height: 12),
              SelectableText('${l10n.orderNumberLabel}: ${widget.orderRef}',
                  key: const ValueKey('qrPayOrderRef'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 12, color: AppColors.muted)),
            ],
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                key: const ValueKey('qrPayCancel'),
                onPressed: _closing ? null : _cancel,
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.danger,
                  side: const BorderSide(color: AppColors.danger),
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text(l10n.vetPayCancel,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
