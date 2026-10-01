import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/pay_channel_picker.dart';
import '../../../shared/widgets/price_load_retry.dart';
import '../../keepsake/data/keepsake_repository.dart';
import '../../keepsake/presentation/keepsake_pay_flow.dart';
import '../../place/presentation/place_detail_page.dart';
import '../data/boarding_pass_repository.dart';
import '../domain/boarding_pass.dart';
import 'share/boarding_pass_share_card.dart';
import 'widgets/boarding_pass_card.dart';
import 'widgets/boarding_pass_unlock_sheet.dart';

/// 登机牌详情 B3b（未解锁）/ B3c（已解锁）（V1.3.2 Story 3.5 · AC9）。
///
/// 自上而下：一张完整登机牌卡（未解锁叠水印）→ 卡外场所名 → 地址条（ACTIVE 可点进场所详情；
/// 下架 / 解析不到 →「Tempat tidak ditemukan」）→「Pertama · Terakhir」。
/// 未解锁吸底「Buka Rp{价}」→ B7b → 选渠道；已解锁**不放**吸底 CTA（发帖属 4.4）。
///
/// V1.3.2 Story 4.3：顶栏右上分享按钮（两态都有）→ 登机牌卡预览；水印按**该张**解锁态。
/// 放顶栏是因为两态底部都已被占（B3b 解锁 CTA / B3c 留给 Pamer di postingan），而 PRD 要求未解锁也可出卡（带水印）。
class BoardingPassDetailPage extends ConsumerStatefulWidget {
  const BoardingPassDetailPage({super.key, required this.placeToken});

  final String placeToken;

  @override
  ConsumerState<BoardingPassDetailPage> createState() => _BoardingPassDetailPageState();
}

class _BoardingPassDetailPageState extends ConsumerState<BoardingPassDetailPage> {
  bool _buying = false;

  Future<void> _buy(BoardingPassDetail d) async {
    if (_buying) return;
    final l10n = AppLocalizations.of(context);
    final price = ref.read(keepsakePricingProvider).value?.boardingPass;
    if (price == null) return;
    final go = await showBoardingPassUnlockSheet(context, placeName: d.placeName, price: price);
    if (go != true || !mounted) return;
    setState(() => _buying = true);
    try {
      final outcome = await runKeepsakePurchase(
        context: context,
        ref: ref,
        sheet: (balance) => BoardingPassPayPicker(pass: d, balance: balance),
        start: (channel) => ref.read(boardingPassRepositoryProvider).unlock(widget.placeToken, channel),
        pollPaid: () async => (await ref.refresh(boardingPassDetailProvider(widget.placeToken).future)).unlocked,
      );
      if (!mounted || outcome == KeepsakeFlowOutcome.notCompleted) return;
      ref.invalidate(boardingPassDetailProvider(widget.placeToken));
      ref.invalidate(boardingPassListProvider);
      if (outcome == KeepsakeFlowOutcome.unlocked) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l10n.boardingPassUnlockedToast)));
      }
    } finally {
      if (mounted) setState(() => _buying = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(boardingPassDetailProvider(widget.placeToken));
    final d = async.value;
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        title: Text(l10n.boardingPassListTitle),
        actions: [
          if (d != null)
            IconButton(
              key: const ValueKey('boardingPassShare'),
              tooltip: l10n.boardingPassShareTooltip,
              constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
              icon: const Icon(Icons.ios_share_rounded),
              onPressed: () => openBoardingPassSharePreview(context, ref, d),
            ),
        ],
      ),
      bottomNavigationBar: d == null || d.unlocked ? null : _UnlockBar(busy: _buying, onTap: () => _buy(d)),
      body: d != null
          ? _Body(pass: d)
          : async.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => EmptyState(
                title: e is DioException && e.response?.statusCode == 404
                    ? l10n.placeUnavailableTitle
                    : l10n.passportLoadFailed,
                icon: Icons.cloud_off_rounded,
                actionLabel: l10n.placeRetry,
                onAction: () => ref.invalidate(boardingPassDetailProvider(widget.placeToken)),
              ),
              data: (_) => const SizedBox.shrink(),
            ),
    );
  }
}

class _Body extends StatelessWidget {
  const _Body({required this.pass});

  final BoardingPassDetail pass;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final first = pass.firstVisitDate == null ? '—' : formatDayMonthYear(context, pass.firstVisitDate!);
    final last = pass.lastVisitDate == null ? '—' : formatDayMonthYear(context, pass.lastVisitDate!);
    final address = [?pass.addressText, ?pass.city].join(', ');
    return ListView(
      key: const ValueKey('boardingPassDetail'),
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        BoardingPassCard(pass: pass, watermarked: !pass.unlocked),
        const SizedBox(height: 18),
        Text(pass.placeName,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: AppColors.ink)),
        const SizedBox(height: 10),
        if (pass.available)
          Material(
            color: AppColors.card,
            borderRadius: BorderRadius.circular(12),
            child: InkWell(
              key: const ValueKey('boardingPassAddress'),
              borderRadius: BorderRadius.circular(12),
              onTap: () => context.push(PlaceDetailPage.routeFor(pass.placeToken)),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                child: Row(children: [
                  const Icon(Icons.place_outlined, size: 18, color: AppColors.mint),
                  const SizedBox(width: 8),
                  Expanded(
                      child: Text(address.isEmpty ? pass.placeName : address,
                          style: const TextStyle(fontSize: 13, color: AppColors.ink2))),
                  const Icon(Icons.chevron_right, color: AppColors.muted),
                ]),
              ),
            ),
          )
        else
          Container(
            key: const ValueKey('boardingPassPlaceGone'),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            decoration: BoxDecoration(color: AppColors.cream2, borderRadius: BorderRadius.circular(12)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(l10n.placeUnavailableTitle,
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
              const SizedBox(height: 2),
              Text(l10n.placeUnavailableBody, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
            ]),
          ),
        const SizedBox(height: 10),
        Text(l10n.boardingPassFirstLast(first, last),
            key: const ValueKey('boardingPassFirstLast'),
            style: const TextStyle(fontSize: 12.5, color: AppColors.textSecondary)),
      ],
    );
  }
}

class _UnlockBar extends ConsumerWidget {
  const _UnlockBar({required this.busy, required this.onTap});

  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final price = ref.watch(keepsakePricingProvider);
    final Widget child;
    if (price.hasError && !price.isLoading) {
      child = Center(
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: PriceLoadRetry(
              key: const ValueKey('boardingPassPriceRetry'), onRetry: () => ref.invalidate(keepsakePricingProvider)),
        ),
      );
    } else {
      final p = price.value?.boardingPass;
      child = FilledButton(
        key: const ValueKey('boardingPassUnlockCta'),
        onPressed: p == null || busy ? null : onTap,
        style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(48),
            backgroundColor: AppColors.mint,
            foregroundColor: AppColors.onAccent),
        child: Text(p == null ? '…' : l10n.boardingPassUnlockCta(formatIdrAmount(p))),
      );
    }
    return SafeArea(minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12), child: child);
  }
}
