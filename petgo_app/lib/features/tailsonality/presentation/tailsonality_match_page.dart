import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/card_render/card_render_pipeline.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../content/presentation/brag_post_entry.dart';
import '../../profile/data/profile_repository.dart';
import '../data/tailsonality_owner_type_repository.dart';
import '../data/tailsonality_providers.dart';
import '../data/ts_remote_art.dart';
import '../domain/content/ts_match_copy.dart';
import '../domain/content/ts_text.dart';
import '../domain/ts_match.dart';
import 'share/match_share_card.dart';
import 'widgets/ts_letter_compare.dart';
import 'widgets/ts_match_card.dart';
import 'widgets/ts_rich_text.dart';
import 'widgets/ts_type_selector.dart';

/// 主人配型页（V1.3.2 Story 2.5 · UX-DR9）。挂在某次结果 token 下：宠物侧用**这次结果**的四字母。
///
/// 配型**全免费**：无锁态、无水印、无任何付费要素；换类型不收费。页首 1:1 配型卡可点 → 配型卡分享预览（Story 4.2）；
/// 结果视图底部主按钮「Pamer di postingan」（Story 4.4）。
class TailsonalityMatchPage extends ConsumerStatefulWidget {
  const TailsonalityMatchPage({super.key, required this.token});

  final String token;

  /// 1:1 配型卡出图测试缝（`toImage` 在 widget test 的 fake-async 里不会完成）。
  @visibleForTesting
  static Future<Uint8List?> Function()? captureForTest;

  @override
  ConsumerState<TailsonalityMatchPage> createState() => _TailsonalityMatchPageState();
}

class _TailsonalityMatchPageState extends ConsumerState<TailsonalityMatchPage> {
  bool _editing = false;
  String? _selected;
  bool _saving = false;
  bool _entered = false;

  /// 页首 1:1 配型卡的截图边界（发帖 / 预览主操作都截它；永不带水印）。
  final GlobalKey _cardKey = GlobalKey();
  bool _capturing = false;

  /// 结果视图的滚动控制：底部「Pamer」常驻，卡在列表首项 —— 截图前先滚回顶部（见 [_brag]）。
  final ScrollController _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  /// 当前页首卡的档号（build 时记下，截图前判断配型卡图到没到手）。
  int? _shownTier;

  Future<Uint8List?> _captureCard() async {
    final capture = TailsonalityMatchPage.captureForTest;
    if (capture != null) return capture();
    // 配型卡按需下载（TsRemoteArt）：图还没到手时卡上画的是占位，不截（调用方按「出图失败」提示）。
    final tier = _shownTier;
    if (tier == null || TsRemoteArt.peek(TsRemoteArt.match(tier)) == null) return null;
    return CardRenderPipeline.capture(boundaryKey: _cardKey, canvas: kTsMatchCanvas);
  }

  /// 底部「Pamer di postingan」：截 1:1 配型卡 → 发帖页。
  ///
  /// 🔴 按钮吸底常驻，卡在列表首项：滚到详解处时卡已被懒列表回收（截到空）或不在屏上（不绘制）。
  /// 先滚回顶部、等一帧再截；仍截不到给轻提示，不静默失败。
  Future<void> _brag() async {
    if (_capturing) return;
    BragPostEntry.reportTap(BragPostSource.tailsonalityMatch);
    setState(() => _capturing = true);
    try {
      if (_scroll.hasClients && _scroll.offset > 0) {
        _scroll.jumpTo(0);
        await WidgetsBinding.instance.endOfFrame;
      }
      if (!mounted) return;
      final bytes = await _captureCard();
      if (!mounted) return;
      if (bytes == null) {
        showAppToast(context, AppLocalizations.of(context).shareCardExportError);
        return;
      }
      await BragPostEntry.open(context, cardPng: bytes, text: AppLocalizations.of(context).tailsonalityBragMatchText);
    } finally {
      if (mounted) setState(() => _capturing = false);
    }
  }

  /// 点 1:1 卡 → 配型卡预览。🔴 预览页上画的是带信息栏的分享卡，截不到纯 1:1 卡 —— 所以**先在本页截好**再传给预览页的主操作。
  Future<void> _openPreview(String petName, String petCode, String owner4) async {
    if (_capturing) return;
    setState(() => _capturing = true);
    Uint8List? bytes;
    try {
      bytes = await _captureCard();
    } finally {
      if (mounted) setState(() => _capturing = false);
    }
    if (!mounted) return;
    await openMatchSharePreview(context, ref, petName: petName, petCode: petCode, ownerType: owner4, cardPng: bytes);
  }

  void _reportEntered(String pet4) {
    if (_entered) return;
    _entered = true;
    WidgetsBinding.instance.addPostFrameCallback(
        (_) => Analytics.capture('tailsonality_match_entered', {'pet_type': pet4}));
  }

