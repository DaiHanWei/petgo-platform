import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_guard.dart';
import '../../auth/domain/auth_state.dart';
import '../../consult/data/consult_repository.dart';
import '../../consult/domain/consult_history_item.dart';
import '../../consult/domain/consult_session.dart';
import '../../consult/presentation/consult_rating_dialog.dart';

/// 问诊 Tab 首页（Story 4.3 入口 + Story 5.8 三段收口）。
///
/// 从上至下：① 功能入口区（AI 问诊 + 兽医咨询**平级**）② 进行中会话卡（若有）③ 我的问诊历史列表。
/// 进 Tab 时查 5.6 补弹评分（pending-rating）→ 有则弹一次。
class TriagePage extends ConsumerStatefulWidget {
  const TriagePage({super.key});

  @override
  ConsumerState<TriagePage> createState() => _TriagePageState();
}

class _TriagePageState extends ConsumerState<TriagePage> {
  Future<ConsultSession?>? _active;
  Future<ConsultHistoryPage>? _history;
  bool _promptChecked = false;

  @override
  void initState() {
    super.initState();
    // 仅登录用户加载历史/进行中/补弹（游客点入口走登录门控）。
    if (ref.read(authControllerProvider).isLoggedIn) {
      _load();
      WidgetsBinding.instance.addPostFrameCallback((_) => _checkPendingRating());
    }
  }

  void _load() {
    final repo = ref.read(consultRepositoryProvider);
    _active = repo.active();
    _history = repo.history();
  }

