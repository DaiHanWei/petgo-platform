import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/media_permission.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../data/id_card_repository.dart';
import '../data/profile_repository.dart';
import '../domain/card_link.dart';
import '../domain/id_card.dart';
import '../domain/share_service.dart';
import 'id_card/id_card_placeholder.dart';
import 'id_card/ktp_card.dart';
import 'id_card/ktp_card_back.dart';
import 'id_card/ktp_fields.dart';

/// 宠物身份证详情页（Story 6.2 · FR-49B/UX-DR5）。三风格切换（KTP 完整 / Paspor·Pelajar 占位）、
/// KTP 正反翻面、会话级编辑（不写档案 AC3）、老用户「尚未生成」引导态 + 生成动作（6-1 POST，幂等）。
/// HD 付费下载留 6-3、分享落地留 6-4。
class IdCardPage extends ConsumerStatefulWidget {
  const IdCardPage({super.key});

  @override
  ConsumerState<IdCardPage> createState() => _IdCardPageState();
}

class _IdCardPageState extends ConsumerState<IdCardPage> {
  int _styleIndex = 0; // 0=KTP, 1=Paspor, 2=Pelajar
  bool _showBack = false;
  KtpEdits _edits = KtpEdits.empty;
  bool _generating = false;
  bool _hdBusy = false;
  bool _uploadingPhoto = false;