  Future<void> _confirm(String pet4) async {
    final sel = _selected;
    if (sel == null || _saving) return;
    setState(() => _saving = true);
    try {
      await ref.read(tailsonalityOwnerTypeProvider.notifier).set(sel);
      Analytics.capture('tailsonality_owner_type_set', {
        'owner_type': sel,
        'pet_type': pet4,
        'match_level': computeTsMatch(sel, pet4).sameCount,
      });
      if (!mounted) return;
      setState(() {
        _saving = false;
        _editing = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _saving = false);
      showAppToast(context, AppLocalizations.of(context).tailsonalityOwnerTypeSaveFailed);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final resultAsync = ref.watch(tailsonalityResultProvider(widget.token));
    final ownerAsync = ref.watch(tailsonalityOwnerTypeProvider);
    final petName = ref.watch(petProfileProvider).asData?.value?.name ?? '';
    final pet4 = resultAsync.asData?.value.letters;
    final petCode = resultAsync.asData?.value.typeCode;
    if (pet4 != null) _reportEntered(pet4);

    final Widget body;
    Widget? bottom;
    if (pet4 == null || (ownerAsync.isLoading && !ownerAsync.hasValue)) {
      body = resultAsync.hasError
          ? Center(
              child: TextButton(
                key: const ValueKey('tsMatchRetry'),
                onPressed: () => ref.invalidate(tailsonalityResultProvider(widget.token)),
                child: Text(l10n.commonRetry),
              ),
            )
          : const Center(child: CircularProgressIndicator());
    } else {
      // 读主人类型失败按「未设置」处理：直接让用户选，选了就覆盖。
      final owner = ownerAsync.asData?.value;
      if (owner == null || _editing) {
        body = _selector(l10n);
        bottom = _confirmBar(l10n, pet4);
      } else {
        body = _result(context, l10n, owner, pet4, petCode!, petName);
        bottom = _bragBar(l10n);
      }
    }
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(backgroundColor: AppColors.cream, title: Text(l10n.tailsonalityMatchTitle)),
      body: body,
      bottomNavigationBar: bottom,
    );
  }

  Widget _selector(AppLocalizations l10n) => ListView(
        key: const ValueKey('tsMatchSelectorView'),
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
        children: [
          Text(l10n.tailsonalityOwnerTypeQuestion,
              style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: AppColors.ink)),
          const SizedBox(height: 6),
          Text(l10n.tailsonalityOwnerTypeHint, style: const TextStyle(fontSize: 13.5, color: AppColors.textSecondary)),
          const SizedBox(height: 18),
          TsTypeSelector(selected: _selected, onSelect: (c) => setState(() => _selected = c)),
        ],
      );

  Widget _confirmBar(AppLocalizations l10n, String pet4) => SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: FilledButton(
            key: const ValueKey('tsOwnerTypeConfirm'),
            // 与选中态同一帧切换（UX-DR8）：都由 _selected 驱动、同一次 setState 生效。
            onPressed: _selected == null || _saving ? null : () => _confirm(pet4),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent,
              minimumSize: const Size.fromHeight(48),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
            ),
            child: Text(l10n.tailsonalityOwnerTypeConfirm, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          ),
        ),
      );

  Widget _bragBar(AppLocalizations l10n) => SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: FilledButton(
            key: const ValueKey('tsMatchBragCta'),
            onPressed: _capturing ? null : _brag,
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent,
              minimumSize: const Size.fromHeight(48),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
            ),
            child: Text(l10n.bragPostButton, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          ),
        ),
      );

  Widget _result(
      BuildContext context, AppLocalizations l10n, String owner4, String pet4, String petCode, String petName) {
    final locale = Localizations.localeOf(context);
    final m = computeTsMatch(owner4, pet4);
    _shownTier = m.tier;
    final tier = kTsMatchTiers[m.sameCount]!;
    final axisTitles = [l10n.tailsonalityAxisEI, l10n.tailsonalityAxisNS, l10n.tailsonalityAxisTF, l10n.tailsonalityAxisJP];
    String fill(TsText t) => tsFillPet(t.of(locale), petName);
    return ListView(
      key: const ValueKey('tsMatchResultView'),
      controller: _scroll,
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: GestureDetector(
              key: const ValueKey('tsMatchCardTap'),
              onTap: () => _openPreview(petName, petCode, owner4),
              child: TsMatchCard(tier: m.tier, boundaryKey: _cardKey),
            ),
          ),
        ),
        const SizedBox(height: 16),
        TsLetterCompare(owner4: owner4, pet4: pet4, petName: petName),
        const SizedBox(height: 16),
        Text(tier.name.of(locale),
            key: const ValueKey('tsMatchTierName'),
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900, color: AppColors.ink)),
        const SizedBox(height: 4),
        Text(fill(tier.summary),
            key: const ValueKey('tsMatchSummary'),
            style: const TextStyle(fontSize: 14.5, color: AppColors.ink2)),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton(
            key: const ValueKey('tsMatchChangeType'),
            onPressed: () => setState(() {
              _editing = true;
              _selected = owner4;
            }),
            child: Text(l10n.tailsonalityMatchChangeType),
          ),
        ),
        const SizedBox(height: 8),
        Text(l10n.tailsonalityMatchDetailTitle,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: AppColors.ink)),
        const SizedBox(height: 8),
        TsRichText(fill(tier.review),
            key: const ValueKey('tsMatchReview'), style: const TextStyle(fontSize: 14, height: 1.55, color: AppColors.ink)),
        for (var i = 0; i < 4; i++) ...[
          const SizedBox(height: 14),
          Text(axisTitles[i],
              key: ValueKey('tsMatchAxisTitle_$i'),
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint)),
          const SizedBox(height: 4),
          TsRichText(fill(kTsAxisDetails[m.axisDetailKeys[i]]!),
              key: ValueKey('tsMatchAxisDetail_${m.axisDetailKeys[i]}'),
              style: const TextStyle(fontSize: 14, height: 1.55, color: AppColors.ink)),
        ],
      ],
    );
  }
}
