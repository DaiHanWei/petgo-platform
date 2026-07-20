import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../data/vet_repository.dart';
import '../domain/consult_ai_context.dart';
import '../domain/vet_queue.dart';
import 'vet_ai_context_card.dart';
import 'vet_empty_state.dart';
import 'widgets/vet_top_bar.dart';

/// 兽医工作台首页（dashboard，原型 V-01）：深色顶栏（问候 + 在线开关）+ 今日 3 统计卡
/// （队列/完成/评分）+ 计费流接单队列。
///
/// Story 3.6：数据源从 V1.0 免费直连流（`consult_sessions` waitingList）**改为计费流** `vetQueue()`
/// （`consult_requests`）。承接 3-2 广播 → 3-3 接单 CAS → 3-4 限时支付。三态：
/// ① `awaitingPay != null` → 顶部「等待用户支付」倒计时中间态（FR-53A），兽医忙不显池；
/// ② 否则 → 可接单 QUEUEING 池（宠物身份 + 等待时长 + 接单 CTA）；
/// ③ `awaitingPay` 由非空→空（轮询侦测）→ 判成交（跳会话在 Active Tab）/ 未成交（取消/超时/未支付）→ 3s Toast（FR-53B）。
class VetInboxPage extends ConsumerStatefulWidget {
  const VetInboxPage({super.key});

  @override
  ConsumerState<VetInboxPage> createState() => _VetInboxPageState();
}

class _VetInboxPageState extends ConsumerState<VetInboxPage> with WidgetsBindingObserver {
  /// 队列轮询间隔（比免费流 8s 更密：FR-53A 倒计时 + 尽快侦测支付/取消跃迁）。
  static const Duration _pollInterval = Duration(seconds: 4);

  /// 支付窗（服务端权威 300s=5min，仅用于倒计时显示 clamp）。
  static const int _payWindowSeconds = 300;