  /// 1600×900 导出边界（HD 下载/分享在 6-3/6-4 用；本 Story 保证画布尺寸 + 截图能力）。
  final GlobalKey idCardBoundaryKey = GlobalKey();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(idCardProvider);
    // 分享入口（Story 6.4）：有档案 + cardToken 才渲染（复用名片分享链接拉新）。
    final cardToken = ref.watch(petProfileProvider).asData?.value?.cardToken;
    final canShare = cardToken != null && cardToken.isNotEmpty;
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(
        title: Text(l10n.idCardTitle),
        actions: [
          if (canShare)
            IconButton(
              key: const ValueKey('idCardShareButton'),
              icon: const Icon(Icons.share),
              onPressed: () => _shareIdCard(cardToken),
            ),
        ],
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => _centered(l10n.idCardLoadError),
        data: (card) {
          if (card == null) return _noProfile(l10n);
          if (!card.generated) return _notGenerated(l10n);
          return _cardView(l10n, card);
        },
      ),
    );
  }

  Widget _cardView(AppLocalizations l10n, IdCardData card) {
    return SafeArea(
      child: Column(
        children: [
          _styleSwitcher(l10n),
          Expanded(
            child: Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: AspectRatio(
                  aspectRatio: kIdCardCanvas.width / kIdCardCanvas.height,
                  child: RepaintBoundary(
                    key: idCardBoundaryKey,
                    child: FittedBox(
                      fit: BoxFit.contain,
                      child: _cardForStyle(l10n, card),
                    ),
                  ),
                ),
              ),
            ),
          ),
          if (_styleIndex == 0) _ktpActions(l10n, card),
          const SizedBox(height: 12),
        ],
      ),
    );
  }

  Widget _cardForStyle(AppLocalizations l10n, IdCardData card) {
    switch (_styleIndex) {
      case 0:
        if (_showBack) {
          return KtpCardBack(
            serialLine: '${l10n.idCardSerialLabel}: ${card.serialId ?? '-'}',
            disclaimerTitle: l10n.idCardDisclaimerTitle,
            disclaimerBody: l10n.idCardDisclaimerBody,
            downloadUrl: petDownloadUrl,
            scanCaption: l10n.idCardScanToDownload,
          );
        }
        return KtpCardFront(fields: buildKtpFields(card, _edits));
      case 1:
        return IdCardComingSoon(
            styleLabel: l10n.idCardStylePaspor, comingSoonText: l10n.idCardComingSoon);
      default:
        return IdCardComingSoon(
            styleLabel: l10n.idCardStylePelajar, comingSoonText: l10n.idCardComingSoon);
    }
  }

  Widget _styleSwitcher(AppLocalizations l10n) {
    final labels = [l10n.idCardStyleKtp, l10n.idCardStylePaspor, l10n.idCardStylePelajar];
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: Row(
        children: [
          for (var i = 0; i < labels.length; i++)
            Expanded(
              child: Padding(
                padding: EdgeInsets.only(right: i < labels.length - 1 ? 8 : 0),
                child: _styleTab(labels[i], i),
              ),
            ),
        ],
      ),
    );
  }

  Widget _styleTab(String label, int index) {
    final selected = _styleIndex == index;
    return GestureDetector(
      onTap: () => setState(() {
        _styleIndex = index;
        _showBack = false;
      }),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: selected ? AppColors.mint : AppColors.card,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: 1.5),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? Colors.white : AppColors.ink2,
            fontWeight: FontWeight.w600,
            fontSize: 14,
          ),
        ),
      ),
    );
  }

  Widget _ktpActions(AppLocalizations l10n, IdCardData card) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        children: [
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () => setState(() => _showBack = !_showBack),
                  icon: const Icon(Icons.flip_to_back),
                  label: Text(_showBack ? l10n.idCardShowFront : l10n.idCardFlip),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _showBack ? null : _openEditSheet,
                  icon: const Icon(Icons.edit),
                  label: Text(l10n.idCardEdit),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          // 换正面照片（前面时可用；持久落档案头像）。
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: (_showBack || _uploadingPhoto) ? null : _changePhoto,
              icon: _uploadingPhoto
                  ? const SizedBox(
                      width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.add_a_photo_outlined),
              label: Text(l10n.idCardChangePhoto),
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _hdBusy ? null : () => _onDownloadHd(card),
              icon: _hdBusy
                  ? const SizedBox(
                      width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.download),
              label: Text(card.hdUnlocked ? l10n.idCardDownloadHd : l10n.idCardUnlockHd),
            ),
          ),
        ],
      ),
    );
  }

  // —— HD 付费下载（Story 6.3）——
  Future<void> _onDownloadHd(IdCardData card) async {
    if (card.hdUnlocked) {
      await _exportHd();
    } else {
      await _openHdPaywall();
    }
  }

  Future<void> _openHdPaywall() async {
    final channel = await showModalBottomSheet<HdPayChannel>(
      context: context,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => const _HdPaywallSheet(),
    );
    if (channel == null || !mounted) return;
    await _purchaseHd(channel);
  }

  Future<void> _purchaseHd(HdPayChannel channel) async {
    setState(() => _hdBusy = true);
    final l10n = AppLocalizations.of(context);
    try {
      final res = await ref.read(idCardRepositoryProvider).purchaseHd(channel);
      if (res.unlocked) {
        ref.invalidate(idCardProvider);
        _toast(l10n.idCardHdUnlockedToast);
        await _exportHd();
      } else {
        // QRIS 待支付：真 QR/Midtrans 属 L2。提示去支付 + 支付后回来点下载即导出。
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

  /// 导出当前证件卡为 1600×900 PNG 并经系统分享面板保存/分享（Story 6.3 · F3/F4）。
  Future<void> _exportHd() async {
    final l10n = AppLocalizations.of(context);
    try {
      final boundary =
          idCardBoundaryKey.currentContext?.findRenderObject() as RenderRepaintBoundary?;
      if (boundary == null) return;
      // iOS 分享面板锚点：在任何 await 之前从当前 context 取（避免跨异步用 context）。
      final box = context.findRenderObject() as RenderBox?;
      final origin = box != null ? box.localToGlobal(Offset.zero) & box.size : null;
      final pixelRatio = kIdCardCanvas.width / boundary.size.width;
      final ui.Image image = await boundary.toImage(pixelRatio: pixelRatio);
      final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
      if (byteData == null) return;
      final bytes = byteData.buffer.asUint8List();
      await Share.shareXFiles(
        [XFile.fromData(Uint8List.fromList(bytes), name: 'tailtopia_id_card.png', mimeType: 'image/png')],
        sharePositionOrigin: origin,
      );
    } catch (_) {
      if (mounted) _toast(l10n.idCardHdExportError);
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  /// 分享身份证拉新链接（Story 6.4 · AC1）：分享 /p/{cardToken} 名片落地页 URL + 身份证风味文案。
  /// 复用既有分享链接助手 + shareFn（iOS 传 sharePositionOrigin）。落地页/失效态/下载 CTA 复用 2.6。
  Future<void> _shareIdCard(String cardToken) async {
    final l10n = AppLocalizations.of(context);
    final box = context.findRenderObject() as RenderBox?;
    final origin = box != null ? box.localToGlobal(Offset.zero) & box.size : null;
    final text = '${l10n.idCardShareCaption}\n${petCardShareUrl(cardToken)}';
    await ref.read(shareServiceProvider)(text, sharePositionOrigin: origin);
  }

  /// 换正面照片（D1，2026-07-17）：**持久落宠物档案头像**——区别于会话级文字编辑（_edits）。
  /// 上传公开桶 → PATCH /me avatarUrl → invalidate 让 KTP 正面 + 宠物档案头像同步刷新。
  Future<void> _changePhoto() async {
    setState(() => _uploadingPhoto = true);
    final l10n = AppLocalizations.of(context);
    try {
      final result = await ref.read(mediaUploadUseCaseProvider).pickAndUploadOne(
            scope: MediaScope.public,
            source: MediaSource.gallery,
            context: context,
          );
      if (result?.publicUrl == null) return; // 取消/无结果
      await ref.read(profileRepositoryProvider).update(avatarUrl: result!.publicUrl);
      ref.invalidate(idCardProvider); // KTP 正面 avatarUrl 刷新
      ref.invalidate(petProfileProvider); // 「我的宠物」头像同步
    } catch (_) {
      if (mounted) _toast(l10n.mediaUploadFailed);
    } finally {
      if (mounted) setState(() => _uploadingPhoto = false);
    }
  }

  // —— 会话级编辑（AC3）：仅改本地 _edits，绝不调 ProfileRepository / 任何档案写端点 ——
  Future<void> _openEditSheet() async {
    final result = await showModalBottomSheet<KtpEdits>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => _KtpEditSheet(initial: _edits),
    );
    if (result != null) setState(() => _edits = result);
  }

  Widget _notGenerated(AppLocalizations l10n) {
    return _CenteredGuide(
      icon: Icons.badge_outlined,
      title: l10n.idCardNotGeneratedTitle,
      body: l10n.idCardNotGeneratedBody,
      ctaLabel: l10n.idCardGenerateCta,
      loading: _generating,
      onCta: _generate,
    );
  }

  Widget _noProfile(AppLocalizations l10n) {
    return _CenteredGuide(
      icon: Icons.pets_outlined,
      title: l10n.idCardNoProfileTitle,
      body: l10n.idCardNoProfileBody,
    );
  }

  Future<void> _generate() async {
    setState(() => _generating = true);
    try {
      await ref.read(idCardRepositoryProvider).generate();
      ref.invalidate(idCardProvider);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(AppLocalizations.of(context).idCardGenerateError)),
        );
      }
    } finally {
      if (mounted) setState(() => _generating = false);
    }
  }

  Widget _centered(String text) => Center(
        child: Padding(padding: const EdgeInsets.all(32), child: Text(text, textAlign: TextAlign.center)),
      );
}

