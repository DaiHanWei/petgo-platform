import 'dart:async';

import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';

/// 通用 QRIS 二维码支付面板（AI 解锁 / 身份证HD 等现金一次性购买复用）。
///
/// 展示后端下发的 EMVCo 二维码串（本地生成，照 recharge/vet_timed_pay 范式）+ 每 3s 轮询到账。
/// [pollPaid] 返回 true 即到账 → 关闭并 resolve `true`；用户取消 → resolve `false`（纯关闭，
/// 不调后端——这些场景 pending 可复用重复支付、不清理，下次再发起复用同 intent）。
Future<bool> showQrPaymentSheet(
  BuildContext context, {
  required String payload,
  required Future<bool> Function() pollPaid,
}) async {
  final bool? result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    showDragHandle: true,
    builder: (BuildContext ctx) => _QrPaymentSheet(payload: payload, pollPaid: pollPaid),
  );
  return result ?? false;
}

class _QrPaymentSheet extends StatefulWidget {
  const _QrPaymentSheet({required this.payload, required this.pollPaid});

  final String payload;
  final Future<bool> Function() pollPaid;

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

  Future<void> _tick() async {
    if (_closing) return;
    try {
      final bool paid = await widget.pollPaid();
      if (!mounted || _closing) return;
      if (paid) {
        _closing = true;
        _poll?.cancel();
        Navigator.of(context).pop(true);
      }
    } catch (_) {
      // 忽略单次轮询失败，下个 tick 再试。
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
