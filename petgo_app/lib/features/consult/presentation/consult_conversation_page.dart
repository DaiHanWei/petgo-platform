import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/im/im_service.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/case_image_viewer.dart';
import '../../notify/data/push_permission_providers.dart';
import '../../notify/domain/push_suppression.dart';
import '../data/consult_repository.dart';
import '../domain/consult_case.dart';
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
  ConsultCase? _case; // 用户自填病例（症状 + 私密图签名 URL）：摘要条展开用，异步拉

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
    _loadCase();
  }

  /// 拉用户自己提交的病例（症状 + 私密图签名 URL）。失败/无病例则不渲染摘要条。
  Future<void> _loadCase() async {
    final c = await ref.read(consultRepositoryProvider).caseContext(widget.sessionId);
    if (mounted) setState(() => _case = c);
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

  /// 离开会话页（返回上一屏；无栈可弹则回问诊入口）。
  void _leave() {
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/consult');
    }
  }

  /// 用户侧「Akhiri」：确认后离开会话（会话状态由服务端权威；本页不发起结束端点）。
  Future<void> _confirmLeave() async {
    final l10n = AppLocalizations.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.vetEndConfirmTitle),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: Text(l10n.vetEndConfirmNo)),
          FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: Text(l10n.vetEndConfirmYes)),
        ],
      ),
    );
    if (ok == true && mounted) _leave();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final pendingClose = _status == 'PENDING_CLOSE' && !_rated;
    final interrupted = _status == 'INTERRUPTED';
    final closed = _status == 'CLOSED';
    final active = _status == 'IN_PROGRESS';
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
      body: Column(
        children: [
          _topBar(l10n, terminalLabel: terminalLabel, showEnd: active),
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
                    style: const TextStyle(fontSize: 11.5, height: 1.4, color: Color(0xFF8A6A12)),
                  ),
                ),
              ],
            ),
          ),
          // 原始症状摘要条（原型紫浅底折叠条）。占位内容；仅活跃会话显示。
          if (active || pendingClose) _symptomBar(l10n),
          Expanded(
            child: SafeArea(
              top: false,
              child: Column(
                children: [
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
          ),
        ],
      ),
    );
  }

  /// 浅色顶栏（原型 chat.html）：返回钮 + 薄荷头像（在线点）+ 兽医名 + 「● Online · 诊所」/终态副行 + Akhiri。
  Widget _topBar(AppLocalizations l10n, {required String? terminalLabel, required bool showEnd}) {
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.surface,
        border: Border(bottom: BorderSide(color: AppColors.line2)),
      ),
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
          child: Row(
            children: [
              InkWell(
                key: const ValueKey('consultLeave'),
                onTap: _leave,
                borderRadius: BorderRadius.circular(10),
                child: Container(
                  width: 34,
                  height: 34,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: const Color(0xFFF3F3F3),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.arrow_back, size: 18, color: AppColors.ink2),
                ),
              ),
              const SizedBox(width: 11),
              // 兽医头像 + 在线点。占位内容。
              SizedBox(
                width: 38,
                height: 38,
                child: Stack(
                  children: [
                    Container(
                      width: 38,
                      height: 38,
                      alignment: Alignment.center,
                      decoration: const BoxDecoration(color: AppColors.vetPrimary, shape: BoxShape.circle),
                      child: const Text('D',
                          style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: Colors.white)),
                    ),
                    Positioned(
                      right: 0,
                      bottom: 0,
                      child: Container(
                        width: 11,
                        height: 11,
                        decoration: BoxDecoration(
                          color: AppColors.vetPrimary,
                          shape: BoxShape.circle,
                          border: Border.all(color: AppColors.surface, width: 2),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('drh. Dewi Santoso',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink)),
                    if (terminalLabel != null)
                      Text(terminalLabel,
                          key: const ValueKey('consultTerminalLabel'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 11, color: AppColors.muted))
                    else
                      Text('${l10n.consultPeerOnline} · Klinik Hewan Sehat',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.vetPrimary)),
                  ],
                ),
              ),
              if (showEnd) ...[
                const SizedBox(width: 8),
                OutlinedButton(
                  key: const ValueKey('consultEndSession'),
                  onPressed: _confirmLeave,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppColors.coral,
                    side: const BorderSide(color: AppColors.coral, width: 1.5),
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  ),
                  child: Text(l10n.vetEndConfirmYes,
                      style: const TextStyle(fontSize: 12, color: AppColors.coral)),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  /// 原始病例摘要折叠条（原型紫浅底）：info 图标 + 真实症状/照片数 + 「View ↓」（可点 → 病例弹层）。
  /// 病例为空（未自填症状/图）时不渲染；数据未到时也先不渲染（拉到再 setState）。
  Widget _symptomBar(AppLocalizations l10n) {
    final c = _case;
    if (c == null || c.isEmpty) return const SizedBox.shrink();
    final summary = [
      if (c.symptomText != null && c.symptomText!.trim().isNotEmpty) c.symptomText!.trim(),
      if (c.imageUrls.isNotEmpty) '${c.imageUrls.length} foto',
    ].join(' · ');
    return Material(
      color: AppColors.mintTint,
      child: InkWell(
        key: const ValueKey('consultSymptomBar'),
        onTap: () => _showCaseSheet(l10n, c),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          child: Row(
            children: [
              const Icon(Icons.info_outline, size: 15, color: AppColors.mint),
              const SizedBox(width: 9),
              Expanded(
                child: Text(summary,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.mint)),
              ),
              const SizedBox(width: 8),
              Text('${l10n.consultSymptomView} ↓',
                  style: const TextStyle(fontSize: 11, color: AppColors.violetSoft)),
            ],
          ),
        ),
      ),
    );
  }

  /// 病例详情底部弹层：完整症状文 + 私密图缩略网格（点 → 全屏看图）。
  void _showCaseSheet(AppLocalizations l10n, ConsultCase c) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.surface,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.xl, AppSpacing.lg, AppSpacing.xl, AppSpacing.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Center(
                child: Container(
                  width: 40,
                  height: 4,
                  decoration: BoxDecoration(
                    color: AppColors.divider,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
              ),
              const SizedBox(height: AppSpacing.lg),
              Text(l10n.consultCaseTitle, style: AppTypography.title),
              if (c.symptomText != null && c.symptomText!.trim().isNotEmpty) ...[
                const SizedBox(height: AppSpacing.md),
                Text(l10n.consultCaseSymptomLabel, style: AppTypography.caption),
                const SizedBox(height: 4),
                Text(c.symptomText!.trim(), style: AppTypography.body),
              ],
              if (c.imageUrls.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.lg),
                Text(l10n.consultCasePhotosLabel, style: AppTypography.caption),
                const SizedBox(height: AppSpacing.sm),
                SizedBox(
                  height: 88,
                  child: ListView.separated(
                    scrollDirection: Axis.horizontal,
                    itemCount: c.imageUrls.length,
                    separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.sm),
                    itemBuilder: (ictx, i) => GestureDetector(
                      onTap: () => showCaseImageFullScreen(ictx, c.imageUrls[i]),
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(10),
                        child: Image.network(
                          c.imageUrls[i],
                          width: 88,
                          height: 88,
                          fit: BoxFit.cover,
                          errorBuilder: (_, _, _) => Container(
                            width: 88,
                            height: 88,
                            color: AppColors.divider,
                            child: const Icon(Icons.broken_image_outlined, color: AppColors.textTertiary),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
