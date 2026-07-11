import 'dart:async';

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

class _VetInboxPageState extends ConsumerState<VetInboxPage> with WidgetsBindingObserver {
  /// 队列轮询间隔（抢单列表实时性；退后台暂停）。
  static const Duration _pollInterval = Duration(seconds: 8);

  List<VetInboxItem>? _items; // null = 首次加载中
  Timer? _poll;
  String _displayName = '';
  String? _avatarUrl; // 运营后台上传的头像；null → 首字母占位
  int? _doneCount; // 完成数（history 列表长度，全量非仅今日）；null=加载中/失败 → 占位
  final Set<int> _skipped = {}; // Lewati 客户端本地跳过的 sessionId（不调后端；刷新后可重现）

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _reload();
    _loadHeaderStats();
    _startPoll();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _poll?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 退后台停轮询省流量/电；回前台立即拉一次再恢复轮询。
    if (state == AppLifecycleState.resumed) {
      _reload();
      _startPoll();
    } else {
      _poll?.cancel();
    }
  }

  void _startPoll() {
    _poll?.cancel();
    _poll = Timer.periodic(_pollInterval, (_) => _reload());
  }

  /// 拉抢单列表（轮询 / 手动共用）：静默刷新，保留旧数据避免闪烁。
  /// 失败时：已有数据则保留（不闪空态）；首次加载就失败则落空态（不卡死在 spinner）。
  Future<void> _reload() async {
    try {
      final list = await ref.read(vetRepositoryProvider).waitingList();
      if (mounted) setState(() => _items = list);
    } catch (_) {
      if (mounted && _items == null) setState(() => _items = const []);
    }
  }

  /// 显式刷新：重拉列表并清空本地跳过（详情返回的隐式刷新不清，跳过本会话内保留）。
  void _refresh() {
    setState(() => _skipped.clear());
    _reload();
  }

  /// Lewati：客户端本地移除该卡（抢单模式，不发起后端调用）。
  void _skip(VetInboxItem item) => setState(() => _skipped.add(item.sessionId));

  Future<void> _loadHeaderStats() async {
    final repo = ref.read(vetRepositoryProvider);
    try {
      final results = await Future.wait([repo.me(), repo.history()]);
      if (!mounted) return;
      setState(() {
        _displayName = (results[0] as dynamic).displayName as String;
        _avatarUrl = (results[0] as dynamic).avatarUrl as String?;
        _doneCount = (results[1] as List).length;
      });
    } catch (_) {
      // 头部统计失败：名字留空、完成数占位「—」，不阻塞队列展示。
    }
  }

  /// 点卡片 → 请求详情/预览页；返回后刷新列表（被抢/取消/超时的项已不再 WAITING）。
  Future<void> _openDetail(VetInboxItem item) async {
    await context.push('/vet/request/${item.sessionId}', extra: item);
    _reload();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: Column(
        children: [
          VetTopBar(greetingName: _displayName, avatarUrl: _avatarUrl, showOnlineToggle: true),
          Expanded(
            child: Builder(
              builder: (context) {
                final loading = _items == null; // 仅首次加载显 spinner；轮询保留旧数据不闪
                final items = (_items ?? const <VetInboxItem>[])
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
        // 原型 vet-dashboard.html：三卡各异色（Antrian 紫 / Selesai 薄荷 / Rating 金），无边框。
        _StatCard(
            value: queue?.toString() ?? '—',
            label: l10n.vetDashboardStatQueue,
            bg: AppColors.cream2,
            valueColor: AppColors.mint),
        const SizedBox(width: AppSpacing.sm),
        _StatCard(
            value: done?.toString() ?? '—',
            label: l10n.vetDashboardStatDone,
            bg: AppColors.vetSurface,
            valueColor: AppColors.vetPrimary),
        const SizedBox(width: AppSpacing.sm),
        // 评分：暂无后端端点 → 占位「—」（见 spec / deferred）。
        _StatCard(
            value: '—',
            label: l10n.vetDashboardStatRating,
            bg: AppColors.goldTint,
            valueColor: AppColors.gold),
      ],
    );
  }
}

class _StatCard extends StatelessWidget {
  const _StatCard({required this.value, required this.label, required this.bg, required this.valueColor});

  final String value;
  final String label;
  final Color bg;
  final Color valueColor;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
        decoration: BoxDecoration(
          color: bg, // 原型三卡各异 tint，无边框
          borderRadius: BorderRadius.circular(14),
        ),
        child: Column(
          children: [
            Text(value, style: AppTypography.display.copyWith(color: valueColor)),
            const SizedBox(height: 2),
            Text(label, style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
          ],
        ),
      ),
    );
  }
}

