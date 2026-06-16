import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/im/im_service.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../notify/data/push_permission_providers.dart';
import '../../notify/domain/push_suppression.dart';
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
  bool _firstConsultPushTried = false; // 首次问诊推送闸门本页只触发一次（gate 另有持久化自守）
  ActiveConsultSession? _activeNotifier;

  // Story 5.5 live 增量：进行中会话登录 IM 收发；离开/结束登出（控 MAU + 不留连接）。
  ImService? _imService;
  bool _imLoginStarted = false;
  String? _peerId; // 对端 IM 账号 v_<vetId>（用户侧）

  @override
  void initState() {
    super.initState();
    // 捕获 IM 封装（dispose 期登出，避免 ref 失效）。
    _imService = ref.read(imServiceProvider);
    // 标记当前激活会话（Story 6.2 F1）：前台同会话推送抑制 in-app Banner。
    _activeNotifier = ref.read(activeConsultSessionProvider.notifier);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _activeNotifier?.set(widget.sessionId);
    });
    _poll = Timer.periodic(_pollInterval, (_) => _tick());
    _tick();
  }

  @override
  void dispose() {
    _poll?.cancel();
    // 离开会话页 → 清激活标记（用 initState 捕获的 notifier，避免 dispose 期 ref 失效）。
    _activeNotifier?.set(null);
    // 离开即登出 IM（不留长连接 / 控 MAU）。
    if (_imLoginStarted) _imService?.logout();
    super.dispose();
  }

  Future<void> _tick() async {
    try {
      final s = await ref.read(consultRepositoryProvider).get(widget.sessionId);
      if (!mounted) return;
      setState(() {
        _status = s.status;
        _closedReason = s.closedReason;
        if (s.vetId != null) _peerId = 'v_${s.vetId}';
      });
      // 进行中（已接单）才登录 IM：取 UserSig 经后端 MAU 闸门（用户须有活跃会话）。
      if (!_imLoginStarted && s.status == 'IN_PROGRESS' && s.vetId != null) {
        _imLoginStarted = true;
        _imService?.loginIfNeeded().catchError((_) {
          // 取 sig 403/网络失败 → 不崩；保留占位演示，下次轮询可重试。
          _imLoginStarted = false;
        });
      }
      if (s.status == 'CLOSED' || s.status == 'INTERRUPTED') _poll?.cancel();
      if (s.status == 'CLOSED') _maybeTriggerFirstConsultPush();
    } catch (_) {
      // 轮询失败静默重试。
    }
  }

  /// 首次问诊完成（会话 CLOSED）→ 触发推送权限闸门（Story 6.4 双时机之一）。
  /// 接 ① 的 P-09 前置 sheet；gate 凭 `pushPermissionAsked` 持久化自守仅一次，本页再加一道防重入。
  /// 失败静默——绝不阻断问诊完成体验。
  Future<void> _maybeTriggerFirstConsultPush() async {
    if (_firstConsultPushTried) return;
    _firstConsultPushTried = true;
    try {
      final gate = await ref.read(pushPermissionGateProvider.future);
      await gate.maybeRequestAfterFirstConsult(firstConsultDone: true);
    } catch (_) {
      // 推送闸门异常不影响问诊流程。
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
      _maybeTriggerFirstConsultPush();
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
            // 免责提示常驻（NFR-9 / UX-DR14：克制、双语、显著位）。TailTopia Prototype 金色条。
            Container(
              key: const ValueKey('consultDisclaimerBanner'),
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              color: AppColors.goldTint,
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('ℹ️', style: TextStyle(fontSize: 14)),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      l10n.consultDisclaimer,
                      style: const TextStyle(
                          fontSize: 11.5, height: 1.4, color: Color(0xFF8A6A12)),
                    ),
                  ),
                ],
              ),
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
              ImChatPlaceholder(
                imConversationId: 'session-${widget.sessionId}',
                peerId: _peerId,
              ),
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
