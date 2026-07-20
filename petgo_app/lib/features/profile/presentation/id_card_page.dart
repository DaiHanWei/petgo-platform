import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:share_plus/share_plus.dart';

import '../../../core/media/media_scope.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
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
import 'id_card/ktp_fields.dart';

/// 身份证 HD 下载展示价（IDR，与后端 pricing_config.id_hd_download_price 对齐；实际收费以后端为准）。
const int kIdCardHdPriceIdr = 5000;

/// 宠物身份证详情页（Story 6.2 · FR-49B/UX-DR5）。三风格切换（KTP 完整 / Paspor·Pelajar 占位）、
/// 会话级编辑（不写档案 AC3）、老用户「尚未生成」引导态 + 生成动作（6-1 POST，幂等）。
/// HD 付费下载留 6-3、分享落地留 6-4。
///
/// 2026-07-17（用户决策）：**取消背面**——证件只剩正面，背面的下载二维码随之下线（后端 `/get`
/// 落地页保留，外部渠道仍可用）。背面原承载的娱乐仿制免责声明改在本页 UI 呈现（见 [_disclaimer]），
/// 故不进导出图。
class IdCardPage extends ConsumerStatefulWidget {
  const IdCardPage({super.key});

  @override
  ConsumerState<IdCardPage> createState() => _IdCardPageState();
}

