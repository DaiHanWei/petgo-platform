import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../data/id_card_repository.dart';
import '../domain/id_card.dart';

/// 身份证历史卡列表（Story 6-7）。点进 KTP 先看这个：历史快照卡列表 + 建卡入口。
/// 每张卡是一次信息快照（独立 serial，旧卡保留可看可下载）；点卡进详情，点建卡入口走独立建卡器。
class IdCardPage extends ConsumerWidget {
  const IdCardPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(idCardListProvider);
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(title: Text(l10n.idCardTitle)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => _centered(l10n.idCardLoadError),
        data: (cards) => cards.isEmpty ? _empty(context, l10n) : _list(context, l10n, cards),
      ),
    );
  }

  Widget _list(BuildContext context, AppLocalizations l10n, List<IdCard> cards) {
    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          Text(l10n.idCardListSubtitle,
              style: const TextStyle(fontSize: 12.5, color: AppColors.ink2, height: 1.5)),
          const SizedBox(height: 14),
          _createEntry(context, l10n),
          const SizedBox(height: 14),
          for (final card in cards) ...[
            _cardTile(context, l10n, card),
            const SizedBox(height: 10),
          ],
        ],
      ),
    );
  }

  Widget _empty(BuildContext context, AppLocalizations l10n) {
    return SafeArea(
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('🪪', style: TextStyle(fontSize: 44)),
              const SizedBox(height: 14),
              Text(l10n.idCardListEmptyTitle,
                  style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
              const SizedBox(height: 8),
              Text(l10n.idCardListEmptyBody,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, color: AppColors.ink2, height: 1.5)),
              const SizedBox(height: 22),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  key: const ValueKey('idCardCreateEntry'),
                  style: FilledButton.styleFrom(
                      backgroundColor: AppColors.mint, padding: const EdgeInsets.symmetric(vertical: 14)),
                  onPressed: () => context.push('/profile/id-cards/create'),
                  icon: const Icon(Icons.add),
                  label: Text(l10n.idCardCreateEntry),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _createEntry(BuildContext context, AppLocalizations l10n) {
    return Material(
      color: AppColors.mintTint,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        key: const ValueKey('idCardCreateEntry'),
        borderRadius: BorderRadius.circular(14),
        onTap: () => context.push('/profile/id-cards/create'),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: AppColors.mint, borderRadius: BorderRadius.circular(11)),
                child: const Icon(Icons.add, color: Colors.white),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(l10n.idCardCreateEntry,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.mint)),
              ),
              const Icon(Icons.chevron_right, color: AppColors.mint),
            ],
          ),
        ),
      ),
    );
  }

  Widget _cardTile(BuildContext context, AppLocalizations l10n, IdCard card) {
    final serial = card.serialId == null ? '----' : card.serialId!.toString().padLeft(4, '0');
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(14),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: ValueKey('idCardTile_${card.id}'),
        onTap: () => context.push('/profile/id-cards/${card.id}'),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                width: 46,
                height: 46,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: AppColors.mintTint, borderRadius: BorderRadius.circular(12)),
                child: const Text('🪪', style: TextStyle(fontSize: 22)),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(card.name ?? 'Pet',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
                    const SizedBox(height: 3),
                    Text(l10n.idCardSerialLabel(serial),
                        style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
                    if (card.createdAt != null) ...[
                      const SizedBox(height: 1),
                      Text(l10n.idCardCreatedLabel(formatDayMonthYear(context, card.createdAt!.toLocal())),
                          style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 8),
              if (card.hdUnlocked)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                      color: AppColors.mintTint2, borderRadius: BorderRadius.circular(8)),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.check_circle_rounded, size: 13, color: AppColors.mint),
                      const SizedBox(width: 4),
                      Text(l10n.idCardHdUnlockedBadge,
                          style: const TextStyle(
                              fontSize: 10.5, fontWeight: FontWeight.w700, color: AppColors.mint)),
                    ],
                  ),
                ),
              const SizedBox(width: 4),
              const Icon(Icons.chevron_right, color: AppColors.line),
            ],
          ),
        ),
      ),
    );
  }

  Widget _centered(String text) => Center(
      child: Padding(padding: const EdgeInsets.all(24), child: Text(text, textAlign: TextAlign.center)));
}
