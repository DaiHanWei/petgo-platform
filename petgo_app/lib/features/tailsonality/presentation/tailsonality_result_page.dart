import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/storage/prefs.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/card_render/card_render_pipeline.dart';
import '../../../shared/media/image_lightbox.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/pay_channel_picker.dart';
import '../../../shared/widgets/price_load_retry.dart';
import '../../content/presentation/brag_post_entry.dart';
import '../../keepsake/data/keepsake_repository.dart';
import '../../keepsake/presentation/keepsake_pay_flow.dart';
import '../../profile/data/profile_repository.dart';
import '../data/tailsonality_owner_type_repository.dart';
import '../data/tailsonality_providers.dart';
import '../data/tailsonality_repository.dart';
import '../domain/content/ts_dialog_copy.dart';
import '../domain/content/ts_roles.dart';
import '../domain/content/ts_text.dart';
import '../domain/tailsonality_result.dart';
import '../domain/ts_match.dart';
import 'share/result_share_card.dart';
import 'tailsonality_retake.dart';
import 'tailsonality_routes.dart';
import 'tailsonality_unlock_analytics.dart';
import 'widgets/tailsonality_badge_chip.dart';
import 'widgets/ts_locked_analysis.dart';
import 'widgets/ts_match_teaser.dart';
import 'widgets/ts_result_card.dart';
import 'widgets/ts_result_menu.dart';
import 'widgets/ts_rich_text.dart';
import 'widgets/ts_unlocked_analysis.dart';

/// Tailsonality 结果页（V1.3.2 Story 2.4 免费态 · Story 3.2 解锁 / 已解锁态 / 挽留 · UX-DR6）。
///
/// 自上而下：结果卡（3:4；未解锁带水印）→ 免费摘要 → 配型引流 → 锁态区（底部「Buka Rp{价}」）/ 已解锁付费区。
/// 已解锁态底部主 CTA「Bagikan」（Story 4.1）→ 结果卡预览，与 ⋯「Bagikan」同一入口；点 3:4 卡图开大图。路由 `extra` 带 [TailsonalityResult] 时先用它首帧渲染；
/// 深链无 `extra` 照常按 token 取数。
///
/// 未解锁态点返回（系统 / AppBar）→ 本机对该结果 token 首次时弹挽留（A10），弹出即记。
class TailsonalityResultPage extends ConsumerStatefulWidget {
  const TailsonalityResultPage({super.key, required this.token, this.initial});

  final String token;
  final TailsonalityResult? initial;

  /// 卡图出图测试缝（Story 4.1 · AC5）：`toImage` 在 widget test 的 fake-async 里不会完成。
  /// 参数是本次要截的 boundary（未解锁 = 含水印的外层；已解锁 = 内层），测试据此断言截的是哪一层。
  @visibleForTesting
  static Future<Uint8List?> Function(GlobalKey boundary)? captureForTest;

  @override
  ConsumerState<TailsonalityResultPage> createState() => _TailsonalityResultPageState();
}

class _TailsonalityResultPageState extends ConsumerState<TailsonalityResultPage> {
  final GlobalKey _lockedKey = GlobalKey();

  /// 结果卡两层截图边界（Story 4.1 · AC5）：内层只含卡面；外层把水印一并框进来（仅未解锁时挂上）。
  final GlobalKey _cardKey = GlobalKey();
  final GlobalKey _cardWatermarkedKey = GlobalKey();
  bool _opening = false;

