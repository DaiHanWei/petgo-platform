import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/consult_repository.dart';

/// 等待界面（Story 5.3 F2/F3/F4）：「正在为你匹配兽医…」+ 轮询 + 1min 超时弹层 + 取消二次确认。
///
/// 超时**不迁移状态**（仍 WAITING）：弹「继续等待 / 先用 AI 分诊」。
/// 「先用 AI」→ 跳 FR-4A，原 WAITING 请求保留（不取消），兽医接单后推送通知。
class ConsultWaitingPage extends ConsumerStatefulWidget {
  const ConsultWaitingPage({super.key, required this.sessionId});

  final int sessionId;

  @override
  ConsumerState<ConsultWaitingPage> createState() => _ConsultWaitingPageState();
}

class _ConsultWaitingPageState extends ConsumerState<ConsultWaitingPage> {
  static const Duration _pollInterval = Duration(seconds: 3);

  Timer? _poll;
  bool _timeoutShown = false;
  bool _navigating = false;

  @override
  void initState() {
    super.initState();
    _startPolling();
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  void _startPolling() {
    _poll?.cancel();
    _poll = Timer.periodic(_pollInterval, (_) => _tick());
    _tick();
  }

  Future<void> _tick() async {
    try {
      final s = await ref.read(consultRepositoryProvider).get(widget.sessionId);
      if (!mounted) return;
      if (s.isInProgress) {
        // 接单 → 进会话界面（Story 5.5）。
        _poll?.cancel();
        if (mounted) context.go('/consult/conversation/${widget.sessionId}');
        return;
      }
      if (s.timedOut && !_timeoutShown) {
        _timeoutShown = true;
        _poll?.cancel();
        _showTimeoutSheet();
      }
    } catch (_) {
      // 轮询失败静默重试（下个 tick）。
    }
  }

  Future<void> _showTimeoutSheet() async {
    final l10n = AppLocalizations.of(context);
    await showModalBottomSheet<void>(
      context: context,
      isDismissible: false,
      enableDrag: false,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(l10n.consultTimeoutTitle, style: AppTypography.headline),
              const SizedBox(height: AppSpacing.sm),
              Text(l10n.consultTimeoutBody, style: AppTypography.body),
              const SizedBox(height: AppSpacing.section),
              FilledButton(
                key: const ValueKey('consultContinueWaiting'),
                onPressed: () {
                  Navigator.of(ctx).pop();
                  _continueWaiting();
                },
                child: Text(l10n.consultContinueWaiting),
              ),
              const SizedBox(height: AppSpacing.sm),
              OutlinedButton(
                key: const ValueKey('consultUseAi'),
                onPressed: () {
                  Navigator.of(ctx).pop();
                  _useAi();
                },
                child: Text(l10n.consultUseAi),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _continueWaiting() async {
    try {
      await ref.read(consultRepositoryProvider).continueWaiting(widget.sessionId);
    } catch (_) {
      // 失败也恢复轮询，下次超时再弹。
    }
    if (!mounted) return;
    _timeoutShown = false;
    _startPolling();
  }

  void _useAi() {
    // 原 WAITING 请求保留（不取消）；跳分诊上传页，提示兽医接单后会通知。
    final l10n = AppLocalizations.of(context);
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(l10n.consultUseAiKeptHint)));
    context.push('/triage/upload');
    // 留在等待页（返回时仍可见）；继续保留请求不轮询，避免重复弹。
  }

  Future<void> _confirmCancel() async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.consultCancelConfirmTitle),
        actions: [
          TextButton(
            key: const ValueKey('consultCancelConfirmNo'),
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(l10n.consultCancelConfirmNo),
          ),
          FilledButton(
            key: const ValueKey('consultCancelConfirmYes'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(l10n.consultCancelConfirmYes),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    _poll?.cancel();
    try {
      await ref.read(consultRepositoryProvider).cancel(widget.sessionId);
    } catch (_) {
      // 取消失败也回主页（用户意图明确）；后端 TTL/状态以服务端为准。
    }
    if (!mounted) return;
    _navigating = true;
    context.go('/triage');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.xl),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const CircularProgressIndicator(),
                const SizedBox(height: AppSpacing.section),
                Text(
                  l10n.consultMatching,
                  key: const ValueKey('consultMatching'),
                  style: AppTypography.title,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: AppSpacing.section),
                TextButton(
                  key: const ValueKey('consultCancel'),
                  onPressed: _navigating ? null : _confirmCancel,
                  child: Text(l10n.consultCancel),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
