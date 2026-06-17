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
  final Set<int> _skipped = {}; // Lewati 客户端本地跳过的 sessionId（不调后端；刷新后可重现）

  @override
  void initState() {
    super.initState();
    _reload();
    _loadHeaderStats();
  }

  void _reload() {
    _items = ref.read(vetRepositoryProvider).waitingList();
  }

  /// 显式刷新：重拉列表并清空本地跳过（详情返回的隐式刷新不清，跳过本会话内保留）。
  void _refresh() => setState(() {
        _skipped.clear();
        _reload();
      });

  /// Lewati：客户端本地移除该卡（抢单模式，不发起后端调用）。
  void _skip(VetInboxItem item) => setState(() => _skipped.add(item.sessionId));

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
                final items = (snapshot.data ?? const <VetInboxItem>[])
                    .where((it) => !_skipped.contains(it.sessionId))
                    .toList();
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
                          onPressed: _refresh,
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
                            child: _InboxCard(
                              item: it,
                              onDetail: () => _openDetail(it),
                              onSkip: () => _skip(it),
                            ),
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

/// 抢单请求卡片（原型 vet-queue.html）：等级徽章(绿/黄/红) + 等待时间 + RINGKASAN AI 摘要框
/// + Lewati(跳过) / Lihat Detail(详情) 双按钮；RED 额外加 ⚠️ 紧急横幅 + 红强调按钮。
/// DIRECT 项无 AI 框，仅「Direct request」。
///
/// 注：宠物名/种类/年龄/主人 原型有，但 `VetInboxItem` 无字段、后端无契约 → 本步 omit（见 deferred）。
class _InboxCard extends StatelessWidget {
  const _InboxCard({required this.item, required this.onDetail, required this.onSkip});

  final VetInboxItem item;
  final VoidCallback onDetail;
  final VoidCallback onSkip;

  bool get _isRed => item.aiDangerLevel == 'RED';

  Color _levelColor() {
    switch (item.aiDangerLevel) {
      case 'RED':
        return AppColors.triageRed;
      case 'YELLOW':
        return AppColors.triageYellow;
      default:
        return AppColors.triageGreen;
    }
  }

  String _levelLabel(AppLocalizations l10n) {
    switch (item.aiDangerLevel) {
      case 'RED':
        return l10n.vetAiContextLevelRed;
      case 'YELLOW':
        return l10n.vetAiContextLevelYellow;
      default:
        return l10n.vetAiContextLevelGreen;
    }
  }

  String _waitingLabel(AppLocalizations l10n) {
    final s = item.waitingElapsedSeconds;
    return s < 60 ? l10n.vetQueueWaitingJustNow : l10n.vetQueueWaitingMinutes(s ~/ 60);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final accent = _levelColor();
    return Container(
      key: ValueKey('vetRequestCard_${item.sessionId}'),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: _isRed ? AppColors.triageRed : AppColors.border, width: _isRed ? 1.5 : 1),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_isRed)
            Container(
              width: double.infinity,
              color: AppColors.triageRed,
              padding: const EdgeInsets.symmetric(vertical: 6, horizontal: AppSpacing.md),
              child: Text(
                l10n.vetQueueUrgentBanner,
                style: AppTypography.caption.copyWith(color: AppColors.onAccent),
              ),
            ),
          Padding(
            padding: const EdgeInsets.all(AppSpacing.md),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 顶行：等级徽章 + 等待时间
                Row(
                  children: [
                    if (item.isAiUpgrade)
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                        decoration: BoxDecoration(
                          color: accent.withValues(alpha: 0.12),
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(
                          _levelLabel(l10n),
                          style: AppTypography.micro.copyWith(color: accent, fontWeight: FontWeight.w700),
                        ),
                      )
                    else
                      Text(l10n.vetInboxDirect, style: AppTypography.title),
                    const Spacer(),
                    Text(_waitingLabel(l10n), style: AppTypography.micro.copyWith(color: AppColors.textTertiary)),
                  ],
                ),
                // RINGKASAN AI 摘要框（仅 AI 升级项）
                if (item.isAiUpgrade) ...[
                  const SizedBox(height: AppSpacing.sm),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(AppSpacing.sm),
                    decoration: BoxDecoration(
                      color: AppColors.vetSurface,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          l10n.vetQueueAiSummaryTitle,
                          style: AppTypography.micro.copyWith(color: AppColors.vetPrimaryDeep, letterSpacing: 0.5),
                        ),
                        if (item.symptomPreview != null) ...[
                          const SizedBox(height: 4),
                          Text(item.symptomPreview!, style: AppTypography.body),
                        ],
                        const SizedBox(height: 4),
                        Text(
                          item.imageCount > 0 ? l10n.vetQueuePhotosAttached(item.imageCount) : l10n.vetQueueNoPhoto,
                          style: AppTypography.caption.copyWith(color: AppColors.textTertiary),
                        ),
                      ],
                    ),
                  ),
                ],
                const SizedBox(height: AppSpacing.sm),
                // 底行：Lewati / Lihat Detail 双按钮
                Row(
                  children: [
                    TextButton(
                      key: ValueKey('vetSkip_${item.sessionId}'),
                      onPressed: onSkip,
                      child: Text(l10n.vetQueueSkip),
                    ),
                    const Spacer(),
                    FilledButton(
                      key: ValueKey('vetDetail_${item.sessionId}'),
                      onPressed: onDetail,
                      style: FilledButton.styleFrom(
                        backgroundColor: _isRed ? AppColors.triageRed : AppColors.vetPrimary,
                        foregroundColor: AppColors.onAccent,
                      ),
                      child: Text(l10n.vetQueueViewDetail),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
