import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/pay_channel_picker.dart';
import '../../keepsake/data/keepsake_repository.dart';
import '../../place/presentation/place_list_page.dart';
import '../../place/presentation/widgets/place_stamp_view.dart';
import '../../profile/presentation/pet_insights_page.dart';
import '../data/boarding_pass_repository.dart';
import '../domain/boarding_pass.dart';

/// 登机牌列表 B3（V1.3.2 Story 3.5 · AC8）：每个去过的地方一张，最近到访在前；点卡进详情。
///
/// 右侧解锁态：已解锁「Terbuka」/ 未解锁「Rp{价}」（价格未取到 →「…」，不影响进详情）。
class BoardingPassListPage extends ConsumerWidget {
  const BoardingPassListPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(boardingPassListProvider);
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(backgroundColor: AppColors.cream, title: Text(l10n.boardingPassListTitle)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => EmptyState(
          title: l10n.passportLoadFailed,
          icon: Icons.cloud_off_rounded,
          actionLabel: l10n.placeRetry,
          onAction: () => ref.invalidate(boardingPassListProvider),
        ),
        data: (list) => list.items.isEmpty
            ? EmptyState(
                key: const ValueKey('boardingPassEmpty'),
                title: l10n.boardingPassEmptyTitle,
                message: l10n.boardingPassEmptyBody,
                icon: Icons.airplane_ticket_outlined,
                actionLabel: l10n.boardingPassFindPlaces,
                onAction: () => context.push(PlaceListPage.routePath),
              )
            : ListView.separated(
                key: const ValueKey('boardingPassList'),
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                itemCount: list.items.length,
                separatorBuilder: (_, _) => const SizedBox(height: 10),
                itemBuilder: (context, i) => _Row(list: list, item: list.items[i]),
              ),
      ),
    );
  }
}

class _Row extends ConsumerWidget {
  const _Row({required this.list, required this.item});

  final BoardingPassList list;
  final BoardingPassItem item;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final price = ref.watch(keepsakePricingProvider).value?.boardingPass;
    final sub = [list.petName.toUpperCase(), ?list.passportNo].join(' · ');
    return Material(
      color: AppColors.card,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        key: ValueKey('boardingPassRow_${item.placeToken}'),
        borderRadius: BorderRadius.circular(14),
        onTap: () => context.push(PetInsightsRoutes.boardingPassFor(item.placeToken)),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              PlaceStampView(placeType: item.placeType, imageUrl: item.stampImageUrl, size: 52),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(item.placeName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
                    const SizedBox(height: 2),
                    Text(sub,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 12, color: AppColors.ink2, fontFeatures: [FontFeature.tabularFigures()])),
                    if (item.lastVisitDate != null) ...[
                      const SizedBox(height: 2),
                      Text(formatDayMonthYear(context, item.lastVisitDate!),
                          style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    item.unlocked ? l10n.boardingPassUnlocked : (price == null ? '…' : 'Rp${formatIdrAmount(price)}'),
                    key: ValueKey('boardingPassState_${item.placeToken}'),
                    style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: item.unlocked ? AppColors.mint : AppColors.ink),
                  ),
                  const SizedBox(height: 4),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                    decoration: BoxDecoration(color: AppColors.mintTint, borderRadius: BorderRadius.circular(9999)),
                    child: Text(l10n.boardingPassVisits(item.visitCount),
                        key: ValueKey('boardingPassVisits_${item.placeToken}'),
                        style: const TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w700,
                            color: AppColors.mint,
                            fontFeatures: [FontFeature.tabularFigures()])),
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