  VetQueue? _queue; // null = 首次加载中
  Timer? _poll; // 拉队列
  Timer? _display; // 1s 心跳刷新倒计时显示
  String _displayName = '';
  int? _doneCount; // 完成数（history 列表长度）；null=加载中/失败 → 占位「—」
  String? _prevAwaitingToken; // 上一轮待支付项 token（侦测「接单已结束」跃迁，FR-53B）

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _reloadQueue();
    _loadHeaderStats();
    _startTimers();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _poll?.cancel();
    _display?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 退后台停轮询省流量/电；回前台立即拉一次再恢复。
    if (state == AppLifecycleState.resumed) {
      _reloadQueue();
      _startTimers();
    } else {
      _poll?.cancel();
      _display?.cancel();
    }
  }

  void _startTimers() {
    _poll?.cancel();
    _display?.cancel();
    _poll = Timer.periodic(_pollInterval, (_) => _reloadQueue());
    // 倒计时仅在待支付态需要 1s 刷新显示（纯客户端显示，跃迁靠轮询服务端）。
    _display = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted && _queue?.awaitingPay != null) setState(() {});
    });
  }

  /// 拉计费队列（轮询/手动共用）：静默刷新保留旧数据避免闪烁；侦测待支付跃迁触发 Toast（FR-53B）。
  Future<void> _reloadQueue() async {
    VetQueue queue;
    try {
      queue = await ref.read(vetRepositoryProvider).vetQueue();
    } catch (_) {
      if (mounted && _queue == null) setState(() => _queue = const VetQueue());
      return;
    }
    await _detectAwaitingTransition(queue);
    if (mounted) setState(() => _queue = queue);
  }

  /// 侦测「本兽医待支付项消失」→ 判成交/未成交（FR-53B，决策 D-4 前端轮询推断）。
  Future<void> _detectAwaitingTransition(VetQueue next) async {
    final prev = _prevAwaitingToken;
    final now = next.awaitingPay?.requestToken;
    if (prev != null && now == null) {
      // 上一轮在等待支付、本轮消失：支付成功转单删 / 用户取消删 / 支付窗超时回退或彻底失败。
      bool paid = false;
      try {
        // 接单中恒仅 1 单（goBusy 互斥）：出现进行中会话即已支付；否则未成交。
        paid = (await ref.read(vetRepositoryProvider).activeSessions()).isNotEmpty;
      } catch (_) {
        paid = false; // 拉取失败按未成交提示（不误报成功）
      }
      if (mounted) {
        final l10n = AppLocalizations.of(context);
        showAppToast(context, paid ? l10n.vetQueuePaidStarted : l10n.vetQueueOrderFellThrough,
            duration: const Duration(seconds: 3));
      }
    }
    _prevAwaitingToken = now;
  }

  /// 显式刷新（右上角）：重拉队列。
  void _refresh() => _reloadQueue();

  /// 接单：CAS 接计费流请求 → 成功刷新（进等待支付态）；409（被抢/占用）→ 3s Toast + 刷新。
  Future<void> _accept(VetQueueItem item) async {
    final l10n = AppLocalizations.of(context);
    try {
      await ref.read(vetRepositoryProvider).acceptConsultRequest(item.requestToken);
      // 接单成功 → 立即刷新拉 awaitingPay（进等待支付态）。跃迁基线由 _reloadQueue 的侦测器据服务端
      // 返回的 awaitingPay 建立——不在此乐观预置 _prevAwaitingToken（否则 reload 前误判「未成交」误报 Toast）。
      await _reloadQueue();
    } catch (_) {
      if (!mounted) return;
      showAppToast(context, l10n.vetQueueAcceptFailed, duration: const Duration(seconds: 3));
      _reloadQueue();
    }
  }

  /// 看病例（D1）：接单前展开完整症状 + 现签私密图。请求已消失（超时删/被抢）→ Toast + 刷新队列。
  Future<void> _viewCase(VetQueueItem item) async {
    final l10n = AppLocalizations.of(context);
    ConsultAiContext ctx;
    try {
      ctx = await ref.read(vetRepositoryProvider).consultationCase(item.requestToken);
    } catch (_) {
      if (!mounted) return;
      showAppToast(context, l10n.vetQueueCaseGone, duration: const Duration(seconds: 3));
      _reloadQueue();
      return;
    }
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.base,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (c) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.md, 0, AppSpacing.md, AppSpacing.md),
          child: SingleChildScrollView(
            key: const ValueKey('vetQueueCaseSheet'),
            child: VetAiContextCard(context_: ctx),
          ),
        ),
      ),
    );
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

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final queue = _queue;
    final loading = queue == null;
    final awaiting = queue?.awaitingPay;
    final available = queue?.available ?? const <VetQueueItem>[];
    return Scaffold(
      backgroundColor: AppColors.base,
      body: Column(
        children: [
          VetTopBar(greetingName: _displayName, showOnlineToggle: true),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(AppSpacing.md),
              children: [
                _StatRow(queue: loading ? null : available.length, done: _doneCount),
                const SizedBox(height: AppSpacing.lg),
                // FR-53A：本人待支付倒计时中间态（有则置顶，忙时池为空）。
                if (awaiting != null) ...[
                  _AwaitingPayCard(item: awaiting, payWindowSeconds: _payWindowSeconds),
                  const SizedBox(height: AppSpacing.lg),
                ],
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        l10n.vetDashboardQueueSection(loading ? 0 : available.length),
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
                else if (available.isEmpty)
                  // 忙时（等待支付/会话中）池为空——引导语随待支付态区分（等待支付时不显「暂无请求」）。
                  awaiting != null
                      ? const SizedBox.shrink()
                      : VetEmptyState(icon: Icons.inbox_outlined, message: l10n.vetInboxEmpty)
                else
                  ...available.map((it) => Padding(
                        padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                        child: _QueueCard(
                            item: it, onAccept: () => _accept(it), onViewCase: () => _viewCase(it)),
                      )),
              ],
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
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(14)),
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

/// FR-53A 待支付中间态卡：本兽医接单后「等待用户支付 剩余 MM:SS」。服务端权威倒计时（纯显示，
/// 跃迁靠轮询）；`pausedAt != null`（用户跳充值，A-4）→ 暂停显示计时，改显「用户正在充值…」。
class _AwaitingPayCard extends StatelessWidget {
  const _AwaitingPayCard({required this.item, required this.payWindowSeconds});

  final VetAwaitingPay item;
  final int payWindowSeconds;

  String get _mmss {
    final deadline = item.payDeadlineAt;
    if (deadline == null) return '--:--';
    final remaining = deadline.difference(DateTime.now().toUtc()).inSeconds.clamp(0, payWindowSeconds);
    final m = (remaining ~/ 60).toString().padLeft(2, '0');
    final s = (remaining % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final petName = (item.petName?.isNotEmpty ?? false) ? item.petName! : null;
    return Container(
      key: const ValueKey('vetAwaitingPayCard'),
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.vetSurface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.vetPrimary.withValues(alpha: 0.35)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.hourglass_top, size: 18, color: AppColors.vetPrimary),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  l10n.vetQueueAwaitingPayTitle,
                  style: AppTypography.title.copyWith(color: AppColors.vetPrimary),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            petName != null
                ? l10n.vetQueueAwaitingPaySubtitleNamed(petName)
                : l10n.vetQueueAwaitingPaySubtitle,
            style: AppTypography.caption.copyWith(color: AppColors.textSecondary),
          ),
          const SizedBox(height: 12),
          if (item.isPaused)
            Row(
              children: [
                const Icon(Icons.pause_circle_outline, size: 20, color: AppColors.textSecondary),
                const SizedBox(width: 8),
                Text(l10n.vetQueuePausedHint,
                    style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
              ],
            )
          else
            Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Text(l10n.vetQueueAwaitingPayRemaining,
                    style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
                const SizedBox(width: 8),
                Text(
                  _mmss,
                  key: const ValueKey('vetAwaitingPayCountdown'),
                  style: AppTypography.display.copyWith(color: AppColors.vetPrimary),
                ),
              ],
            ),
        ],
      ),
    );
  }
}

