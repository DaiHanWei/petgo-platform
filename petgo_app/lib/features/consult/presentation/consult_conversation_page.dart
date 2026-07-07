import 'dart:async';

import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/im/im_service.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/case_image_viewer.dart';
import 'consult_refresh.dart';
import '../../notify/data/push_permission_providers.dart';
import '../../notify/domain/push_suppression.dart';
import '../../profile/data/health_event_repository.dart';
import '../../profile/data/timeline_repository.dart';
import '../../profile/presentation/archive_prompt_dialog.dart';
import '../data/consult_repository.dart';
import '../domain/consult_case.dart';
import '../domain/consult_diagnosis.dart';
import 'consult_diagnosis_sheet.dart';
import 'consult_diagnosis_view.dart';
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

  // 会话 CLOSED（30min 续聊窗口过）后正文平铺只读会诊结果，替代实时聊天占位。
  ConsultDiagnosis? _diagnosis;
  bool _diagnosisFetched = false; // 完成一次拉取（成功 / 确认无诊断）；失败保持 false 待重试
  bool _diagnosisLoading = false;

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

  /// CLOSED 后拉本次最终诊断供正文平铺。成功（含确认无诊断=null）后置 fetched；
  /// 失败保持 false，由轮询重试。并发/已完成则空转。
  Future<void> _fetchDiagnosisOnce() async {
    if (_diagnosisFetched || _diagnosisLoading) return;
    _diagnosisLoading = true;
    try {
      final d = await ref.read(consultRepositoryProvider).diagnosis(widget.sessionId);
      if (mounted) setState(() => _diagnosis = d);
      _diagnosisFetched = true;
    } catch (_) {
      // 失败不置 fetched → 下次轮询重试。
    } finally {
      _diagnosisLoading = false;
    }
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
        // 后端报已评分即锁死评分入口（含补评分后 closedReason 仍 UNRATED 的情形）。
        if (s.rated) _rated = true;
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
      if (s.status == 'INTERRUPTED') _poll?.cancel();
      if (s.status == 'CLOSED') {
        _maybeTriggerFirstConsultPush();
        // CLOSED → 正文平铺只读诊断；诊断到手（或确认无）再停轮询，失败则随轮询自动重试。
        await _fetchDiagnosisOnce();
        if (_diagnosisFetched) _poll?.cancel();
      }
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
      ref.read(consultRefreshProvider.notifier).bump(); // 通知历史列表刷新已评分
      if (!mounted) return;
      setState(() {
        _rated = true;
        _status = 'CLOSED';
      });
      _poll?.cancel();
      _maybeTriggerFirstConsultPush();
      _fetchDiagnosisOnce(); // 评分即关闭 → 正文平铺只读诊断（poll 已停，须主动拉）。
      showAppToast(context, l10n.consultRateThanks);
    } catch (_) {
      if (!mounted) return;
      showAppToast(context, l10n.consultRateFailed);
    }
  }

  /// 离开会话页：回「问诊」Tab（/triage，含咨询记录），不逐层退回 /consult 发起/case 表单页
  /// —— 用户聊完点返回应回到问诊记录列表（用户反馈：原先错误地回到了首页 /home）；
  /// 用 go 而非 pop：新发起流程栈下是 /consult/case 表单，pop 会错误退回表单（indexedStack
  /// 保留 /triage 既有状态，go 回去等价于回到记录页）。
  void _leave() {
    context.go('/triage');
  }

  /// 查看会诊结果：拉本次最终诊断 → 只读弹层；未出诊断则提示。
  Future<void> _openDiagnosis() async {
    final l10n = AppLocalizations.of(context);
    final d = await ref.read(consultRepositoryProvider).diagnosis(widget.sessionId);
    if (!mounted) return;
    if (d == null) {
      showAppToast(context, l10n.consultResultEmpty);
      return;
    }
    await showConsultDiagnosisSheet(
      context,
      d,
      // 结果弹窗底部「存入宠物档案」（bug 20260707）：先关弹窗再走存档流程。
      footerBuilder: (sheetCtx) => _saveButton(
        l10n,
        onTap: () {
          Navigator.of(sheetCtx).pop();
          _saveToArchive();
        },
      ),
    );
  }

  /// 存入宠物档案（bug 20260706-258·乙 / 20260707）：兽医问诊用户主动存档（去掉自动归档），
  /// 入口从聊天室顶部横幅移到**问诊结果底部**（结果弹窗 + CLOSED 内联结果区）。复用 Story 2.5 三态存档流程。
  /// sourceRef=`consult:<sessionId>` 与档案幂等键、时间线深链一致。存档后刷新成长档案，使 diary 当场可见。
  Future<void> _saveToArchive() async {
    final d = _diagnosis;
    await showArchivePrompt(
      context,
      ref,
      ArchivePromptArgs(
        sourceRef: 'consult:${widget.sessionId}',
        sourceType: HealthSourceType.vetConsult,
        symptomSummary: d?.diagnosis,
        adviceSummary: d?.generalAdvice,
      ),
    );
    if (!mounted) return;
    // 存档（ARCHIVED）后使成长档案时间线 / 统计失效 → 进档案页即见 diary（bug 20260707）。
    ref.invalidate(timelineFirstPageProvider);
    ref.invalidate(archiveStatsProvider);
  }

  /// 「存入宠物档案」按钮（问诊结果底部 opt-in）。[onTap] 由调用处决定是否先关结果弹窗再走存档流程。
  Widget _saveButton(AppLocalizations l10n, {required VoidCallback onTap}) {
    return SizedBox(
      width: double.infinity,
      child: FilledButton.icon(
        key: const ValueKey('consultSaveToArchive'),
        style: FilledButton.styleFrom(backgroundColor: AppColors.mint),
        onPressed: onTap,
        icon: const Text('📁', style: TextStyle(fontSize: 15)),
        label: Text(l10n.triageSaveToArchive),
      ),
    );
  }

  /// 「查看会诊结果」入口横幅（兽医结束后常驻）。
  Widget _resultEntry(AppLocalizations l10n) {
    return Material(
      color: AppColors.vetSurface,
      child: InkWell(
        key: const ValueKey('consultViewResult'),
        onTap: _openDiagnosis,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
          child: Row(
            children: [
              const Text('📋', style: TextStyle(fontSize: 15)),
              const SizedBox(width: 8),
              Expanded(
                child: Text(l10n.consultViewResult,
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.mint)),
              ),
              const Icon(Icons.chevron_right_rounded, size: 18, color: AppColors.mint),
            ],
          ),
        ),
      ),
    );
  }

  /// CLOSED 正文区：平铺只读会诊结果。诊断在途显加载圈；确无诊断（异常/极早期）显温和空态。
  Widget _closedResultArea(AppLocalizations l10n) {
    final d = _diagnosis;
    if (d != null) {
      // 结果平铺 + 底部「存入宠物档案」（bug 20260707：保存移到结果底部，非聊天室顶部）。
      return Column(
        children: [
          Expanded(child: ConsultDiagnosisView(diagnosis: d)),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: _saveButton(l10n, onTap: _saveToArchive),
          ),
        ],
      );
    }
    if (!_diagnosisFetched) return const Center(child: CircularProgressIndicator());
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Text(l10n.consultResultEmpty,
            style: AppTypography.disclaimer, textAlign: TextAlign.center),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final pendingClose = _status == 'PENDING_CLOSE' && !_rated;
    final interrupted = _status == 'INTERRUPTED';
    final closed = _status == 'CLOSED';
    final active = _status == 'IN_PROGRESS';
    // 30min 续聊期(PENDING_CLOSE)：仍可聊天 → 顶部「查看会诊结果」入口（弹层）。
    // CLOSED(窗口过)：不再聊天，会诊结果改为正文平铺（见下），故此入口仅续聊期显示。
    final showResultEntry = _status == 'PENDING_CLOSE';
    // 终态只读标签（Story 5.8 AC3）：已结束 / 未评分 / 已中断。
    final terminalLabel = interrupted
        ? l10n.terminalInterrupted
        : (closed && _closedReason == 'UNRATED' && !_rated)
            ? l10n.terminalUnrated
            : closed
                ? l10n.terminalClosed
                : null;
    return PopScope(
      // 系统返回键也走 _leave（回 /triage 问诊 Tab），与顶栏返回钮一致；不逐层退回 Start Consultation/case 表单。
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _leave();
      },
      child: Scaffold(
      backgroundColor: AppColors.base,
      body: Column(
        children: [
          _topBar(l10n, terminalLabel: terminalLabel),
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
          // 30min 续聊期 → 「查看会诊结果」入口（弹层）；CLOSED 后改正文平铺，不再显示此条。
          if (showResultEntry) _resultEntry(l10n),
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
                  // CLOSED(30min 窗口过)：不再实时聊天，正文平铺只读会诊结果（参考兽医填写页，不可编辑）。
                  if (closed) Expanded(child: _closedResultArea(l10n)),
                ],
              ),
            ),
          ),
        ],
      ),
      ),
    );
  }

  /// 浅色顶栏（原型 chat.html）：返回钮 + 薄荷头像（在线点）+ 兽医名 + 「● Online · 诊所」/终态副行。
  /// 用户侧无「结束会话」入口——会话结束由兽医发起（Story 5.6），用户只能离开/评分。
  Widget _topBar(AppLocalizations l10n, {required String? terminalLabel}) {
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