  Future<void> _checkPendingRating() async {
    if (_promptChecked) return;
    _promptChecked = true;
    try {
      final pending = await ref.read(consultRepositoryProvider).pendingRating();
      if (pending == null || !mounted) return;
      final result = await ConsultRatingDialog.show(context);
      if (result == null) {
        // 跳过 → 标记 PROMPTED 不再弹。
        await ref.read(consultRepositoryProvider).markRatingPrompted(pending.id);
      } else {
        await ref.read(consultRepositoryProvider).rate(pending.id, result.stars, result.comment);
        if (mounted) setState(_load);
      }
    } catch (_) {
      // 补弹失败静默（下次进页再查）。
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.tabTriage)),
      body: ListView(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: <Widget>[
          // ① 功能入口区（AI + 兽医平级，左右并排大卡，对齐设计稿 S11）。
          IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                Expanded(
                  child: _EntryCard(
                    valueKey: 'triageEntryAI',
                    emoji: '🤖',
                    bgColor: AppColors.consultEntryAi,
                    title: l10n.triageEntryAiTitle,
                    description: l10n.triageEntryAiDesc,
                    freeLabel: l10n.triageFreeBadge,
                    onTap: () => requireLogin(
                      ref,
                      context,
                      pendingAction: const RouteIntent(location: '/triage/upload'),
                      onAllowed: () => context.push('/triage/upload'),
                    ),
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: _EntryCard(
                    valueKey: 'triageEntryVet',
                    emoji: '🩺',
                    bgColor: AppColors.consultEntryVet,
                    title: l10n.triageEntryVetTitle,
                    description: l10n.triageEntryVetDesc,
                    freeLabel: l10n.triageFreeBadge,
                    // 兽医咨询入口（Story 5.3 发起，含在线/离线态）。
                    onTap: () => requireLogin(
                      ref,
                      context,
                      pendingAction: const RouteIntent(location: '/consult'),
                      onAllowed: () => context.push('/consult'),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          // 营业时间提示（对齐设计稿 S11，文案已有 l10n）。
          Center(
            child: Text(
              l10n.consultProbabilisticOnline,
              style: AppTypography.caption.copyWith(color: AppColors.textTertiary),
              textAlign: TextAlign.center,
            ),
          ),
          if (loggedIn) ...<Widget>[
            // ② 进行中会话卡（若有）。
            FutureBuilder<ConsultSession?>(
              future: _active,
              builder: (context, snapshot) {
                final s = snapshot.data;
                if (s == null) return const SizedBox.shrink();
                final target =
                    s.isWaiting ? '/consult/waiting/${s.id}' : '/consult/conversation/${s.id}';
                return Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.lg),
                  child: Material(
                    color: AppColors.accentConsult.withValues(alpha: 0.10),
                    borderRadius: BorderRadius.circular(AppRounded.lg),
                    child: InkWell(
                      key: const ValueKey('consultActiveCard'),
                      borderRadius: BorderRadius.circular(AppRounded.lg),
                      onTap: () => context.push(target),
                      child: Padding(
                        padding: const EdgeInsets.all(AppSpacing.lg),
                        child: Row(
                          children: [
                            const Icon(Icons.forum_outlined, color: AppColors.accentConsult),
                            const SizedBox(width: AppSpacing.md),
                            Expanded(child: Text(l10n.consultActiveCard, style: AppTypography.body)),
                            const Icon(Icons.chevron_right, color: AppColors.textTertiary),
                          ],
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
            // ③ 我的问诊历史列表。
            const SizedBox(height: AppSpacing.section),
            Text(l10n.consultHistoryTitle, style: AppTypography.title),
            const SizedBox(height: AppSpacing.md),
            FutureBuilder<ConsultHistoryPage>(
              future: _history,
              builder: (context, snapshot) {
                if (snapshot.connectionState == ConnectionState.waiting) {
                  // 静态占位（非动画）：避免阻塞 pumpAndSettle；加载完成切列表/空态。
                  return const SizedBox(height: AppSpacing.lg);
                }
                final items = snapshot.data?.items ?? const <ConsultHistoryItem>[];
                if (items.isEmpty) {
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
                    child: Text(l10n.consultHistoryEmpty,
                        key: const ValueKey('consultHistoryEmpty'), style: AppTypography.caption),
                  );
                }
                return Column(children: [for (final it in items) _HistoryTile(item: it)]);
              },
            ),
          ],
        ],
      ),
    );
  }
}

/// 历史条目（Story 5.8 F2，对齐设计稿 S11）：头像 + 严重度胶囊/星评 + 相对时间 + 摘要。
class _HistoryTile extends StatelessWidget {
  const _HistoryTile({required this.item});

  final ConsultHistoryItem item;

  /// 发起相对时间（双语 l10n，复用 content_detail 的语义键）。
  static String _relativeTime(AppLocalizations l10n, DateTime t) {
    final d = DateTime.now().difference(t);
    if (d.inMinutes < 1) return l10n.timeJustNow;
    if (d.inHours < 1) return l10n.timeMinutesAgo(d.inMinutes);
    if (d.inDays < 1) return l10n.timeHoursAgo(d.inHours);
    return l10n.timeDaysAgo(d.inDays);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isAi = item.isAi;
    final time = item.date == null ? '' : _relativeTime(l10n, item.date!);
    final summary = isAi ? (item.symptomSummary ?? '') : _vetSubtitle(l10n);

    final List<Widget> meta = <Widget>[
      if (isAi)
        _SeverityChip(level: item.dangerLevel)
      else
        Expanded(
          child: Text(item.vetDisplayName ?? l10n.historyTypeVet,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600),
              maxLines: 1,
              overflow: TextOverflow.ellipsis),
        ),
      if (!isAi && item.userStars != null) ...<Widget>[
        const SizedBox(width: AppSpacing.sm),
        _Stars(count: item.userStars!),
      ],
      if (isAi) const Spacer(),
      if (time.isNotEmpty) ...<Widget>[
        const SizedBox(width: AppSpacing.sm),
        Text(time, style: AppTypography.micro),
      ],
    ];

    return Card(
      margin: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: ListTile(
        key: ValueKey(isAi ? 'historyAi_${item.triageId}' : 'historyVet_${item.sessionId}'),
        leading: _HistoryAvatar(isAi: isAi, vetName: item.vetDisplayName),
        title: Row(children: meta),
        subtitle: summary.isEmpty
            ? null
            : Text(summary, maxLines: 2, overflow: TextOverflow.ellipsis),
        onTap: () {
          if (isAi) {
            // AI 进分诊结果只读（Epic 4 结果页深链，路由表 6.1 承接，本故事占位不跳）。
            return;
          }
          if (item.sessionId != null) {
            context.push('/consult/conversation/${item.sessionId}');
          }
        },
      ),
    );
  }

  /// 兽医副标题：终态/已存档/会话摘要（星评已挪到 title 行星形控件，不再拼字符串）。
  String _vetSubtitle(AppLocalizations l10n) {
    final parts = <String>[];
    // 互斥：中断态只显示「已中断」；否则未评分时显示「未评分」（避免两者并列）。
    if (item.terminalState == 'INTERRUPTED') {
      parts.add(l10n.terminalInterrupted);
    } else if (item.userStars == null) {
      parts.add(l10n.historyUnrated);
    }
    if (item.archived == true) parts.add(l10n.historyArchived);
    if (item.sessionSummary != null && item.sessionSummary!.isNotEmpty) {
      parts.add(item.sessionSummary!);
    }
    return parts.join(' · ');
  }
}

/// 历史项头像：AI → 🤖 浅蓝圆；兽医 → 昵称首字母浅蓝圆。
class _HistoryAvatar extends StatelessWidget {
  const _HistoryAvatar({required this.isAi, this.vetName});

  final bool isAi;
  final String? vetName;

  @override
  Widget build(BuildContext context) {
    final bg = AppColors.accentConsult.withValues(alpha: 0.15);
    if (isAi) {
      return CircleAvatar(
          radius: 18, backgroundColor: bg, child: const Text('🤖', style: TextStyle(fontSize: 18)));
    }
    final n = (vetName ?? '').trim();
    return CircleAvatar(
      radius: 18,
      backgroundColor: bg,
      child: n.isEmpty
          ? const Icon(Icons.medical_services_outlined, size: 18, color: AppColors.accentConsult)
          : Text(n.characters.first.toUpperCase(),
              style: const TextStyle(
                  fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.accentConsult)),
    );
  }
}

/// 分诊严重度胶囊（GREEN/YELLOW/RED → 三色 + 本地化标签，对齐设计稿）。
class _SeverityChip extends StatelessWidget {
  const _SeverityChip({required this.level});

  final String? level;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (Color color, String label) = switch (level) {
      'RED' => (AppColors.triageRed, l10n.triageLevelRed),
      'YELLOW' => (AppColors.triageYellow, l10n.triageLevelYellow),
      _ => (AppColors.triageGreen, l10n.triageLevelGreen),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: AppSpacing.xxs),
      decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(AppRounded.full)),
      child: Text(label, style: AppTypography.badge),
    );
  }
}

/// 兽医星评（1..5 实心星，其余空星）。
class _Stars extends StatelessWidget {
  const _Stars({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final c = count.clamp(0, 5); // 防越界（评分本应 1..5）。
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        for (int i = 1; i <= 5; i++)
          Icon(i <= c ? Icons.star_rounded : Icons.star_outline_rounded,
              size: 14, color: AppColors.triageYellow),
      ],
    );
  }
}

/// 问诊入口卡（竖向大卡，对齐设计稿 S11）：emoji + 标题 + 描述 + Gratis 胶囊，柔彩底。
class _EntryCard extends StatelessWidget {
  const _EntryCard({
    required this.valueKey,
    required this.emoji,
    required this.bgColor,
    required this.title,
    required this.description,
    required this.freeLabel,
    required this.onTap,
  });

  final String valueKey;
  final String emoji;
  final Color bgColor;
  final String title;
  final String description;
  final String freeLabel;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: bgColor,
      borderRadius: BorderRadius.circular(AppRounded.lg),
      child: InkWell(
        key: ValueKey(valueKey),
        borderRadius: BorderRadius.circular(AppRounded.lg),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(
              horizontal: AppSpacing.md, vertical: AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: <Widget>[
              Text(emoji, style: const TextStyle(fontSize: 36)),
              const SizedBox(height: AppSpacing.md),
              Text(title, style: AppTypography.title, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.xs),
              Text(description,
                  style: AppTypography.caption, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.sm),
              _FreeBadge(label: freeLabel),
            ],
          ),
        ),
      ),
    );
  }
}

class _FreeBadge extends StatelessWidget {
  const _FreeBadge({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: AppSpacing.xxs),
      decoration: BoxDecoration(
        color: AppColors.triageGreen,
        borderRadius: BorderRadius.circular(AppRounded.full),
      ),
      child: Text(label, style: AppTypography.badge),
    );
  }
}