/// 计费流接单队列卡（Story 3.6）：宠物身份块（头像 emoji + 名 + meta）+ 等待时长 + 接单 CTA。
/// **无 AI 危险等级/症状/照片**（`consult_requests` 不存病例，区别于免费流 `_InboxCard` 富卡）。
class _QueueCard extends StatelessWidget {
  const _QueueCard({required this.item, required this.onAccept, required this.onViewCase});

  final VetQueueItem item;
  final VoidCallback onAccept;

  /// 展开完整病例（D1）：拉现签图 + 全文症状。
  final VoidCallback onViewCase;

  /// 等级色条（与 `vet_request_detail_page` 同源）。DIRECT 自填病例无 AI 评级 → 兽医主题色。
  /// **RED 不会出现**（后端 + 库 CHECK 双重兜底，红色态零兽医引流）。
  Color _levelColor() {
    switch (item.aiDangerLevel) {
      case 'YELLOW':
        return AppColors.triageYellow;
      case 'GREEN':
        return AppColors.triageGreen;
      default:
        return AppColors.vetPrimary;
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

  /// meta 行：「种类 · 年龄 · @主人」，缺项跳过。
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

  String _waitingShort(AppLocalizations l10n) {
    final s = item.waitingSeconds;
    return s < 60 ? l10n.vetQueueWaitJustNow : l10n.vetQueueWaitMinutesAgo(s ~/ 60);
  }

  /// 状态徽章（ref33/34）：YELLOW→「Perlu Konsul」琥珀，GREEN→「Normal」绿；DIRECT 无 AI 级别不显。
  Widget? _levelBadge(AppLocalizations l10n) {
    final (String label, Color bg, Color fg) = switch (item.aiDangerLevel) {
      'YELLOW' => (l10n.vetQueueBadgeConsult, AppColors.goldTint, AppColors.tipsBadgeText),
      'GREEN' => (
          l10n.vetQueueBadgeNormal,
          AppColors.triageGreen.withValues(alpha: 0.14),
          AppColors.onlineDeepGreen,
        ),
      _ => ('', Colors.transparent, Colors.transparent),
    };
    if (label.isEmpty) return null;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
      child: Text(label,
          style: AppTypography.micro.copyWith(color: fg, fontWeight: FontWeight.w700)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final meta = _metaLine(l10n);
    final badge = _levelBadge(l10n);
    return Container(
      key: ValueKey('vetQueueCard_${item.requestToken}'),
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
          Container(height: 4, color: _levelColor()),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 40,
                      height: 40,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: AppColors.vetPrimary.withValues(alpha: 0.14),
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
                          if (meta.isNotEmpty)
                            Text(meta,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        if (badge != null) ...[badge, const SizedBox(height: 4)],
                        Text(_waitingShort(l10n),
                            style: AppTypography.micro.copyWith(color: AppColors.textTertiary)),
                      ],
                    ),
                  ],
                ),
                // 病例摘要（D1）：兽医据此判断是否接单。无病例则整块省略。
                if (item.hasCase) ...[
                  const SizedBox(height: 10),
                  if (item.symptomPreview != null && item.symptomPreview!.isNotEmpty)
                    Text(
                      item.symptomPreview!,
                      key: ValueKey('vetQueueSymptom_${item.requestToken}'),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: AppTypography.body.copyWith(color: AppColors.textSecondary),
                    ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      if (item.imageCount > 0) ...[
                        Icon(Icons.photo_outlined, size: 14, color: AppColors.textTertiary),
                        const SizedBox(width: 4),
                        Text('${item.imageCount}',
                            style: AppTypography.micro.copyWith(color: AppColors.textTertiary)),
                        const SizedBox(width: 12),
                      ],
                      TextButton(
                        key: ValueKey('vetQueueViewCase_${item.requestToken}'),
                        onPressed: onViewCase,
                        style: TextButton.styleFrom(
                          padding: EdgeInsets.zero,
                          minimumSize: const Size(0, 0),
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          foregroundColor: AppColors.vetPrimary,
                        ),
                        child: Text(l10n.vetQueueViewCase),
                      ),
                    ],
                  ),
                ],
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    key: ValueKey('vetAccept_${item.requestToken}'),
                    onPressed: onAccept,
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.vetPrimary,
                      foregroundColor: AppColors.onAccent,
                      padding: const EdgeInsets.symmetric(vertical: 11),
                    ),
                    child: Text(l10n.vetQueueAccept),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
