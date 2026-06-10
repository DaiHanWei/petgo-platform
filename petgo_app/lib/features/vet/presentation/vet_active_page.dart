import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/vet_repository.dart';
import '../domain/vet_workbench_lists.dart';
import 'vet_empty_state.dart';

/// 进行中 Tab：兽医当前已接单、对话中的会话列表。点卡进 [VetConversationPage]（IM 占位聊天）。
class VetActivePage extends ConsumerStatefulWidget {
  const VetActivePage({super.key});

  @override
  ConsumerState<VetActivePage> createState() => _VetActivePageState();
}

class _VetActivePageState extends ConsumerState<VetActivePage> {
  late Future<List<VetActiveItem>> _items;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() {
    _items = ref.read(vetRepositoryProvider).activeSessions();
  }

  Future<void> _open(VetActiveItem item) async {
    await context.push('/vet/conversation/${item.sessionId}');
    if (mounted) setState(_reload);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.vetTabActive),
        actions: [
          IconButton(
            key: const ValueKey('vetActiveRefresh'),
            icon: const Icon(Icons.refresh),
            onPressed: () => setState(_reload),
          ),
        ],
      ),
      body: FutureBuilder<List<VetActiveItem>>(
        future: _items,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          final items = snapshot.data ?? const [];
          if (items.isEmpty) {
            return VetEmptyState(icon: Icons.chat_outlined, message: l10n.vetActiveEmpty);
          }
          return ListView.separated(
            padding: const EdgeInsets.all(AppSpacing.md),
            itemCount: items.length,
            separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
            itemBuilder: (ctx, i) => _ActiveCard(item: items[i], onTap: () => _open(items[i])),
          );
        },
      ),
    );
  }
}

class _ActiveCard extends StatelessWidget {
  const _ActiveCard({required this.item, required this.onTap});

  final VetActiveItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      key: ValueKey('vetActiveCard_${item.sessionId}'),
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
            CircleAvatar(
              backgroundColor: AppColors.accentConsult.withValues(alpha: 0.15),
              child: const Icon(Icons.pets, color: AppColors.accentConsult),
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(item.petName, style: AppTypography.body),
                  const SizedBox(height: 2),
                  Text(
                    item.lastMessage,
                    style: AppTypography.caption,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
            if (item.unread > 0) ...[
              const SizedBox(width: AppSpacing.sm),
              Container(
                padding: const EdgeInsets.all(6),
                decoration: const BoxDecoration(color: AppColors.danger, shape: BoxShape.circle),
                constraints: const BoxConstraints(minWidth: 22, minHeight: 22),
                child: Text(
                  '${item.unread}',
                  textAlign: TextAlign.center,
                  style: AppTypography.caption.copyWith(color: Colors.white),
                ),
              ),
            ],
            const Icon(Icons.chevron_right, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
