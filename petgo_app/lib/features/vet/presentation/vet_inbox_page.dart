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
import 'widgets/vet_top_bar.dart';

/// 兽医工作台首页（dashboard，原型 V-01）：深色顶栏（问候 + 在线开关）+ 今日 3 统计卡
/// （队列/完成/评分）+「ANTRIAN SEKARANG (n)」当前队列。
///
/// 队列即 Story 5.2 抢单列表（决策 F11）：多在线兽医并发可见同批 WAITING 请求。点卡进
/// 请求详情/预览页（`/vet/request/:id`，进入即 3 分钟预览计时），接单走 5.3 DB 原子写。
class VetInboxPage extends ConsumerStatefulWidget {
  const VetInboxPage({super.key});

  @override
  ConsumerState<VetInboxPage> createState() => _VetInboxPageState();
}

class _VetInboxPageState extends ConsumerState<VetInboxPage> {
  late Future<List<VetInboxItem>> _items;
  String _displayName = '';
  int? _doneCount; // 完成数（history 列表长度，全量非仅今日）；null=加载中/失败 → 占位

  @override
  void initState() {
    super.initState();
    _reload();
    _loadHeaderStats();
  }

  void _reload() {
    _items = ref.read(vetRepositoryProvider).waitingList();
  }

  Future<void> _loadHeaderStats() async {
    final repo = ref.read(vetRepositoryProvider);
    try {
      final results = await Future.wait([repo.me(), repo.history()]);
      if (!mounted) return;
      setState(() {
        _displayName = (results[0] as dynamic).displayName as String;
        _doneCount = (results[1] as List).length;
      });
    } catch (_) {
      // 头部统计失败：名字留空、完成数占位「—」，不阻塞队列展示。
    }
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
      backgroundColor: AppColors.base,
      body: Column(
        children: [
          VetTopBar(greetingName: _displayName, showOnlineToggle: true),
          Expanded(
            child: FutureBuilder<List<VetInboxItem>>(
              future: _items,
              builder: (context, snapshot) {
                final loading = snapshot.connectionState == ConnectionState.waiting;
                final items = snapshot.data ?? const <VetInboxItem>[];
                return ListView(
                  padding: const EdgeInsets.all(AppSpacing.md),
                  children: [
                    _StatRow(
                      queue: loading ? null : items.length,
                      done: _doneCount,
                    ),
                    const SizedBox(height: AppSpacing.lg),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            l10n.vetDashboardQueueSection(loading ? 0 : items.length),
                            style: AppTypography.caption.copyWith(
                              color: AppColors.textSecondary,
                              letterSpacing: 0.6,
                            ),
                          ),
                        ),
                        IconButton(
                          key: const ValueKey('vetInboxRefresh'),
                          icon: const Icon(Icons.refresh, size: 20),
                          onPressed: () => setState(_reload),
                        ),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.sm),
                    if (loading)
                      const Padding(
                        padding: EdgeInsets.only(top: AppSpacing.xl),
                        child: Center(child: CircularProgressIndicator()),
                      )
                    else if (items.isEmpty)
                      VetEmptyState(icon: Icons.inbox_outlined, message: l10n.vetInboxEmpty)
                    else
                      ...items.map((it) => Padding(
                            padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                            child: _InboxCard(item: it, onTap: () => _openDetail(it)),
                          )),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

/// 今日 3 统计卡：队列 / 完成 / 评分。无数据源的评分恒占位「—」（不造假值）。
class _StatRow extends StatelessWidget {
  const _StatRow({required this.queue, required this.done});

  final int? queue; // null=加载中
  final int? done; // null=加载中/失败

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Row(
      children: [
        _StatCard(value: queue?.toString() ?? '—', label: l10n.vetDashboardStatQueue),
        const SizedBox(width: AppSpacing.sm),
        _StatCard(value: done?.toString() ?? '—', label: l10n.vetDashboardStatDone),
        const SizedBox(width: AppSpacing.sm),
        // 评分：暂无后端端点 → 占位「—」（见 spec / deferred）。
        _StatCard(value: '—', label: l10n.vetDashboardStatRating),
      ],
    );
  }
}

class _StatCard extends StatelessWidget {
  const _StatCard({required this.value, required this.label});

  final String value;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
        decoration: BoxDecoration(
          color: AppColors.vetSurface, // 薄荷浅底点缀（原型卡有 tint，非纯白）
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppColors.border),
        ),
        child: Column(
          children: [
            Text(value, style: AppTypography.display.copyWith(color: AppColors.vetPrimary)),
            const SizedBox(height: 2),
            Text(label, style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
          ],
        ),
      ),
    );
  }
}

/// 抢单请求卡片：症状摘要 + 图片张数 + AI 评级；整卡可点进详情/预览。
/// （视觉增强——AI 摘要框 / Lewati·Detail 双按钮 / 宠物 meta——留下一单元。）
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
