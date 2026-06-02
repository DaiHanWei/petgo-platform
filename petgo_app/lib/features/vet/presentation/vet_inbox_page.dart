import 'package:dio/dio.dart';
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

/// 待接单 Tab（Story 5.5）：待接单请求列表（含 AI 上下文摘要）+ 接单。
///
/// 「接受」→ CAS 接单；成功跳兽医对话界面；并发被抢 → 「已被接走」提示 + 刷新列表。
class VetInboxPage extends ConsumerStatefulWidget {
  const VetInboxPage({super.key});

  @override
  ConsumerState<VetInboxPage> createState() => _VetInboxPageState();
}

class _VetInboxPageState extends ConsumerState<VetInboxPage> {
  late Future<List<VetInboxItem>> _items;
  int? _accepting;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() {
    _items = ref.read(vetRepositoryProvider).waitingList();
  }

  Future<void> _accept(VetInboxItem item) async {
    if (_accepting != null) return;
    setState(() => _accepting = item.sessionId);
    final l10n = AppLocalizations.of(context);
    try {
      final session = await ref.read(vetRepositoryProvider).accept(item.sessionId);
      if (!mounted) return;
      context.push('/vet/conversation/${session.id}');
    } on DioException catch (e) {
      if (!mounted) return;
      // 409 → 已被别的兽医接走。
      final msg = e.response?.statusCode == 409 ? l10n.vetInboxTaken : l10n.vetStatusUpdateFailed;
      ScaffoldMessenger.of(context)
        ..clearSnackBars()
        ..showSnackBar(SnackBar(content: Text(msg)));
      setState(_reload);
    } finally {
      if (mounted) setState(() => _accepting = null);
    }
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
              accepting: _accepting == items[i].sessionId,
              onAccept: () => _accept(items[i]),
            ),
          );
        },
      ),
    );
  }
}

class _InboxCard extends StatelessWidget {
  const _InboxCard({required this.item, required this.accepting, required this.onAccept});

  final VetInboxItem item;
  final bool accepting;
  final VoidCallback onAccept;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isYellow = item.aiDangerLevel == 'YELLOW';
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
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
          const SizedBox(height: AppSpacing.sm),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton(
              key: ValueKey('vetAccept_${item.sessionId}'),
              onPressed: accepting ? null : onAccept,
              child: Text(l10n.vetInboxAccept),
            ),
          ),
        ],
      ),
    );
  }
}
