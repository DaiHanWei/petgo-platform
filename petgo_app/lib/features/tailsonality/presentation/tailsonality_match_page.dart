import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/card_render/card_render_pipeline.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/pay_channel_picker.dart';
import '../../../shared/widgets/price_load_retry.dart';
import '../../content/presentation/brag_post_entry.dart';
import '../../keepsake/data/keepsake_repository.dart';
import '../../keepsake/domain/keepsake_pricing.dart';
import '../../keepsake/presentation/keepsake_pay_flow.dart';
import '../../profile/data/profile_repository.dart';
import '../data/tailsonality_owner_type_repository.dart';
import '../data/tailsonality_providers.dart';
import '../data/tailsonality_repository.dart';
import '../data/ts_remote_art.dart';
import '../domain/content/ts_match_copy.dart';
import '../domain/content/ts_text.dart';
import '../domain/tailsonality_result.dart';
import '../domain/ts_match.dart';
import 'share/match_share_card.dart';
import 'tailsonality_unlock_analytics.dart';
import 'widgets/ts_letter_compare.dart';
import 'widgets/ts_match_card.dart';
import 'widgets/ts_rich_text.dart';
import 'widgets/ts_type_selector.dart';

/// 主人配型页（V1.3.2 Story 2.5 · UX-DR9）。挂在某次结果 token 下：宠物侧用**这次结果**的四字母。
///
/// 🔴 2026-10-09 配型改回付费（推翻 2026-09-21「全免费」）：选主人类型仍免费；选完后**整页上锁**，
/// 按结果单独解锁（默认 Rp3,000），或买完整解读（含配型）。锁态下不出配型卡、档位、字母对照（都会泄露结果）。
/// 解锁后照旧：配型卡无水印、换类型不收费；页首 1:1 配型卡可点 → 分享预览（Story 4.2）；底部「Pamer di postingan」（Story 4.4）。
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
  bool _buying = false;
  bool _lockViewedReported = false;

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
      final result = resultAsync.asData!.value;
      if (owner == null || _editing) {
        body = _selector(l10n);
        bottom = _confirmBar(l10n, pet4);
      } else if (!result.matchUnlocked) {
        _reportLockViewed(result);
        body = _locked(l10n, owner, pet4, petName);
        bottom = _unlockBar(l10n, result, petName);
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

  // ---------- 2026-10-09：配型锁态 ----------

  void _reportLockViewed(TailsonalityResult r) {
    if (_lockViewedReported) return;
    _lockViewedReported = true;
    WidgetsBinding.instance.addPostFrameCallback((_) => TailsonalityUnlockAnalytics.matchViewed(
        roleCode: r.typeCode, resultIndex: r.resultIndex, price: ref.read(keepsakePricingProvider).value?.tailsonalityMatch));
  }

  /// [full] = 买完整解读（含配型；已买过配型时按补差价——但能看到锁态说明还没买过配型，故恒为原价）。
  Future<void> _startUnlock(TailsonalityResult r, String petName, {required bool full}) async {
    if (_buying) return;
    setState(() => _buying = true);
    // 抽屉期间保活定价 provider（autoDispose）：确认埋点要读价格。
    final priceSub = ref.listenManual(keepsakePricingProvider, (_, _) {});
    final l10n = AppLocalizations.of(context);
    final repo = ref.read(tailsonalityRepositoryProvider);
    try {
      final outcome = await runKeepsakePurchase(
        context: context,
        ref: ref,
        sheet: (balance) => _TsMatchPaywallSheet(full: full, petName: petName, balance: balance, result: r),
        start: (channel) => full ? repo.unlock(r.token, channel) : repo.unlockMatch(r.token, channel),
        pollPaid: () async => (await ref.refresh(tailsonalityResultProvider(r.token).future)).matchUnlocked,
        // 完整解读（含配型）与单买配型是两种支付用途；完整解读价已扣掉先前单买配型付过的钱（补差价）。
        purchasePurpose: full ? 'TAILSONALITY' : 'TS_MATCH',
        cashPriceIdr: () =>
            full ? r.fullUnlockPrice(priceSub.read().value) : priceSub.read().value?.tailsonalityMatch,
        onChannelConfirmed: (channel) => TailsonalityUnlockAnalytics.matchInitiated(
            roleCode: r.typeCode,
            resultIndex: r.resultIndex,
            method: channel,
            product: full ? 'full' : 'match',
            price: full ? r.fullUnlockPrice(priceSub.read().value) : priceSub.read().value?.tailsonalityMatch),
      );
      if (!mounted || outcome == KeepsakeFlowOutcome.notCompleted) return;
      // 已解锁判定只看服务端：刷新结果（与列表），由服务端的 `matchUnlocked` 切换页面。
      ref.invalidate(tailsonalityResultProvider(r.token));
      ref.invalidate(tailsonalityResultsProvider);
      // 买完整解读时服务端可能自动佩戴徽章（同结果页 _startUnlock）。
      if (full) ref.invalidate(petProfileProvider);
      if (outcome == KeepsakeFlowOutcome.unlocked) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(full ? l10n.tailsonalityUnlockedToast : l10n.tailsonalityMatchUnlockedToast)));
      }
    } finally {
      priceSub.close();
      if (mounted) setState(() => _buying = false);
    }
  }

  /// 锁态：只亮出双方四字母（用户自己选的 + 测出来的，都不是付费内容）与要解锁的内容清单；可改类型（免费）。
  Widget _locked(AppLocalizations l10n, String owner4, String pet4, String petName) {
    Widget letters(String label, String code) => Column(
          children: [
            Text(label, style: const TextStyle(fontSize: 12.5, color: AppColors.textSecondary)),
            const SizedBox(height: 4),
            Text(code,
                style: const TextStyle(
                    fontSize: 24, fontWeight: FontWeight.w900, color: AppColors.ink, letterSpacing: 2)),
          ],
        );
    return ListView(
      key: const ValueKey('tsMatchLockedView'),
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      children: [
        Container(
          padding: const EdgeInsets.fromLTRB(20, 24, 20, 22),
          decoration: BoxDecoration(
            color: AppColors.card,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: AppColors.lineViolet),
          ),
          child: Column(
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  letters(l10n.tailsonalityMatchYou, owner4),
                  const Icon(Icons.lock_outline, size: 28, color: AppColors.mint),
                  letters(petName, pet4),
                ],
              ),
              const SizedBox(height: 20),
              Text(l10n.tailsonalityMatchLockedTitle(petName),
                  key: const ValueKey('tsMatchLockedTitle'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: AppColors.ink)),
              const SizedBox(height: 8),
              Text(l10n.tailsonalityMatchLockedBody,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 14, height: 1.5, color: AppColors.ink2)),
            ],
          ),
        ),
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
      ],
    );
  }

  /// 锁态底部：主按钮单买配型；下方文字链买完整解读（含配型）。价格只从服务端读（D-2），取价失败显示重试。
  Widget _unlockBar(AppLocalizations l10n, TailsonalityResult r, String petName) {
    final price = ref.watch(keepsakePricingProvider);
    final Widget main;
    final matchPrice = price.value?.tailsonalityMatch;
    if ((price.hasError && !price.isLoading) || (price.hasValue && matchPrice == null)) {
      main = Center(
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: PriceLoadRetry(
              key: const ValueKey('tsMatchUnlockPriceRetry'),
              onRetry: () => ref.invalidate(keepsakePricingProvider)),
        ),
      );
    } else {
      main = FilledButton(
        key: const ValueKey('tsMatchUnlockCta'),
        onPressed: matchPrice == null || _buying ? null : () => _startUnlock(r, petName, full: false),
        style: FilledButton.styleFrom(
          backgroundColor: AppColors.mint,
          foregroundColor: AppColors.onAccent,
          minimumSize: const Size.fromHeight(48),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ),
        child: Text(matchPrice == null ? '…' : l10n.tailsonalityMatchUnlockCta(formatIdrAmount(matchPrice)),
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
      );
    }
    final fullPrice = r.fullUnlockPrice(price.value);
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            main,
            if (fullPrice != null)
              TextButton(
                key: const ValueKey('tsMatchFullUnlockLink'),
                onPressed: _buying ? null : () => _startUnlock(r, petName, full: true),
                child: Text(l10n.tailsonalityMatchFullUnlockLink(formatIdrAmount(fullPrice)),
                    textAlign: TextAlign.center, style: const TextStyle(fontSize: 13)),
              ),
          ],
        ),
      ),
    );
  }
}

