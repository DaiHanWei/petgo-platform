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
          // ① 功能入口区（AI + 兽医平级）。
          _EntryCard(
            valueKey: 'triageEntryAI',
            icon: Icons.smart_toy_outlined,
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
          const SizedBox(height: AppSpacing.lg),
          _EntryCard(
            valueKey: 'triageEntryVet',
            icon: Icons.medical_services_outlined,
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

/// 历史条目（Story 5.8 F2）：AI（评级 + 摘要）/ 兽医（昵称 + 摘要 + 评分 + 已存档 + 终态）。
class _HistoryTile extends StatelessWidget {
  const _HistoryTile({required this.item});

  final ConsultHistoryItem item;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isAi = item.isAi;
    return Card(
      margin: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: ListTile(
        key: ValueKey(isAi ? 'historyAi_${item.triageId}' : 'historyVet_${item.sessionId}'),
        leading: Icon(isAi ? Icons.smart_toy_outlined : Icons.medical_services_outlined,
            color: AppColors.accentConsult),
        title: Text(isAi
            ? '${l10n.historyTypeAi} · ${item.dangerLevel ?? ''}'
            : '${l10n.historyTypeVet} · ${item.vetDisplayName ?? ''}'),
        subtitle: Text(
          isAi ? (item.symptomSummary ?? '') : _vetSubtitle(l10n),
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
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

  String _vetSubtitle(AppLocalizations l10n) {
    final parts = <String>[];
    if (item.terminalState == 'INTERRUPTED') {
      parts.add(l10n.terminalInterrupted);
    } else if (item.userStars != null) {
      parts.add('${item.userStars} ★');
    } else {
      parts.add(l10n.historyUnrated);
    }
    if (item.archived == true) parts.add(l10n.historyArchived);
    if (item.sessionSummary != null && item.sessionSummary!.isNotEmpty) {
      parts.add(item.sessionSummary!);
    }
    return parts.join(' · ');
  }
}

class _EntryCard extends StatelessWidget {
  const _EntryCard({
    required this.valueKey,
    required this.icon,
    required this.title,
    required this.description,
    required this.freeLabel,
    required this.onTap,
  });

  final String valueKey;
  final IconData icon;
  final String title;
  final String description;
  final String freeLabel;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(AppRounded.lg),
      child: InkWell(
        key: ValueKey(valueKey),
        borderRadius: BorderRadius.circular(AppRounded.lg),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Row(
            children: <Widget>[
              CircleAvatar(
                radius: 24,
                backgroundColor: AppColors.accentConsult.withValues(alpha: 0.15),
                child: Icon(icon, color: AppColors.accentConsult),
              ),
              const SizedBox(width: AppSpacing.lg),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Row(
                      children: <Widget>[
                        Flexible(child: Text(title, style: AppTypography.title)),
                        const SizedBox(width: AppSpacing.sm),
                        _FreeBadge(label: freeLabel),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.xs),
                    Text(description, style: AppTypography.caption),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, color: AppColors.textTertiary),
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
