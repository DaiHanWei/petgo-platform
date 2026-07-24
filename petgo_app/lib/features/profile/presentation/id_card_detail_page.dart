import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:gal/gal.dart';
import 'package:share_plus/share_plus.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/id_card_repository.dart';
import '../domain/id_card.dart';
import 'id_card/hd_paywall_sheet.dart';
import 'id_card/ktp_card.dart';
import 'id_card/ktp_fields.dart';
import 'id_card/passport_card.dart';
import 'id_card/student_card.dart';

/// 单张身份证卡详情（Story 6-7）。渲染冻结快照卡面 + 按卡付费解锁 HD + 保存到相册/分享。
/// 快照不可编辑（建卡时定格）；下载/解锁按本卡 [cardId] 走多卡端点。
class IdCardDetailPage extends ConsumerStatefulWidget {
  const IdCardDetailPage({super.key, required this.cardId});

  final int cardId;

  @override
  ConsumerState<IdCardDetailPage> createState() => _IdCardDetailPageState();
}

class _IdCardDetailPageState extends ConsumerState<IdCardDetailPage> {
  bool _hdBusy = false;
  final GlobalKey idCardBoundaryKey = GlobalKey();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(idCardDetailProvider(widget.cardId));
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(title: Text(l10n.idCardTitle)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
            child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(l10n.idCardLoadError, textAlign: TextAlign.center))),
        data: (card) => _view(l10n, card),
      ),
    );
  }

  Widget _view(AppLocalizations l10n, IdCard card) {
    final data = card.toIdCardData();
    // 按卡种分发卡面 + 画布尺寸（Story 6-8）。
    final (Size canvas, Widget cardFront) = switch (card.cardType) {
      'PASSPORT' => (kPassportCardCanvas, PassportCardFront(fields: buildPassportFields(data))),
      'STUDENT' => (kStudentCardCanvas, StudentCardFront(fields: buildStudentFields(data))),
      _ => (kIdCardCanvas, KtpCardFront(fields: buildKtpFields(data, KtpEdits.empty))),
    };
    return SafeArea(
      child: Column(
        children: [
          Expanded(
            child: Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: AspectRatio(
                  aspectRatio: canvas.width / canvas.height,
                  child: RepaintBoundary(
                    key: idCardBoundaryKey,
                    child: FittedBox(
                      fit: BoxFit.contain,
                      child: cardFront,
                    ),
                  ),
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.only(bottom: 2),
                  child: Text(
                    '${l10n.idCardDisclaimerTitle} · ${l10n.idCardDisclaimerBody}',
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.ink2, fontSize: 11, height: 1.35),
                  ),
                ),
                if (card.hdUnlocked)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.check_circle_rounded, size: 16, color: AppColors.mint),
                        const SizedBox(width: 5),
                        Text(l10n.idCardHdUnlockedBadge,
                            style: const TextStyle(
                                fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.mint)),
                      ],
                    ),
                  ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(backgroundColor: AppColors.mint),
                    onPressed: _hdBusy ? null : () => _onDownloadHd(card),
                    icon: _hdBusy
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                        : const Icon(Icons.download),
                    label: Text(card.hdUnlocked ? l10n.idCardDownloadHd : l10n.idCardUnlockHd),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
        ],
      ),
    );
  }

  Future<void> _onDownloadHd(IdCard card) async {
    if (card.hdUnlocked) {
      await _exportHd();
    } else {
      await _openHdPaywall(card);
    }
  }

  Future<void> _openHdPaywall(IdCard card) async {
    int balance = 0;
    try {
      balance = (await ref.read(pawCoinProvider.future)).balance;
    } catch (_) {
      balance = 0;
    }
    if (!mounted) return;
    final channel = await showModalBottomSheet<HdPayChannel>(
      context: context,
      backgroundColor: AppColors.card,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => HdPaywallSheet(
        petName: card.name,
        serialId: card.serialId,
        avatarUrl: card.avatarUrl,
        balance: balance,
      ),
    );
    if (channel == null || !mounted) return;
    await _purchaseHd(channel);
  }

  Future<void> _purchaseHd(HdPayChannel channel) async {
    setState(() => _hdBusy = true);
    final l10n = AppLocalizations.of(context);
    try {
      final res = await ref.read(idCardRepositoryProvider).purchaseHdForCard(widget.cardId, channel);
      if (res.unlocked) {
        ref.invalidate(idCardDetailProvider(widget.cardId));
        ref.invalidate(idCardListProvider);
        _toast(l10n.idCardHdUnlockedToast);
        await _exportHd();
      } else if ((res.payload?.isNotEmpty ?? false) && mounted) {
        final bool paid = await showQrPaymentSheet(
          context,
          payload: res.payload!,
          orderRef: res.paymentToken,
          pollPaid: () async {
            final card = await ref.refresh(idCardDetailProvider(widget.cardId).future);
            return card.hdUnlocked;
          },
        );
        if (paid && mounted) {
          ref.invalidate(idCardDetailProvider(widget.cardId));
          ref.invalidate(idCardListProvider);
          _toast(l10n.idCardHdUnlockedToast);
          await _exportHd();
        }
      } else {
        _toast(l10n.idCardHdQrisPending);
      }
    } on DioException catch (e) {
      _toast(e.response?.statusCode == 409
          ? l10n.idCardHdInsufficientBalance
          : l10n.idCardHdPurchaseError);
    } catch (_) {
      _toast(l10n.idCardHdPurchaseError);
    } finally {
      if (mounted) setState(() => _hdBusy = false);
    }
  }

  /// 导出卡为 PNG → 弹选单：保存到相册 / 分享（bug 20260721-334）。
  Future<void> _exportHd() async {
    final l10n = AppLocalizations.of(context);
    try {
      final boundary =
          idCardBoundaryKey.currentContext?.findRenderObject() as RenderRepaintBoundary?;
      if (boundary == null) return;
      final box = context.findRenderObject() as RenderBox?;
      final origin = box != null ? box.localToGlobal(Offset.zero) & box.size : null;
      final pixelRatio = kIdCardCanvas.width / boundary.size.width;
      final ui.Image image = await boundary.toImage(pixelRatio: pixelRatio);
      final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
      if (byteData == null) return;
      final bytes = byteData.buffer.asUint8List();
      if (!mounted) return;
      await _showExportSheet(bytes, origin);
    } catch (_) {
      if (mounted) _toast(l10n.idCardHdExportError);
    }
  }

  Future<void> _showExportSheet(Uint8List bytes, Rect? shareOrigin) async {
    final l10n = AppLocalizations.of(context);
    await showModalBottomSheet<void>(
      context: context,
      builder: (sheetCtx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              key: const ValueKey('idCardSaveToGallery'),
              leading: const Icon(Icons.download_rounded),
              title: Text(l10n.idCardSaveToGallery),
              onTap: () {
                Navigator.of(sheetCtx).pop();
                _saveToGallery(bytes);
              },
            ),
            ListTile(
              key: const ValueKey('idCardShareImage'),
              leading: const Icon(Icons.ios_share_rounded),
              title: Text(l10n.idCardShareImage),
              onTap: () {
                Navigator.of(sheetCtx).pop();
                _shareImageBytes(bytes, shareOrigin);
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _saveToGallery(Uint8List bytes) async {
    final l10n = AppLocalizations.of(context);
    try {
      await Gal.putImageBytes(bytes, name: 'tailtopia_id_card');
      if (mounted) _toast(l10n.idCardSavedToGallery);
    } catch (_) {
      if (mounted) _toast(l10n.idCardSaveError);
    }
  }

  Future<void> _shareImageBytes(Uint8List bytes, Rect? origin) async {
    await Share.shareXFiles(
      [XFile.fromData(bytes, name: 'tailtopia_id_card.png', mimeType: 'image/png')],
      sharePositionOrigin: origin,
    );
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }
}