class _CenteredGuide extends StatelessWidget {
  const _CenteredGuide({
    required this.icon,
    required this.title,
    required this.body,
    this.ctaLabel,
    this.onCta,
    this.loading = false,
  });

  final IconData icon;
  final String title;
  final String body;
  final String? ctaLabel;
  final VoidCallback? onCta;
  final bool loading;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 72, color: AppColors.mint500),
            const SizedBox(height: 20),
            Text(title,
                textAlign: TextAlign.center,
                style: const TextStyle(
                    color: AppColors.ink, fontSize: 20, fontWeight: FontWeight.w700)),
            const SizedBox(height: 10),
            Text(body,
                textAlign: TextAlign.center,
                style: const TextStyle(color: AppColors.ink2, fontSize: 14, height: 1.4)),
            if (ctaLabel != null) ...[
              const SizedBox(height: 24),
              FilledButton(
                onPressed: loading ? null : onCta,
                child: loading
                    ? const SizedBox(
                        width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(ctaLabel!),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// HD 付费下载 paywall（Story 6.3 · AC3）。选渠道（PawCoin 站内余额 / QRIS 现金）返回，页面据此发起购买。
class _HdPaywallSheet extends StatelessWidget {
  const _HdPaywallSheet();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 36,
              height: 4,
              decoration:
                  BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
            ),
          ),
          const SizedBox(height: 16),
          Text(l10n.idCardHdPaywallTitle,
              style: const TextStyle(color: AppColors.ink, fontSize: 17, fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Text(l10n.idCardHdPaywallBody,
              style: const TextStyle(color: AppColors.ink2, fontSize: 13, height: 1.4)),
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: () => Navigator.of(context).pop(HdPayChannel.pawcoin),
            icon: const Icon(Icons.account_balance_wallet_outlined),
            label: Text(l10n.idCardHdPayPawcoin),
          ),
          const SizedBox(height: 10),
          OutlinedButton.icon(
            onPressed: () => Navigator.of(context).pop(HdPayChannel.qris),
            icon: const Icon(Icons.qr_code),
            label: Text(l10n.idCardHdPayQris),
          ),
        ],
      ),
    );
  }
}

