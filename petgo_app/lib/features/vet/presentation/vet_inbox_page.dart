import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/vet_repository.dart';
import '../domain/vet_inbox_item.dart';
import 'vet_empty_state.dart';

/// 待接单 Tab（Story 5.2 AC5 · 决策 F11 抢单模式）：多在线兽医并发可见同一批 WAITING 请求卡片。
///
/// 点请求卡片进**请求详情/预览页**（`/vet/request/:id`，进入即 3 分钟预览计时），接单走 5.3 的
/// DB 原子写（先到先得，影响 0 行 = 已被抢）。本页只负责列表展示 + 跳详情；接单/三态返回在详情页。
class VetInboxPage extends ConsumerStatefulWidget {
  const VetInboxPage({super.key});

  @override
  ConsumerState<VetInboxPage> createState() => _VetInboxPageState();
}

class _VetInboxPageState extends ConsumerState<VetInboxPage> {
  late Future<List<VetInboxItem>> _items;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() {
    _items = ref.read(vetRepositoryProvider).waitingList();
  }

  /// 点卡片 → 请求详情/预览页；返回后刷新列表（被抢/取消/超时的项已不再 WAITING）。
  Future<void> _openDetail(VetInboxItem item) async {
    await context.push('/vet/request/${item.sessionId}', extra: item);
    if (mounted) setState(_reload);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.vetTabInbox),
        actions: [
          IconButton(
            key: const ValueKey('vetInboxRefresh'),
            icon: const Icon(Icons.refresh),
            onPressed: () => setState(_reload),
          ),
        ],
      ),
      body: FutureBuilder<List<VetInboxItem>>(
        future: _items,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          final items = snapshot.data ?? const [];
          if (items.isEmpty) {
            return VetEmptyState(icon: Icons.inbox_outlined, message: l10n.vetInboxEmpty);
          }
          return ListView.separated(
            padding: const EdgeInsets.all(AppSpacing.md),
            itemCount: items.length,
            separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
            itemBuilder: (ctx, i) => _InboxCard(
              item: items[i],
              onTap: () => _openDetail(items[i]),
            ),
          );
        },
      ),
    );
  }
}

/// 抢单请求卡片：症状摘要 + 图片张数 + AI 评级；整卡可点进详情/预览。
class _InboxCard extends StatelessWidget {
  const _InboxCard({required this.item, required this.onTap});

  final VetInboxItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isYellow = item.aiDangerLevel == 'YELLOW';
    return InkWell(
      key: ValueKey('vetRequestCard_${item.sessionId}'),
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.border),
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (item.isAiUpgrade) ...[
                    Text(
                      isYellow ? l10n.vetAiContextLevelYellow : l10n.vetAiContextLevelGreen,
                      style: AppTypography.caption.copyWith(
                        color: isYellow ? AppColors.triageYellow : AppColors.triageGreen,
                      ),
                    ),
                    if (item.symptomPreview != null) ...[
                      const SizedBox(height: 4),
                      Text(item.symptomPreview!, style: AppTypography.body),
                    ],
                    if (item.imageCount > 0) ...[
                      const SizedBox(height: 4),
                      Text(l10n.vetInboxImages(item.imageCount), style: AppTypography.caption),
                    ],
                  ] else
                    Text(l10n.vetInboxDirect, style: AppTypography.body),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
