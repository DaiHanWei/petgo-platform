import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';
import 'qr_payment/qr_pay_parts.dart';
import 'qr_payment/qr_pay_save.dart';

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
///
/// V1.4.1 QRIS 支付页（稿 A1–A5）：抽屉固定 60% 屏高、内部滚动、按钮吸底；
/// 加金额 [amountIdr]、倒计时（按 [expiresAt] 每秒算，同一笔单关掉再开剩余时间接着走）、
/// 可复制订单号、付款 App、付款步骤、「Simpan Kode QR」。两个新参数都可选：没传对应行不画。
/// 倒计时归零 → 抽屉自己进过期态（A4）并停轮询，只剩「Tutup」（resolve `false`，与取消同路；
/// 调用方原有的过期 Toast 照旧在关闭后弹）。
Future<bool> showQrPaymentSheet(
  BuildContext context, {
  required String payload,
  required Future<bool> Function() pollPaid,
  String? orderRef,
  void Function(QrPaymentAborted abort)? onAborted,
  int? amountIdr,
  DateTime? expiresAt,
}) async {
  final bool? result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    showDragHandle: true,
    backgroundColor: AppColors.card,
    builder: (BuildContext ctx) => _QrPaymentSheet(
        payload: payload,
        pollPaid: pollPaid,
        orderRef: orderRef,
        onAborted: onAborted,
        amountIdr: amountIdr,
        expiresAt: expiresAt),
  );
  return result ?? false;
}

class _QrPaymentSheet extends StatefulWidget {
  const _QrPaymentSheet(
      {required this.payload,
      required this.pollPaid,
      this.orderRef,
      this.onAborted,
      this.amountIdr,
      this.expiresAt});

  final String payload;
  final Future<bool> Function() pollPaid;

  /// 支付号（bug 326）：客服对账用，展示在二维码下方（可空）。
  final String? orderRef;

  /// 见 [showQrPaymentSheet] 的同名参数。默认 null＝行为与改动前完全一致。
  final void Function(QrPaymentAborted abort)? onAborted;

  final int? amountIdr;
  final DateTime? expiresAt;

  @override
  State<_QrPaymentSheet> createState() => _QrPaymentSheetState();
}

class _QrPaymentSheetState extends State<_QrPaymentSheet> {
  Timer? _poll;
  Timer? _clock;
  bool _closing = false;
  bool _expired = false;

  Duration? get _remaining {
    final exp = widget.expiresAt;
    if (exp == null) return null;
    final d = exp.toUtc().difference(DateTime.now().toUtc());
    return d.isNegative ? Duration.zero : d;
  }

  @override
  void initState() {
    super.initState();
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => _tick());
    if (widget.expiresAt != null) {
      // 1 秒心跳只为刷新倒计时与判定过期（照 recharge_page 的 _displayTimer）；到账仍以轮询为准。
      _clock = Timer.periodic(const Duration(seconds: 1), (_) => _onClock());
      if (_remaining == Duration.zero) _expire();
    }
  }

  void _onClock() {
    if (!mounted || _closing) return;
    if (!_expired && _remaining == Duration.zero) {
      _expire();
    } else {
      setState(() {});
    }
  }

  void _expire() {
    _expired = true;
    _clock?.cancel();
    // 🔴 过期后**不停**查到账，只降频：最后几秒扫码付款时，网关回调可能晚于截止时刻才到；
    //    停了就会出现「钱已到账、抽屉却一直显示已过期」。查到已付照常 pop(true) 进成功态。
    _poll?.cancel();
    _poll = Timer.periodic(const Duration(seconds: 10), (_) => _tick());
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _poll?.cancel();
    _clock?.cancel();
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
        _clock?.cancel();
        Navigator.of(context).pop(true);
      }
    } on QrPaymentAborted catch (abort) {
      if (!mounted || _closing) return;
      _closing = true;
      _poll?.cancel();
      _clock?.cancel();
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
    _clock?.cancel();
    Navigator.of(context).pop(false);
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    // 抽屉固定 60% 屏高（稿 A1，含顶部拖拽条），内容在内部滚动，按钮区吸底。
    // showDragHandle 的拖拽条占 kMinInteractiveDimension（48）高，在本 SizedBox 之外，这里扣掉。
    final double height = MediaQuery.sizeOf(context).height * 0.6 - kMinInteractiveDimension;
    return SizedBox(
      height: height,
      child: Column(
        children: <Widget>[
          Expanded(
            child: SingleChildScrollView(
              key: const ValueKey('qrPayScroll'),
              padding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  QrPaySummary(
                    density: QrPayDensity.sheet,
                    amountIdr: widget.amountIdr,
                    remaining: _remaining,
                  ),
                  const SizedBox(height: 10),
                  QrPayCodeCard(
                    density: QrPayDensity.sheet,
                    payload: widget.payload,
                    orderRef: widget.orderRef,
                    expired: _expired,
                  ),
                  QrPayGuide(expiresAt: widget.expiresAt),
                ],
              ),
            ),
          ),
          QrPayBottomBar(
            density: QrPayDensity.sheet,
            expired: _expired,
            onClose: _cancel,
            onSave: (_closing || widget.payload.isEmpty)
                ? null
                : () => saveQrPaymentImage(context,
                    payload: widget.payload,
                    amountIdr: widget.amountIdr,
                    orderRef: widget.orderRef,
                    expiresAt: widget.expiresAt),
            onCancel: _closing ? null : _cancel,
            cancelLabel: l10n.vetPayCancel,
          ),
        ],
      ),
    );
  }
}