  /// 结果页列表的滚动控制：⋯「Pamer」截图前先滚回顶部（卡在列表首项，滚远了会被懒列表回收、截不到）。
  final ScrollController _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }
  bool _viewedReported = false;
  bool _buying = false;

  /// 本页实例已处理过挽留（弹过 / 本机早已弹过）→ 再点返回直接走。
  bool _retentionDone = false;
  bool _retentionBusy = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(tailsonalityResultProvider(widget.token));
    final petName = ref.watch(petProfileProvider).asData?.value?.name ?? '';
    // 答题页带过来的结果一直有效（刚由服务端创建）：后续取数失败 / 重试中都继续显示它，不被重试页顶掉。
    // 用 `value`（刷新中保留上一份）而不是 `asData`：解锁后刷新期间不能闪回 `initial` 的锁态。
    final shown = async.value ?? widget.initial;
    final locked = shown != null && !shown.unlocked;
    if (locked) {
      // viewed 带价格：价格到位（或失败）后再判一次可视，避免首帧就报出一条缺价的事件。
      ref.listen(keepsakePricingProvider, (_, next) {
        if (!next.isLoading) _scheduleViewedCheck();
      });
      _scheduleViewedCheck();
    }
    return PopScope(
      // 购买流程进行中（读余额 / 请求在途、尚无弹层）不拦返回：用户此时想走就让走，流程随页面卸载自然作废。
      canPop: !locked || _retentionDone || _buying,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && shown != null) _onBlockedPop(shown, petName);
      },
      child: Scaffold(
        backgroundColor: AppColors.cream,
        appBar: AppBar(
          backgroundColor: AppColors.cream,
          title: Text(l10n.tailsonalityTitle),
          actions: [
            IconButton(
              key: const ValueKey('tsResultMore'),
              tooltip: l10n.tailsonalityMoreActions,
              constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
              icon: const Icon(Icons.more_horiz),
              onPressed: shown == null ? null : () => _openMenu(context),
            ),
          ],
        ),
        body: shown != null
            ? NotificationListener<ScrollNotification>(
                onNotification: (_) {
                  if (locked) _checkViewed(shown);
                  return false;
                },
                child: _Body(
                  result: shown,
                  petName: petName,
                  lockedKey: _lockedKey,
                  scrollController: _scroll,
                  cardKey: _cardKey,
                  cardWatermarkedKey: _cardWatermarkedKey,
                  onCardTap: () => _openCardLightbox(shown),
                  onShare: () => openResultSharePreview(context, ref, shown),
                  footer: locked
                      ? _UnlockCta(
                          upgradePrice: shown.upgradePrice, busy: _buying, onTap: () => _startUnlock(shown, petName))
                      : null,
                ),
              )
            : async.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => _isNotFound(e)
                    ? Center(
                        key: const ValueKey('tsResultNotFound'),
                        child: Text(l10n.tailsonalityResultNotFound, style: const TextStyle(color: AppColors.muted)),
                      )
                    : Center(
                        child: TextButton(
                          key: const ValueKey('tsResultRetry'),
                          onPressed: () => ref.invalidate(tailsonalityResultProvider(widget.token)),
                          child: Text(l10n.commonRetry),
                        ),
                      ),
                data: (_) => const SizedBox.shrink(),
              ),
      ),
    );
  }

  static bool _isNotFound(Object e) => e is DioException && e.response?.statusCode == 404;

  void _openMenu(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 顺序固定（C-7）：Bagikan / Pamer di postingan（Story 4.4）/ Tes Ulang。
    showTsResultMenu(context, [
      (
        key: const ValueKey('tsMenuShare'),
        icon: Icons.ios_share_rounded,
        label: l10n.tailsonalityMenuShare,
        onTap: () {
          final r = ref.read(tailsonalityResultProvider(widget.token)).value ?? widget.initial;
          if (r != null) openResultSharePreview(context, ref, r);
        },
      ),
      (
        key: const ValueKey('tsMenuBrag'),
        icon: Icons.edit_note_rounded,
        label: l10n.bragPostButton,
        onTap: () {
          final r = ref.read(tailsonalityResultProvider(widget.token)).value ?? widget.initial;
          if (r != null) _brag(r);
        },
      ),
      (
        key: const ValueKey('tsMenuRetake'),
        icon: Icons.refresh_rounded,
        label: l10n.tailsonalityMenuRetake,
        onTap: () => startTailsonalityRetake(context, ref),
      ),
    ]);
  }

  // ---------- Story 4.1 · AC5：点卡图看大图 ----------

  /// 截当前 3:4 卡面。🔴 未解锁截**含水印**的外层（大图 / 发帖图也得带水印），已解锁截内层。
  Future<Uint8List?> _captureCard(TailsonalityResult r) {
    final boundary = r.unlocked ? _cardKey : _cardWatermarkedKey;
    final capture = TailsonalityResultPage.captureForTest;
    return capture != null
        ? capture(boundary)
        : CardRenderPipeline.capture(boundaryKey: boundary, canvas: kTsCardCanvas);
  }

  /// ⋯「Pamer di postingan」（Story 4.4）：截 3:4 卡（与分享图同一水印规则）→ 带图带字进发帖页。
  ///
  /// 🔴 ⋯ 在任意滚动位置都能点，而卡在列表首项：滚远了它会被懒列表回收（截到空）或不在屏上（不绘制）。
  /// 所以先滚回顶部、等一帧再截；仍截不到给轻提示，不静默失败。
  Future<void> _brag(TailsonalityResult r) async {
    if (_opening) return;
    BragPostEntry.reportTap(BragPostSource.tailsonalityResult);
    _opening = true;
    try {
      if (_scroll.hasClients && _scroll.offset > 0) {
        _scroll.jumpTo(0);
        await WidgetsBinding.instance.endOfFrame;
      }
      if (!mounted) return;
      final bytes = await _captureCard(r);
      if (!mounted) return;
      if (bytes == null) {
        showAppToast(context, AppLocalizations.of(context).shareCardExportError);
        return;
      }
      await BragPostEntry.open(context, cardPng: bytes, text: AppLocalizations.of(context).tailsonalityBragResultText);
    } finally {
      _opening = false;
    }
  }

  /// 截当前卡面 → 内存图灯箱。
  Future<void> _openCardLightbox(TailsonalityResult r) async {
    if (_opening) return;
    _opening = true;
    try {
      final bytes = await _captureCard(r);
      if (!mounted || bytes == null) return;
      await ImageLightbox.openMemory(
        context,
        images: [bytes],
        initialIndex: 0,
        heroTagPrefix: _heroPrefix(r),
        source: 'tailsonality_result',
      );
    } finally {
      _opening = false;
    }
  }

  // ---------- AC8：锁态区首次进入可视区域 ----------

  void _scheduleViewedCheck() {
    if (_viewedReported) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final r = ref.read(tailsonalityResultProvider(widget.token)).value ?? widget.initial;
      if (r != null && !r.unlocked) _checkViewed(r);
    });
  }

  void _checkViewed(TailsonalityResult r) {
    if (_viewedReported) return;
    final price = ref.read(keepsakePricingProvider);
    if (price.isLoading && !price.hasValue) return; // 等价格（失败则不带 price 照报）
    final box = _lockedKey.currentContext?.findRenderObject();
    if (box is! RenderBox || !box.attached || !box.hasSize) return;
    final top = box.localToGlobal(Offset.zero).dy;
    if (top >= MediaQuery.sizeOf(context).height) return;
    _viewedReported = true;
    TailsonalityUnlockAnalytics.viewed(
        roleCode: r.typeCode,
        resultIndex: r.resultIndex,
        price: r.fullUnlockPrice(price.value));
  }

  // ---------- AC4 / AC6：购买 ----------

  Future<void> _startUnlock(TailsonalityResult r, String petName) async {
    if (_buying) return;
    setState(() => _buying = true);
    // 抽屉期间保活定价 provider（autoDispose）：确认埋点要读价格。
    final priceSub = ref.listenManual(keepsakePricingProvider, (_, _) {});
    final l10n = AppLocalizations.of(context);
    try {
      final outcome = await runKeepsakePurchase(
        context: context,
        ref: ref,
        sheet: (balance) => _TsPaywallSheet(result: r, petName: petName, balance: balance),
        start: (channel) => ref.read(tailsonalityRepositoryProvider).unlock(r.token, channel),
        pollPaid: () async => (await ref.refresh(tailsonalityResultProvider(r.token).future)).unlocked,
        purchasePurpose: 'TAILSONALITY',
        cashPriceIdr: () => r.fullUnlockPrice(priceSub.read().value),
        onChannelConfirmed: (channel) => TailsonalityUnlockAnalytics.initiated(
            roleCode: r.typeCode,
            resultIndex: r.resultIndex,
            method: channel,
            price: r.fullUnlockPrice(priceSub.read().value)),
      );
      if (!mounted || outcome == KeepsakeFlowOutcome.notCompleted) return;
      // 已解锁判定只看服务端：刷新结果（与列表），由服务端的 `unlocked` 切换页面。
      ref.invalidate(tailsonalityResultProvider(r.token));
      ref.invalidate(tailsonalityResultsProvider);
      // Story 3.3：首次解锁服务端会自动佩戴 → 档案卡小标要重取（全局 keep-alive provider，不刷就一直是旧的）。
      ref.invalidate(petProfileProvider);
      if (outcome == KeepsakeFlowOutcome.unlocked) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l10n.tailsonalityUnlockedToast)));
      }
    } finally {
      priceSub.close();
      if (mounted) setState(() => _buying = false);
    }
  }

  // ---------- AC7：挽留 ----------

  Future<void> _onBlockedPop(TailsonalityResult r, String petName) async {
    if (_retentionBusy || _buying) return;
    _retentionBusy = true;
    try {
      AppPrefs? prefs;
      try {
        prefs = await AppPrefs.create();
      } catch (_) {
        prefs = null; // 读不到本地标记：照弹（最多多弹一次），不能卡住返回。
      }
      if (!mounted) return;
      if (prefs?.tailsonalityRetentionShown(r.token) ?? false) {
        setState(() => _retentionDone = true);
        Navigator.of(context).pop();
        return;
      }
      // 弹出即记（不论点哪个按钮）。
      unawaited(prefs?.markTailsonalityRetentionShown(r.token));
      setState(() => _retentionDone = true);
      final unlock = await _showRetentionSheet(r, petName);
      if (!mounted) return;
      if (unlock == true) {
        await _startUnlock(r, petName);
      } else if (unlock == false) {
        TailsonalityUnlockAnalytics.abandoned(resultIndex: r.resultIndex);
        Navigator.of(context).pop();
      }
      // null = 点弹窗外 / 系统返回关掉弹窗：留在页面；下次返回直接走。
    } finally {
      _retentionBusy = false;
    }
  }

  /// 挽留抽屉：贴底、顶部圆角，样式照 B7「买这一版」等付费抽屉（拖拽条 + 标题 + 说明 + 并排双按钮）；
  /// 头部用「卡缩略 + 宠物名旁的小标预览」直观说明解锁后得到什么。
  /// 返回 true = 去解锁，false = 再看看（离开），null = 点外部 / 下滑关掉（留在页面）。
  Future<bool?> _showRetentionSheet(TailsonalityResult r, String petName) {
    final locale = Localizations.localeOf(context);
    const c = kTsRetentionDialog;
    final role = kTsRoles[r.letters];
    return showModalBottomSheet<bool>(
      context: context,
      backgroundColor: AppColors.card,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (ctx) => SafeArea(
        child: Padding(
          key: const ValueKey('tsRetentionDialog'),
          padding: const EdgeInsets.fromLTRB(20, 6, 20, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
                ),
              ),
              const SizedBox(height: 16),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: AppColors.tailsonalityBannerBg,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: AppColors.violet100),
                ),
                child: Row(
                  children: [
                    SizedBox(width: 56, child: TsResultCard(result: r, watermarked: true)),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Flexible(
                                child: Text(petName,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                        fontSize: 17, fontWeight: FontWeight.w800, color: AppColors.ink)),
                              ),
                              const SizedBox(width: 6),
                              TailsonalityBadgeChip(
                                  key: const ValueKey('tsRetentionBadgePreview'), letters: r.letters),
                            ],
                          ),
                          if (role != null) ...[
                            const SizedBox(height: 4),
                            Text(role.name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              Text(c.title.of(locale),
                  style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 8),
              Text(tsFillPet(c.body.of(locale), petName),
                  style: const TextStyle(color: AppColors.muted, fontSize: 13, height: 1.5)),
              const SizedBox(height: 18),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      key: const ValueKey('tsRetainLater'),
                      style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)),
                      onPressed: () => Navigator.of(ctx).pop(false),
                      child: Text(c.cancel.of(locale)),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      key: const ValueKey('tsRetainUnlock'),
                      style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(48),
                          backgroundColor: AppColors.mint,
                          foregroundColor: AppColors.onAccent),
                      onPressed: () => Navigator.of(ctx).pop(true),
                      child: Text(c.confirm.of(locale)),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

}