/// KTP 预览编辑面板（会话级，AC3）。仅收集覆盖值返回，页面据此改本地 _edits；**不触后端/档案**。
class _KtpEditSheet extends StatefulWidget {
  const _KtpEditSheet({required this.initial});

  final KtpEdits initial;

  @override
  State<_KtpEditSheet> createState() => _KtpEditSheetState();
}

class _KtpEditSheetState extends State<_KtpEditSheet> {
  late final Map<String, TextEditingController> _c;

  @override
  void initState() {
    super.initState();
    final e = widget.initial;
    _c = {
      'nama': TextEditingController(text: e.nama),
      'ttl': TextEditingController(text: e.tempatTglLahir),
      'jk': TextEditingController(text: e.jenisKelamin),
      'alamat': TextEditingController(text: e.alamat),
      'status': TextEditingController(text: e.statusPerkawinan),
      'pekerjaan': TextEditingController(text: e.pekerjaan),
    };
  }

  @override
  void dispose() {
    for (final c in _c.values) {
      c.dispose();
    }
    super.dispose();
  }

  String? _v(String k) {
    final t = _c[k]!.text.trim();
    return t.isEmpty ? null : t;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 18,
        bottom: MediaQuery.of(context).viewInsets.bottom + 24,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                    color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
              ),
            ),
            const SizedBox(height: 16),
            Text(l10n.idCardEditTitle,
                style: const TextStyle(
                    color: AppColors.ink, fontSize: 17, fontWeight: FontWeight.w700)),
            const SizedBox(height: 16),
            _field('Nama', 'nama'),
            _field('Tempat/Tgl Lahir', 'ttl'),
            _field('Jenis Kelamin', 'jk'),
            _field('Alamat', 'alamat'),
            _field('Status Perkawinan', 'status'),
            _field('Pekerjaan', 'pekerjaan'),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(
                widget.initial.copyWith(
                  nama: _v('nama'),
                  tempatTglLahir: _v('ttl'),
                  jenisKelamin: _v('jk'),
                  alamat: _v('alamat'),
                  statusPerkawinan: _v('status'),
                  pekerjaan: _v('pekerjaan'),
                ),
              ),
              child: Text(l10n.idCardEditDone),
            ),
          ],
        ),
      ),
    );
  }

  Widget _field(String label, String key) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextField(
        controller: _c[key],
        decoration: InputDecoration(
          labelText: label,
          border: const OutlineInputBorder(),
          isDense: true,
        ),
      ),
    );
  }
}
