import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:gal/gal.dart';
import 'package:share_plus/share_plus.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/card_render/card_export.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/id_card_repository.dart';
import '../domain/id_card.dart';
import 'id_card/hd_paywall_sheet.dart';
import 'id_card/id_card_watermark.dart';
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
  bool _shareBusy = false;

  /// 当前卡面（bug 20260730-430：一卡一面，由快照 cardType 决定，不再提供三 Tab 切换）。
  /// 0=KTP, 1=Paspor, 2=Pelajar；在 [_view] 随卡数据赋值，供导出链（[_exportHd]）选画布。
  int _styleIndex = 0;
  final GlobalKey idCardBoundaryKey = GlobalKey();

  /// 含水印的外层 boundary（Story 18.2 · AC2）。
  ///
  /// 🔴 免费分享出去的必须是**带水印版**——无水印仍需付费，那是与既有付费点共存的前提。
  /// 水印层刻意盖在 [idCardBoundaryKey] **之外**（见 IdCardWatermark 的类注释：
  /// 挪进去会污染付费导出图），所以带水印的截图必须另用一个包住整个 Stack 的 boundary。
  final GlobalKey idCardWatermarkedBoundaryKey = GlobalKey();

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
    // bug 20260730-430：一卡一面——卡面由快照 cardType 决定（存量旧卡 null → KTP），
    // 不再提供三 Tab 视图切换；HD 解锁随之天然按单卡面计费。
    _styleIndex = switch (card.effectiveCardType) {
      'PASSPORT' => 1,
      'STUDENT' => 2,
      _ => 0,
    };
    final (Size canvas, double radius, Widget cardFront) = switch (_styleIndex) {
      1 => (
          kPassportCardCanvas,
          kPassportCardCanvasRadius,
          PassportCardFront(fields: buildPassportFields(data))
        ),
      2 => (
          kStudentCardCanvas,
          kStudentCardCanvasRadius,
          StudentCardFront(fields: buildStudentFields(data))
        ),
      _ => (
          kIdCardCanvas,
          kIdCardCanvasRadius,
          KtpCardFront(fields: buildKtpFields(data, KtpEdits.empty))
        ),
    };
    return SafeArea(
      child: Column(
        children: [
          const SizedBox(height: 12),
          Expanded(
            child: Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: AspectRatio(
                  aspectRatio: canvas.width / canvas.height,
                  // 防截图水印（bug 20260728-383）：水印是 RepaintBoundary 的 Stack 兄弟，
                  // 预览/截图恒带水印；HD 导出 toImage 只截 boundary 子树 → 导出图无水印。
                  child: Stack(
                    fit: StackFit.expand, // 卡面保持 AspectRatio 紧约束（loose 会让 FittedBox 撑到画布原尺寸）
                    children: [
                      // Story 18.2：外层 boundary 把水印一并包进来，供免费分享用（AC2）。
                      // 🛡 内层 idCardBoundaryKey 依旧只含卡面 —— 付费 HD 导出无水印，不受影响。
                      RepaintBoundary(
                        key: idCardWatermarkedBoundaryKey,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            RepaintBoundary(
                              key: idCardBoundaryKey,
                              child: FittedBox(
                                fit: BoxFit.contain,
                                child: cardFront,
                              ),
                            ),
                            IdCardWatermark(canvas: canvas, canvasRadius: radius),
                          ],
                        ),
                      ),
                    ],
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
                // 🔴 Story 18.2 · AC1：分享按钮与「解锁高清」**并列**。
                //    绝不能只放进 HD 导出后的底部选单 —— 那等于仍被付费墙挡住：
                //    用户得先付费才能看到"免费分享"的入口，整条激励失效。
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton.icon(
                        key: const ValueKey('idCardFreeShare'),
                        onPressed: _shareBusy ? null : () => _onFreeShare(card),
                        icon: _shareBusy
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(strokeWidth: 2))
                            : const Icon(Icons.ios_share_rounded),
                        // 🛡 AC6：文案固定「分享」，**不含奖励信息**。
                        //    这样总开关关闭时只需停掉成功后的提示，
                        //    不会出现"按钮承诺了奖励却不发"。
                        label: Text(l10n.idCardShare),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: FilledButton.icon(
                        style: FilledButton.styleFrom(backgroundColor: AppColors.mint),
                        onPressed: _hdBusy ? null : () => _onDownloadHd(card),
                        icon: _hdBusy
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                    strokeWidth: 2, color: Colors.white))
                            : const Icon(Icons.download),
                        label: Text(card.hdUnlocked ? l10n.idCardDownloadHd : l10n.idCardUnlockHd),
                      ),
                    ),
                  ],
                ),
                _shareRewardHint(l10n),
              ],
            ),
          ),
          const SizedBox(height: 12),
        ],
      ),
    );
  }

  /// 分享奖励提示（产品 2026-08-27）。**配好了才出现**。
  ///
  /// 🔴 奖励三个数（总开关 / 每次发放 / 日上限）**默认全是 0** —— 功能随版本上线，
  /// 但默认一分不发，等运营在后台配上才开始发。所以这句话不能常驻：
  /// 没配就显示「分享可得 PawCoin」，正是 Story 18.2 AC6 反复要避免的
  /// **「承诺了奖励却不发」**（AC6 当初的做法是干脆一个字都不提）。
  ///
  /// ⚠️ 判据**由服务端给**（`/me/id-cards/share-reward` 返回可承诺的枚数，0 = 不提）。
  /// 客户端不复刻那套判定：它涉及总开关、每次发放数、日上限、以及「这只宠物领过没」，
  /// 复刻一份必然漂移，而漂移的后果就是空承诺。
  ///
  /// ⚠️ 文案写「**首次**分享」不是「每次」：这个奖励是**一只宠物档案只发一次**。
  /// 写成「每次」在第二次分享时就是假话，而用户不会去读规则，只会觉得被骗了一次。
  ///
  /// 🛡 按钮本身仍**不含奖励信息**（AC6 原样保留）—— 这句提示是独立的一行，
  /// 服务端说 0 就整行不渲染，按钮文案一个字都不用改。
  Widget _shareRewardHint(AppLocalizations l10n) {
    final coins = ref.watch(idCardShareRewardProvider).value ?? 0;
    if (coins <= 0) return const SizedBox.shrink();
    return Padding(
      key: const ValueKey('idCardShareRewardHint'),
      padding: const EdgeInsets.only(top: 8),
      child: Text(
        l10n.idCardShareRewardHint(coins),
        style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
      ),
    );
  }

  /// 🔴 Story 18.2：免费分享带水印版，成功后上报并按结果决定是否提示。
  ///
  /// ✅ AC5：只在系统面板回调 `ShareResultStatus.success` 时上报 —— 用户取消不上报、不发币。
  ///    这条不是新写的判断：`CardExport.shareImage` 已经做了（非 success 返回 null），
  ///    这里**照它的写法用**，不另起一套。
  ///
  /// 🛡 AC7：上报失败不影响分享本身 —— 分享已经发生了，奖励只是锦上添花。
  ///    不重试、不建补偿队列。
  Future<void> _onFreeShare(IdCard card) async {
    final l10n = AppLocalizations.of(context);
    setState(() => _shareBusy = true);
    try {
      final origin = _shareOrigin();
      // 🛡 AC2：截**含水印**的那个 boundary。无水印仍需付费。
      final bytes = await _capture(idCardWatermarkedBoundaryKey);
      if (bytes == null) return;
      Analytics.capture('id_card_share_tapped', {'card_style': _styleIndex});
      final channel = await CardExport.shareImage(
        bytes,
        name: 'tailtopia_id_card',
        origin: origin,
      );
      if (channel == null) {
        // 取消 / 面板不可用 ⇒ 没分享出去 ⇒ 不上报、不发币、不提示。
        return;
      }
      Analytics.capture('id_card_share_sent', {'channel': channel});
      int coins = 0;
      try {
        coins = await ref.read(idCardRepositoryProvider).reportShareForReward(card.id);
      } catch (_) {
        // 🛡 发放上报失败 = 当作没发。分享本身已经成功，绝不因此报错给用户。
        coins = 0;
      }
      Analytics.capture('id_card_share_rewarded', {'rewarded': coins > 0});
      if (!mounted) return;
      // 🛡 AC6：奖励只出现在**成功后的轻提示**里，且只在真的发了币时出现。
      //    没发就静默 —— 不告知原因（AC3：告知会诱导"攒着别分享"或"月初集中刷满"）。
      if (coins > 0) {
        _toast(l10n.idCardShareRewardToast(coins));
      }
    } catch (_) {
      if (mounted) _toast(l10n.idCardShareError);
    } finally {
      if (mounted) setState(() => _shareBusy = false);
    }
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
        cardNo: card.cardNo,
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
        // bug 20260806 同类收口：PawCoin 即时扣款成功后失效余额缓存，防他处读到旧余额。
        ref.invalidate(pawCoinProvider);
        _toast(l10n.idCardHdUnlockedToast);
        await _exportHd();
      } else if ((res.payload?.isNotEmpty ?? false) && mounted) {
        final bool paid = await showQrPaymentSheet(
          context,
          payload: res.payload!,
          orderRef: res.paymentRef,
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
  /// 把某个 boundary 截成导出规格的 PNG 字节。
  ///
  /// ⚠️ 传 boundary 而不是写死：付费 HD 导出用**不含水印**的内层 boundary，
  /// 免费分享用**含水印**的外层 boundary（Story 18.2 · AC2）。两条路径共用这一份
  /// 画布选择与白底合成逻辑 —— 抄两遍就会出现「改了一处忘了另一处」。
  Future<Uint8List?> _capture(GlobalKey boundaryKey) async {
    final boundary =
        boundaryKey.currentContext?.findRenderObject() as RenderRepaintBoundary?;
    if (boundary == null) return null;
    // 按当前卡面选画布（护照 1990×1548 与 KTP/学生卡 1988×1200 不同，
    // 恒用 KTP 宽会把护照导出尺寸错算）。
    final exportCanvas = switch (_styleIndex) {
      1 => kPassportCardCanvas,
      2 => kStudentCardCanvas,
      _ => kIdCardCanvas,
    };
    final pixelRatio = exportCanvas.width / boundary.size.width;
    final ui.Image shot = await boundary.toImage(pixelRatio: pixelRatio);
    // bug 20260731-441：卡面 ClipRRect 圆角外是 alpha=0 透明像素，PNG 存透明本身没问题，
    // 但相册查看器深色主题/IM 转发压缩会把透明平铺成黑角——导出前合成到白底再编码。
    final recorder = ui.PictureRecorder();
    final composeCanvas = Canvas(recorder);
    composeCanvas.drawRect(
      Rect.fromLTWH(0, 0, shot.width.toDouble(), shot.height.toDouble()),
      Paint()..color = Colors.white,
    );
    composeCanvas.drawImage(shot, Offset.zero, Paint());
    final ui.Image image = await recorder.endRecording().toImage(shot.width, shot.height);
    final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
    return byteData?.buffer.asUint8List();
  }

  Rect? _shareOrigin() {
    final box = context.findRenderObject() as RenderBox?;
    return box != null ? box.localToGlobal(Offset.zero) & box.size : null;
  }

  Future<void> _exportHd() async {
    final l10n = AppLocalizations.of(context);
    try {
      final origin = _shareOrigin();
      final bytes = await _capture(idCardBoundaryKey);
      if (bytes == null || !mounted) return;
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