/// 抢单请求卡片（原型 vet-queue.html）：宠物身份块（头像+名+meta）+ 等级徽章(绿/黄/红)
/// + 等待时间 + RINGKASAN AI 摘要框(按等级配色) + Lewati/Lihat Detail 双按钮；RED 加 ⚠️ 紧急横幅。
/// 身份字段全 nullable：`petName==null`（真后端未下发）→ 优雅降级不显身份块。
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

  String _speciesEmoji() {
    switch (item.petSpecies) {
      case 'CAT':
        return '🐱';
      case 'DOG':
        return '🐶';
      default:
        return '🐾';
    }
  }

  /// meta 行：「种类 · 年龄 · @主人」，缺项跳过（无性别——后端不下发 petSex）。
  String _metaLine(AppLocalizations l10n) {
    final parts = <String>[];
    switch (item.petSpecies) {
      case 'CAT':
        parts.add(l10n.vetSpeciesCat);
      case 'DOG':
        parts.add(l10n.vetSpeciesDog);
    }
    final m = item.petAgeMonths;
    if (m != null) parts.add(m >= 12 ? l10n.vetAgeYears(m ~/ 12) : l10n.vetAgeMonths(m));
    if (item.ownerHandle != null) parts.add('@${item.ownerHandle}');
    return parts.join(' · ');
  }

  /// 等级徽章配色（文字/底）：黄→琥珀、红→珊瑚、绿/DIRECT→薄荷。
  (Color, Color) _badgeColors() {
    switch (item.aiDangerLevel) {
      case 'RED':
        return (AppColors.healthEventText, AppColors.coralTint);
      case 'YELLOW':
        return (AppColors.tipsBadgeText, AppColors.goldTint);
      default:
        return (AppColors.vetPrimary, AppColors.vetSurface);
    }
  }

  /// 等级徽章 emoji（前缀，对齐原型 vet-queue.html）；标签走 l10n。
  String _badgeEmoji() {
    switch (item.aiDangerLevel) {
      case 'RED':
        return '🔴';
      case 'YELLOW':
        return '🟡';
      default:
        return '🟢';
    }
  }

  String _badgeLabel(AppLocalizations l10n) {
    switch (item.aiDangerLevel) {
      case 'RED':
        return l10n.vetQueueLevelRed;
      case 'YELLOW':
        return l10n.vetQueueLevelYellow;
      default:
        return l10n.vetQueueLevelGreen;
    }
  }

  /// 顶部色条颜色（4px）：按等级；DIRECT→薄荷。
  Color _stripColor() {
    switch (item.aiDangerLevel) {
      case 'RED':
        return AppColors.triageRed;
      case 'YELLOW':
        return AppColors.triageYellow;
      default:
        return AppColors.vetPrimary;
    }
  }

  String _waitingShort(AppLocalizations l10n) {
    final s = item.waitingElapsedSeconds;
    return s < 60 ? l10n.vetQueueWaitJustNow : l10n.vetQueueWaitMinutesAgo(s ~/ 60);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (badgeFg, badgeBg) = _badgeColors();
    return Container(
      key: ValueKey('vetRequestCard_${item.sessionId}'),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(color: AppColors.ink.withValues(alpha: 0.06), blurRadius: 12, offset: const Offset(0, 3)),
        ],
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 顶部 4px 等级色条
          Container(height: 4, color: _stripColor()),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 头行：头像+名/meta（左） · 等级徽章+时间（右）
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Row(
                        children: [
                          Container(
                            width: 40,
                            height: 40,
                            alignment: Alignment.center,
                            decoration: BoxDecoration(
                              color: (item.isAiUpgrade ? _levelColor() : AppColors.vetPrimary)
                                  .withValues(alpha: 0.14),
                              shape: BoxShape.circle,
                            ),
                            child: Text(_speciesEmoji(), style: const TextStyle(fontSize: 18)),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(item.petName ?? l10n.vetInboxDirect,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: AppTypography.title.copyWith(color: AppColors.ink)),
                                if (_metaLine(l10n).isNotEmpty)
                                  Text(_metaLine(l10n),
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: AppTypography.caption
                                          .copyWith(color: AppColors.textSecondary)),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                          decoration: BoxDecoration(
                            color: badgeBg,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(_badgeEmoji(), style: AppTypography.micro),
                              const SizedBox(width: 4),
                              Text(_badgeLabel(l10n),
                                  style: AppTypography.micro
                                      .copyWith(color: badgeFg, fontWeight: FontWeight.w700)),
                            ],
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(_waitingShort(l10n),
                            style: AppTypography.micro.copyWith(color: AppColors.textTertiary)),
                      ],
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                // 摘要：RED→PERHATIAN SEGERA 框 / YELLOW→RINGKASAN AI 框 / 其余→纯文本行
                _summary(l10n),
                const SizedBox(height: 12),
                // CTA：RED→单个红色「Tangani Sekarang」；其余→Lewati + Lihat Detail
                _isRed
                    ? SizedBox(
                        width: double.infinity,
                        child: FilledButton(
                          key: ValueKey('vetDetail_${item.sessionId}'),
                          onPressed: onDetail,
                          style: FilledButton.styleFrom(
                            backgroundColor: AppColors.triageRed,
                            foregroundColor: AppColors.onAccent,
                            padding: const EdgeInsets.symmetric(vertical: 11),
                          ),
                          child: Text('⚠ ${l10n.vetQueueHandleNow}'),
                        ),
                      )
                    : Row(
                        children: [
                          Expanded(
                            child: OutlinedButton(
                              key: ValueKey('vetSkip_${item.sessionId}'),
                              onPressed: onSkip,
                              style: OutlinedButton.styleFrom(
                                foregroundColor: AppColors.textSecondary,
                                side: BorderSide(color: AppColors.border, width: 1.5),
                                padding: const EdgeInsets.symmetric(vertical: 11),
                              ),
                              child: Text(l10n.vetQueueSkip),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            flex: 2,
                            child: FilledButton(
                              key: ValueKey('vetDetail_${item.sessionId}'),
                              onPressed: onDetail,
                              style: FilledButton.styleFrom(
                                backgroundColor: AppColors.vetPrimary,
                                foregroundColor: AppColors.onAccent,
                                padding: const EdgeInsets.symmetric(vertical: 11),
                              ),
                              child: Text('${l10n.vetQueueViewDetail} →'),
                            ),
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

  /// 摘要区：RED/YELLOW 标签框；GREEN/DIRECT 纯文本行（含照片状态后缀）。
  Widget _summary(AppLocalizations l10n) {
    if (_isRed || item.aiDangerLevel == 'YELLOW') {
      final box = _isRed ? AppColors.coralTint : AppColors.goldTint;
      final titleColor = _isRed ? AppColors.healthEventText : AppColors.tipsBadgeText;
      final title = _isRed ? l10n.vetQueueUrgentBanner : l10n.vetQueueAiSummaryTitle;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(color: box, borderRadius: BorderRadius.circular(10)),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: AppTypography.caption
                        .copyWith(color: titleColor, fontWeight: FontWeight.w700)),
                if (item.symptomPreview != null) ...[
                  const SizedBox(height: 4),
                  Text(item.symptomPreview!,
                      style: AppTypography.body.copyWith(height: 1.5)),
                ],
              ],
            ),
          ),
          if (item.imageCount > 0) ...[
            const SizedBox(height: 10),
            Row(
              children: [
                Icon(Icons.grid_view_rounded, size: 13, color: AppColors.textTertiary),
                const SizedBox(width: 6),
                Text(l10n.vetQueuePhotosAttached(item.imageCount),
                    style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
                const SizedBox(width: 8),
                for (var i = 0; i < item.imageCount.clamp(0, 3); i++) ...[
                  if (i > 0) const SizedBox(width: 4),
                  Container(
                    width: 28,
                    height: 28,
                    decoration: BoxDecoration(
                      color: i.isEven ? AppColors.skyTint : AppColors.goldTint,
                      borderRadius: BorderRadius.circular(7),
                    ),
                    alignment: Alignment.center,
                    child: Text(_speciesEmoji(), style: const TextStyle(fontSize: 13)),
                  ),
                ],
              ],
            ),
          ],
        ],
      );
    }
    // GREEN / DIRECT：纯文本行 + 照片状态后缀
    final base = item.symptomPreview ?? l10n.vetRequestNoDetail;
    final photoSuffix = item.imageCount > 0
        ? ' · ${l10n.vetQueuePhotosAttached(item.imageCount)}'
        : ' · ${l10n.vetQueueNoPhoto}';
    return Text('$base$photoSuffix',
        style: AppTypography.body.copyWith(color: AppColors.textSecondary, height: 1.5));
  }
}
