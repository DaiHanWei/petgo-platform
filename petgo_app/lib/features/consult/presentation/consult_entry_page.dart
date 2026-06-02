import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/consult_repository.dart';
import '../domain/consult_session.dart';

/// 兽医咨询入口（Story 5.3 F1）。在线/离线两态 + 已有进行中跳转 + 离线软引导（不强制）。
///
/// 概率性在线展示「工作日 8:00–23:00 通常有兽医在线」（静态 l10n，**不显示人数**）；
/// 离线态「当前暂无兽医在线」+ 恢复时段 + 「先用 AI 分诊？」可点跳 FR-4A（不强制）。
class ConsultEntryPage extends ConsumerStatefulWidget {
  const ConsultEntryPage({super.key});

  @override
  ConsumerState<ConsultEntryPage> createState() => _ConsultEntryPageState();
}

class _ConsultEntryPageState extends ConsumerState<ConsultEntryPage> {
  bool _starting = false;
  ConsultSession? _active;
  bool _activeChecked = false;

  @override
  void initState() {
    super.initState();
    _checkActive();
  }

  Future<void> _checkActive() async {
    try {
      final a = await ref.read(consultRepositoryProvider).active();
      if (mounted) {
        setState(() {
          _active = a;
          _activeChecked = true;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _activeChecked = true);
    }
  }

  Future<void> _start() async {
    if (_starting) return;
    setState(() => _starting = true);
    final l10n = AppLocalizations.of(context);
    try {
      final session = await ref.read(consultRepositoryProvider).create();
      if (!mounted) return;
      // 进行中 → 进会话（5.5 暂跳等待页占位）；WAITING/已有 → 等待页。
      context.go('/consult/waiting/${session.id}');
    } on DioException {
      _banner(l10n.consultStartFailed);
    } finally {
      if (mounted) setState(() => _starting = false);
    }
  }

  void _banner(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final availability = ref.watch(consultAvailabilityProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.consultEntryTitle)),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: availability.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (_, _) => _offline(l10n),
            data: (a) {
              // 已有进行中咨询 → 「查看进行中 →」（优先于发起）。
              if (_active != null) {
                return _ongoing(l10n);
              }
              if (!_activeChecked) {
                return const Center(child: CircularProgressIndicator());
              }
              return a.vetOnline ? _online(l10n) : _offline(l10n);
            },
          ),
        ),
      ),
    );
  }

  Widget _ongoing(AppLocalizations l10n) {
    return Center(
      child: FilledButton(
        key: const ValueKey('consultViewActive'),
        onPressed: () => context.go('/consult/waiting/${_active!.id}'),
        child: Text(l10n.consultViewActive),
      ),
    );
  }

  Widget _online(AppLocalizations l10n) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Text(l10n.consultProbabilisticOnline, style: AppTypography.body, textAlign: TextAlign.center),
        const SizedBox(height: AppSpacing.section),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            key: const ValueKey('consultStartButton'),
            onPressed: _starting ? null : _start,
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.accentConsult,
              foregroundColor: AppColors.onAccent,
              padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
            ),
            child: Text(l10n.consultStart, style: AppTypography.button),
          ),
        ),
      ],
    );
  }

  Widget _offline(AppLocalizations l10n) {
    return Column(
      key: const ValueKey('consultOfflineState'),
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        const Icon(Icons.schedule, size: 48, color: AppColors.textTertiary),
        const SizedBox(height: AppSpacing.md),
        Text(l10n.consultNoVetOnline, style: AppTypography.title, textAlign: TextAlign.center),
        const SizedBox(height: AppSpacing.sm),
        Text(l10n.consultOfflineWindow, style: AppTypography.caption, textAlign: TextAlign.center),
        const SizedBox(height: AppSpacing.section),
        // 软引导：可点跳 AI 分诊，不强制（用户可留在本页）。
        OutlinedButton(
          key: const ValueKey('consultOfflineUseAi'),
          onPressed: () => context.push('/triage/upload'),
          child: Text(l10n.consultOfflineUseAi),
        ),
      ],
    );
  }
}