String _heroPrefix(TailsonalityResult r) => 'tailsonality_result_${r.token}';

class _Body extends StatelessWidget {
  const _Body({
    required this.result,
    required this.petName,
    required this.lockedKey,
    required this.scrollController,
    required this.cardKey,
    required this.cardWatermarkedKey,
    required this.onCardTap,
    required this.onShare,
    this.footer,
  });

  final TailsonalityResult result;
  final String petName;
  final GlobalKey lockedKey;
  final ScrollController scrollController;
  final GlobalKey cardKey;
  final GlobalKey cardWatermarkedKey;
  final VoidCallback onCardTap;
  final VoidCallback onShare;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final role = kTsRoles[result.letters];
    return ListView(
      key: const ValueKey('tsResultBody'),
      controller: scrollController,
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: GestureDetector(
              key: const ValueKey('tsResultCardTap'),
              onTap: onCardTap,
              child: Hero(
                tag: lightboxHeroTag(_heroPrefix(result), 0),
                child: TsResultCard(
                  result: result,
                  watermarked: !result.unlocked,
                  boundaryKey: cardKey,
                  watermarkedBoundaryKey: cardWatermarkedKey,
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 18),
        if (role != null)
          TsRichText(
            tsFillPet(role.summary.of(locale), petName),
            key: const ValueKey('tsResultSummary'),
            style: const TextStyle(fontSize: 15, height: 1.55, color: AppColors.ink),
          ),
        const SizedBox(height: 16),
        // 配型引流放在锁态区之前：配型可单独购买（2026-10-09），入口放在完整解读的墙后等于没有。
        Consumer(builder: (context, ref, _) {
          // 主人类型已设 → 对照缩略 + 档位标签；未设（或读失败）→ `?` 形态。改类型后回来随 provider 立即更新。
          // 2026-10-09 配型改回付费：未解锁配型时不露档位（档位就是付费内容），只显示引导文案 + 锁。
          final owner = ref.watch(tailsonalityOwnerTypeProvider).asData?.value;
          return TsMatchTeaser(
            petLetters: result.letters,
            ownerLetters: owner,
            sameCount: owner == null || !result.matchUnlocked ? null : computeTsMatch(owner, result.letters).sameCount,
            locked: !result.matchUnlocked,
            onTap: () => context.push(TailsonalityRoutes.match(result.token)),
          );
        }),
        const SizedBox(height: 16),
        if (!result.unlocked)
          TsLockedAnalysis(
            key: lockedKey,
            typeCode: result.typeCode,
            letters: result.letters,
            energy: result.energy,
            petName: petName,
            footer: footer,
          )
        else
          TsUnlockedAnalysis(
            typeCode: result.typeCode,
            letters: result.letters,
            energy: result.energy,
            petName: petName,
          ),
        if (result.unlocked) ...[
          const SizedBox(height: 20),
          // 已解锁态底部主 CTA（Story 4.1 · AC4.5）：与 ⋯「Bagikan」同一个预览入口。
          FilledButton(
            key: const ValueKey('tsResultShareCta'),
            onPressed: onShare,
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent,
              minimumSize: const Size.fromHeight(48),
            ),
            child: Text(AppLocalizations.of(context).tailsonalityMenuShare,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          ),
        ],
      ],
    );
  }
}

