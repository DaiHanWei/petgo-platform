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

/// 问诊 Tab 首页（Story 4.3 · F1）。AI 智能分诊 + 在线兽医**平级双入口卡**，均标注「免费」；
/// 兽医卡为占位（Epic 5 接入前点击仅提示）。AI 卡点击经强登录门控后进入上传页。
class TriagePage extends ConsumerWidget {
  const TriagePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.tabTriage)),
      body: ListView(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: <Widget>[
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
            // 兽医卡占位（Epic 5 接入）：点击仅提示，不进入未实现流。
            onTap: () => ScaffoldMessenger.of(context)
              ..clearSnackBars()
              ..showSnackBar(SnackBar(content: Text(l10n.triageVetComingSoon))),
          ),
        ],
      ),
    );
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