/// 配型锁态的选渠道抽屉：单买配型 / 买完整解读（含配型）两用，通用 [PayChannelPicker]。
class _TsMatchPaywallSheet extends ConsumerWidget {
  const _TsMatchPaywallSheet({required this.full, required this.petName, required this.balance, required this.result});

  final bool full;
  final String petName;
  final int balance;
  final TailsonalityResult result;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final pricing = ref.watch(keepsakePricingProvider);
    // 旧后端无配型价时 tailsonalityMatch 为 null → 当作取价失败（抽屉显示重试），不猜价。
    final AsyncValue<int> price = pricing.when(
      data: (KeepsakePricing p) {
        final v = full ? result.fullUnlockPrice(p) : p.tailsonalityMatch;
        return v == null ? AsyncValue.error(StateError('no price'), StackTrace.current) : AsyncValue.data(v);
      },
      loading: () => const AsyncValue.loading(),
      error: AsyncValue.error,
    );
    return PayChannelPicker(
      header: Row(
        children: [
          const Icon(Icons.favorite_border, size: 28, color: AppColors.mint),
          const SizedBox(width: 12),
          Text(result.letters,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: AppColors.ink)),
        ],
      ),
      price: price,
      onRetryPrice: () => ref.invalidate(keepsakePricingProvider),
      balance: balance,
      title: full ? l10n.tailsonalityPaywallTitle : l10n.tailsonalityMatchPaywallTitle,
      body: full ? l10n.tailsonalityPaywallBody(petName) : l10n.tailsonalityMatchPaywallBody(petName),
      confirmLabel: (p) => l10n.tailsonalityPayConfirm(p == null ? '…' : formatIdrAmount(p)),
      confirmKey: const ValueKey('tsMatchPayConfirm'),
      retryKey: const ValueKey('tsMatchPayPriceRetry'),
    );
  }
}