class _IdCardPageState extends ConsumerState<IdCardPage> {
  int _styleIndex = 0; // 0=KTP, 1=Paspor, 2=Pelajar
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
      onTap: () => setState(() => _styleIndex = index),
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
          _disclaimer(l10n),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _openEditSheet,
              icon: const Icon(Icons.edit),
              label: Text(l10n.idCardEdit),
            ),
          ),
          const SizedBox(height: 10),
          // 换正面照片（持久落档案头像）。
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _uploadingPhoto ? null : _changePhoto,
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

  /// 娱乐仿制免责声明（合规必做）。背面取消后移到页面 UI：用户看得到，但不随卡面导出/分享出去。
  Widget _disclaimer(AppLocalizations l10n) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 2),
      child: Text(
        '${l10n.idCardDisclaimerTitle} · ${l10n.idCardDisclaimerBody}',
        textAlign: TextAlign.center,
        style: const TextStyle(color: AppColors.ink2, fontSize: 11, height: 1.35),
      ),
    );
  }

  // —— HD 付费下载（Story 6.3）——
  Future<void> _onDownloadHd(IdCardData card) async {
    if (card.hdUnlocked) {
      await _exportHd();
    } else {
      await _openHdPaywall(card);
    }
  }

  Future<void> _openHdPaywall(IdCardData card) async {
    // 等余额到位（冷启动 provider 同步读会拿到 loading→0，误判 PawCoin 不可用）。
    int balance = 0;
    try {
      balance = (await ref.read(pawCoinProvider.future)).balance;
    } catch (_) {
      balance = 0; // 拉取失败 → PawCoin 按不足降级（仍可 QRIS）
    }
    if (!mounted) return;
    final channel = await showModalBottomSheet<HdPayChannel>(
      context: context,
      backgroundColor: AppColors.card,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => _HdPaywallSheet(
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
/// 身份证 HD 付费下载弹层（Story 6.3 · 0718 ref27 改版）：预览卡（低清+watermark）+ 说明 +
/// PawCoin/QRIS 选中态方式卡 + 底部「Bayar & Unduh HD」确认。返回所选 [HdPayChannel]（取消=null）。
class _HdPaywallSheet extends StatefulWidget {
  const _HdPaywallSheet({this.petName, this.serialId, this.avatarUrl, required this.balance});

  final String? petName;
  final int? serialId;
  final String? avatarUrl;
  final int balance;

  @override
  State<_HdPaywallSheet> createState() => _HdPaywallSheetState();
}

class _HdPaywallSheetState extends State<_HdPaywallSheet> {
  static const int _priceIdr = kIdCardHdPriceIdr;
  late HdPayChannel _selected =
      widget.balance >= _priceIdr ? HdPayChannel.pawcoin : HdPayChannel.qris;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final enoughCoin = widget.balance >= _priceIdr;
    final no = widget.serialId == null ? '----' : widget.serialId!.toString().padLeft(4, '0');
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 6, 20, 20),
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
            // 预览卡（紫渐变 + watermark + 名/编号 + 低清提示）。
            Container(
              padding: const EdgeInsets.symmetric(vertical: 22, horizontal: 16),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [AppColors.mint500, AppColors.mint],
                ),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Column(
                children: [
                  const Text('🐾', style: TextStyle(fontSize: 30)),
                  const SizedBox(height: 8),
                  Text('${widget.petName ?? 'Pet'} · No. $no',
                      style: const TextStyle(
                          color: Colors.white, fontSize: 16, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 4),
                  Text(l10n.idCardHdPreviewSub,
                      style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.8), fontSize: 12)),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Text(l10n.idCardHdPaywallTitle,
                style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            Text(l10n.idCardHdPaywallBody,
                style: const TextStyle(color: AppColors.ink2, fontSize: 13, height: 1.5)),
            const SizedBox(height: 18),
            // PawCoin：余额充足可选（显扣减），不足→Isi saldo dulu 跳充值。
            _HdMethodTile(
              icon: Icons.savings_outlined,
              title: 'PawCoin',
              subtitle:
                  l10n.idCardHdPawcoinSub(_fmt(widget.balance), _fmt(_priceIdr)),
              selected: enoughCoin && _selected == HdPayChannel.pawcoin,
              enabled: enoughCoin,
              trailingAction: enoughCoin ? null : l10n.triageUnlockTopupFirst,
              onTap: enoughCoin
                  ? () => setState(() => _selected = HdPayChannel.pawcoin)
                  : () {
                      Navigator.of(context).pop();
                      context.push('/me/pawcoin/recharge');
                    },
            ),
            const SizedBox(height: 10),
            _HdMethodTile(
              icon: Icons.qr_code_2,
              title: 'QRIS / DANA',
              subtitle: 'Rp${_fmt(_priceIdr)}',
              selected: _selected == HdPayChannel.qris,
              onTap: () => setState(() => _selected = HdPayChannel.qris),
            ),
            const SizedBox(height: 18),
            FilledButton(
              key: const ValueKey('hdPayConfirm'),
              onPressed: () => Navigator.of(context).pop(_selected),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: Text(l10n.idCardHdPayConfirm,
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
            ),
          ],
        ),
      ),
    );
  }

  /// 千分位（5000 → 5.000），避免引 intl。
  static String _fmt(int v) {
    final d = v.toString();
    final b = StringBuffer();
    for (var i = 0; i < d.length; i++) {
      if (i > 0 && (d.length - i) % 3 == 0) b.write('.');
      b.write(d[i]);
    }
    return b.toString();
  }
}

/// HD 付费方式卡（ref27）：色块图标 + 标题 + 副行 + 选中紫框/对勾；不足态右侧充值链接。
class _HdMethodTile extends StatelessWidget {
  const _HdMethodTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.onTap,
    this.enabled = true,
    this.trailingAction,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool selected;
  final bool enabled;
  final String? trailingAction;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint2 : AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
              color: selected ? AppColors.mint : AppColors.line, width: selected ? 1.6 : 1.2),
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: enabled ? AppColors.mintTint : AppColors.cream2,
                borderRadius: BorderRadius.circular(11),
              ),
              child: Icon(icon, size: 20, color: enabled ? AppColors.mint : AppColors.muted),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(title,
                      style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: enabled ? AppColors.ink : AppColors.textTertiary)),
                  const SizedBox(height: 2),
                  Text(subtitle, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                ],
              ),
            ),
            if (trailingAction != null) ...[
              const SizedBox(width: 8),
              Text(trailingAction!,
                  style: const TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.mint)),
            ] else if (selected)
              const Icon(Icons.check_circle, size: 22, color: AppColors.mint)
            else
              Icon(Icons.radio_button_unchecked, size: 22, color: AppColors.line),
          ],
        ),
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