/// 锁态区底部购买按钮：价格只从服务端读（D-2），取价中「…」禁用，失败显示重试。
/// 已单独买过配型 → 显示服务端算好的补差价 [upgradePrice]。
class _UnlockCta extends ConsumerWidget {
  const _UnlockCta({this.upgradePrice, required this.busy, required this.onTap});

  final int? upgradePrice;
  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final price = ref.watch(keepsakePricingProvider);
    if (upgradePrice == null && price.hasError && !price.isLoading) {
      return Center(
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: PriceLoadRetry(
              key: const ValueKey('tsUnlockPriceRetry'), onRetry: () => ref.invalidate(keepsakePricingProvider)),
        ),
      );
    }
    final p = upgradePrice ?? price.value?.tailsonality;
    return FilledButton(
      key: const ValueKey('tsUnlockCta'),
      onPressed: p == null || busy ? null : onTap,
      style: FilledButton.styleFrom(
        backgroundColor: AppColors.mint,
        foregroundColor: AppColors.onAccent,
        minimumSize: const Size.fromHeight(48),
      ),
      child: Text(p == null ? '…' : l10n.tailsonalityUnlockCta(formatIdrAmount(p)),
          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
    );
  }
}

/// Tailsonality 选渠道抽屉：通用 [PayChannelPicker] + 结果卡缩略（带水印）+ 代号 + 角色名。
class _TsPaywallSheet extends ConsumerWidget {
  const _TsPaywallSheet({required this.result, required this.petName, required this.balance});

  final TailsonalityResult result;
  final String petName;
  final int balance;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final role = kTsRoles[result.letters];
    return PayChannelPicker(
      price: result.upgradePrice != null
          ? AsyncValue.data(result.upgradePrice!)
          : ref.watch(keepsakePricingProvider).whenData((p) => p.tailsonality),
      onRetryPrice: () => ref.invalidate(keepsakePricingProvider),
      balance: balance,
      title: l10n.tailsonalityPaywallTitle,
      body: result.upgradePrice != null
          ? l10n.tailsonalityPaywallUpgradeBody(petName)
          : l10n.tailsonalityPaywallBody(petName),
      confirmLabel: (p) => l10n.tailsonalityPayConfirm(p == null ? '…' : formatIdrAmount(p)),
      confirmKey: const ValueKey('tsPayConfirm'),
      retryKey: const ValueKey('tsPayPriceRetry'),
      header: Row(
        children: [
          SizedBox(width: 72, child: TsResultCard(result: result, watermarked: true)),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(result.typeCode,
                    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: AppColors.ink)),
                if (role != null) ...[
                  const SizedBox(height: 4),
                  Text(role.name, style: const TextStyle(fontSize: 14, color: AppColors.ink2)),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}
