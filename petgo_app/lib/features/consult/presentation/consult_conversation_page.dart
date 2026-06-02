import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/consult_repository.dart';
import 'consult_rating_dialog.dart';
import 'im_chat_placeholder.dart';

/// 用户侧进行中会话界面（Story 5.5 + 5.6）。
///
/// 常驻免责提示（NFR-9）+ IM 对话区（L2 占位）。轮询会话状态：兽医结束 → PENDING_CLOSE →
/// 展示「请评分」+ 评分弹窗（30min 窗口内仍可继续发消息，输入框不锁）；评分提交 → CLOSED 只读。
class ConsultConversationPage extends ConsumerStatefulWidget {
  const ConsultConversationPage({super.key, required this.sessionId});

  final int sessionId;

  @override
  ConsumerState<ConsultConversationPage> createState() => _ConsultConversationPageState();
}

class _ConsultConversationPageState extends ConsumerState<ConsultConversationPage> {
  static const Duration _pollInterval = Duration(seconds: 5);

  Timer? _poll;
  String _status = 'IN_PROGRESS';
  String? _closedReason;
  bool _rated = false;

  @override
  void initState() {
    super.initState();
    _poll = Timer.periodic(_pollInterval, (_) => _tick());
    _tick();
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _tick() async {
    try {
      final s = await ref.read(consultRepositoryProvider).get(widget.sessionId);
      if (!mounted) return;
      setState(() {
        _status = s.status;
        _closedReason = s.closedReason;
      });
      if (s.status == 'CLOSED' || s.status == 'INTERRUPTED') _poll?.cancel();
    } catch (_) {
      // 轮询失败静默重试。
    }
  }

  Future<void> _openRating() async {
    final l10n = AppLocalizations.of(context);
    final result = await ConsultRatingDialog.show(context);
    if (result == null || !mounted) return;
    try {
      await ref.read(consultRepositoryProvider).rate(widget.sessionId, result.stars, result.comment);
      if (!mounted) return;
      setState(() {
        _rated = true;
        _status = 'CLOSED';
      });
      _poll?.cancel();
      ScaffoldMessenger.of(context)
        ..clearSnackBars()
        ..showSnackBar(SnackBar(content: Text(l10n.consultRateThanks)));
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
        ..clearSnackBars()
        ..showSnackBar(SnackBar(content: Text(l10n.consultRateFailed)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final pendingClose = _status == 'PENDING_CLOSE' && !_rated;
    final interrupted = _status == 'INTERRUPTED';
    final closed = _status == 'CLOSED';
    // 终态只读标签（Story 5.8 AC3）：已结束 / 未评分 / 已中断。
    final terminalLabel = interrupted
        ? l10n.terminalInterrupted
        : (closed && _closedReason == 'UNRATED' && !_rated)
            ? l10n.terminalUnrated
            : closed
                ? l10n.terminalClosed
                : null;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        title: Text(l10n.consultConversationTitle),
        actions: [
          if (terminalLabel != null)
            Padding(
              padding: const EdgeInsets.only(right: AppSpacing.md),
              child: Center(
                child: Text(terminalLabel,
                    key: const ValueKey('consultTerminalLabel'), style: AppTypography.caption),
              ),
            ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            // 免责提示常驻（NFR-9 / UX-DR14：克制、双语、显著位）。
            Container(
              key: const ValueKey('consultDisclaimerBanner'),
              width: double.infinity,
              padding: const EdgeInsets.all(AppSpacing.md),
              color: AppColors.triageYellowSurface,
              child: Text(l10n.consultDisclaimer, style: AppTypography.caption),
            ),
            // 封禁中断（Story 5.7）：转只读终态 + 软引导重新发起（复用 5.3 发起入口）。
            if (interrupted)
              Expanded(
                child: Center(
                  child: Padding(
                    padding: const EdgeInsets.all(AppSpacing.xl),
                    child: Column(
                      key: const ValueKey('consultInterruptedState'),
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.cloud_off_outlined, size: 48, color: AppColors.textTertiary),
                        const SizedBox(height: AppSpacing.md),
                        Text(l10n.consultInterrupted,
                            style: AppTypography.body, textAlign: TextAlign.center),
                        const SizedBox(height: AppSpacing.section),
                        FilledButton(
                          key: const ValueKey('consultReconsult'),
                          onPressed: () => context.go('/consult'),
                          child: Text(l10n.consultReconsult),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            // 兽医结束 → 请评分提示；CLOSED 未评分进入也可补评（30min 内仍可继续发消息，故非阻断 banner）。
            if (pendingClose || (closed && _closedReason == 'UNRATED' && !_rated))
              Container(
                key: const ValueKey('consultRatePromptBanner'),
                width: double.infinity,
                padding: const EdgeInsets.all(AppSpacing.md),
                color: AppColors.surface,
                child: Row(
                  children: [
                    Expanded(child: Text(l10n.consultRatePrompt, style: AppTypography.caption)),
                    FilledButton(
                      key: const ValueKey('consultOpenRating'),
                      onPressed: _openRating,
                      child: Text(l10n.consultRateSubmit),
                    ),
                  ],
                ),
              ),
            // 终态（中断/关闭）转只读：不显示输入区。
            if (!interrupted && !closed)
              ImChatPlaceholder(imConversationId: 'session-${widget.sessionId}'),
            // 关闭终态占位（只读，无输入框）。
            if (closed)
              Expanded(
                child: Center(
                  child: Text(l10n.imChatPlaceholderHint,
                      style: AppTypography.disclaimer, textAlign: TextAlign.center),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
