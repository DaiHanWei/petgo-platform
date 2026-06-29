import 'dart:async';

import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/im/im_service.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../consult/presentation/im_chat_placeholder.dart';
import '../data/vet_repository.dart';
import '../domain/consult_ai_context.dart';
import '../domain/vet_diagnosis_draft.dart';
import '../domain/vet_inbox_item.dart';
import 'vet_ai_context_card.dart';
import 'vet_final_diagnosis_page.dart';

/// 兽医侧进行中会话界面（Story 5.5 · 原型 vet-chat.html 1:1）。
///
/// 深色顶栏 #2B2540（宠物/主人身份 + 结束会话）+ FR-5 深色工具条 #1A2B28（Template/Obat/
/// Riwayat/Darurat）+ IM 对话区（L2 占位，[ImChatPlaceholder]）。点 Template 展开 AI 辅助参考。
class VetConversationPage extends ConsumerStatefulWidget {
  const VetConversationPage({super.key, required this.sessionId});

  final int sessionId;

  @override
  ConsumerState<VetConversationPage> createState() => _VetConversationPageState();
}

/// FR-5 工具条当前激活项。
enum _Tool { template, history }

class _VetConversationPageState extends ConsumerState<VetConversationPage> {
  late Future<_VetConvData> _data;

  // Story 5.5 live 增量：进会话即登录 IM（兽医恒签）；离开登出（不留长连接）。
  ImService? _imService;
  bool _imLoginStarted = false;

  // 聊天输入框控制器由本页持有（供 Template「Pakai」预填），传给 ImChatPlaceholder。
  final TextEditingController _chatInput = TextEditingController();

  // 默认展开「Template Saran」辅助参考（原型默认激活态）。
  _Tool _activeTool = _Tool.template;

  // 病例区（症状+照片）默认折叠：薄条常驻,点开看完整病例 + 真图(签名 URL)。
  bool _caseExpanded = false;

  @override
  void initState() {
    super.initState();
    _imService = ref.read(imServiceProvider);
    _data = _load();
  }

  @override
  void dispose() {
    if (_imLoginStarted) _imService?.logout();
    _chatInput.dispose();
    super.dispose();
  }

  Future<_VetConvData> _load() async {
    final repo = ref.read(vetRepositoryProvider);
    final results = await Future.wait([
      repo.session(widget.sessionId),
      repo.aiContext(widget.sessionId),
      repo.assist(widget.sessionId),
    ]);
    final data = _VetConvData(
      results[0] as VetSession,
      results[1] as ConsultAiContext,
      results[2] as ConsultAssist,
    );
    // 兽医进入进行中会话即登录 IM（UserSig 后端恒签兽医）。失败不崩，保留占位演示。
    if (!_imLoginStarted && data.session.status == 'IN_PROGRESS') {
      _imLoginStarted = true;
      unawaited(_imService!.loginIfNeeded().catchError((_) {
        _imLoginStarted = false;
      }));
    }
    return data;
  }

  /// Story C：结束会话前必须填最终诊断表单（原型 `#p-vet-final-diagnosis`）。
  /// 进诊断表单页 → 填完(Diagnosa 必填)提交才真正调 end；返回则取消结束。诊断推用户 + 存档。
  Future<void> _endSession(_VetConvData d) async {
    final l10n = AppLocalizations.of(context);
    final draft = await Navigator.of(context).push<VetDiagnosisDraft>(
      MaterialPageRoute(
        // 诊断页挂根 Navigator → 显式包薄荷主题，避免回落紫色。
        builder: (_) => Theme(
          data: AppTheme.vet,
          child: VetFinalDiagnosisPage(petName: d.session.petName),
        ),
      ),
    );
    if (draft == null || !mounted) return;
    try {
      await ref.read(vetRepositoryProvider).endSession(widget.sessionId, draft);
    } catch (_) {
      // 结束失败 → 留在会话页让兽医重试，不跳工作台。
      // 否则服务端仍 IN_PROGRESS、用户端仍显「进行中」，而兽医已离开 = 两端状态撕裂。
      if (mounted) {
        showAppToast(context, l10n.consultStartFailed);
      }
      return;
    }
    // 仅 end 成功才归工作台。
    if (mounted) context.go('/vet/workbench');
  }

  void _leave() {
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/vet/workbench');
    }
  }

  void _onToolUnavailable() {
    final l10n = AppLocalizations.of(context);
    showAppToast(context, l10n.vetChatToolUnavailable);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.vetSurface2,
      body: FutureBuilder<_VetConvData>(
        future: _data,
        builder: (context, snapshot) {
          if (!snapshot.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          final d = snapshot.data!;
          return SafeArea(
            top: false, // 底部输入栏避让系统导航栏（手势/三键），不被遮挡（用户侧同款处理）。
            child: Column(
            children: [
              _topBar(d),
              _toolsBar(),
              if (_activeTool == _Tool.template)
                _templatePanel(d.assist)
              else
                _historyPanel(d.assist),
              // 病例区(症状+照片):有 AI 上下文时显薄条,点开看完整病例 + 真图。
              if (d.aiContext.hasAiContext) _caseSection(d.aiContext),
              // 消息区 + 输入栏（已对齐原型；气泡/发送色随兽医薄荷主题）。Expanded 贴底。
              ImChatPlaceholder(
                imConversationId: d.session.imConversationId,
                peerId: d.session.userId != null ? 'u_${d.session.userId}' : null,
                accent: AppColors.vetPrimary, // 兽医侧气泡/发送钮薄荷 #5BCBBB（非 M3 偏移色）
                selfIsVet: true, // 兽医视角：己方薄荷「D」/ 对端用户紫「A」
                inputController: _chatInput, // Template「Pakai」预填进此框
              ),
            ],
            ),
          );
        },
      ),
    );
  }

  /// 深色顶栏 #2B2540：返回钮 + 宠物头像 + 「名(主人)」+ 等级/种类/性别/年龄状态行 + Akhiri Sesi。
  Widget _topBar(_VetConvData d) {
    final l10n = AppLocalizations.of(context);
    final session = d.session;
    final hasPet = session.petName != null;
    return Container(
      color: AppColors.vetTopBar,
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.md, AppSpacing.sm),
          child: Row(
            children: [
              InkWell(
                onTap: _leave,
                borderRadius: BorderRadius.circular(10),
                child: Container(
                  width: 34,
                  height: 34,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.arrow_back, size: 18, color: Colors.white),
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Container(
                width: 36,
                height: 36,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.12),
                  shape: BoxShape.circle,
                ),
                child: Text(_speciesEmoji(session.petSpecies), style: const TextStyle(fontSize: 16)),
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      hasPet ? _titleLine(session) : l10n.consultConversationTitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: AppTypography.title.copyWith(color: Colors.white),
                    ),
                    if (_statusLine(l10n, d).isNotEmpty)
                      Text(
                        _statusLine(l10n, d),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.caption.copyWith(color: Colors.white.withValues(alpha: 0.6)),
                      ),
                  ],
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              // 仅 IN_PROGRESS 才可结束:每个 case 只能 end 一次(按 session 状态门控,
              // 天然区分同一用户的多个 case);已结束(PENDING_CLOSE/CLOSED)重进不再显示。
              if (d.session.status == 'IN_PROGRESS')
                OutlinedButton(
                  key: const ValueKey('vetEndSession'),
                  onPressed: () => _endSession(d),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppColors.coral,
                    side: const BorderSide(color: AppColors.coral, width: 1.5),
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: Text(l10n.vetEndSession, style: AppTypography.caption.copyWith(color: AppColors.coral)),
                ),
            ],
          ),
        ),
      ),
    );
  }

  String _titleLine(VetSession s) =>
      s.ownerHandle != null ? '${s.petName} (${s.ownerHandle})' : s.petName!;

  /// 状态副行：「等级 · 种类 · 性别 · 年龄」，缺段跳过。
  String _statusLine(AppLocalizations l10n, _VetConvData d) {
    final parts = <String>[];
    switch (d.aiContext.dangerLevel) {
      case 'YELLOW':
        parts.add(l10n.vetAiContextLevelYellow);
      case 'GREEN':
        parts.add(l10n.vetAiContextLevelGreen);
    }
    final s = d.session;
    switch (s.petSpecies) {
      case 'CAT':
        parts.add(l10n.vetSpeciesCat);
      case 'DOG':
        parts.add(l10n.vetSpeciesDog);
    }
    final m = s.petAgeMonths;
    if (m != null) parts.add(m >= 12 ? l10n.vetAgeYears(m ~/ 12) : l10n.vetAgeMonths(m));
    return parts.join(' · ');
  }

  String _speciesEmoji(String? species) {
    switch (species) {
      case 'CAT':
        return '🐱';
      case 'DOG':
        return '🐶';
      default:
        return '🐾';
    }
  }

  /// FR-5 深色工具条 #1A2B28：TOOLS 标签 + 四工具 chip（Template/History 切换，Obat/Darurat 未提供）。
  Widget _toolsBar() {
    final l10n = AppLocalizations.of(context);
    return Container(
      width: double.infinity,
      color: AppColors.vetToolbar,
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: [
            Text(
              l10n.vetChatToolsLabel,
              style: AppTypography.micro.copyWith(color: Colors.white.withValues(alpha: 0.4), letterSpacing: 0.5),
            ),
            const SizedBox(width: AppSpacing.sm),
            _toolChip(l10n.vetChatToolTemplate, active: _activeTool == _Tool.template,
                onTap: () => setState(() => _activeTool = _Tool.template)),
            const SizedBox(width: 6),
            _toolChip(l10n.vetChatToolDrugs, active: false, onTap: _onToolUnavailable),
            const SizedBox(width: 6),
            _toolChip(l10n.vetChatToolHistory, active: _activeTool == _Tool.history,
                onTap: () => setState(() => _activeTool = _Tool.history)),
            const SizedBox(width: 6),
            _toolChip(l10n.vetChatToolEmergency, active: false, onTap: _onToolUnavailable),
          ],
        ),
      ),
    );
  }

  Widget _toolChip(String label, {required bool active, required VoidCallback onTap}) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          // 工具/辅助区是紫色点缀（原型 vet-chat.html：深薄荷 chrome 中唯一的紫）。
          color: active ? AppColors.mint.withValues(alpha: 0.4) : Colors.white.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: active ? AppColors.mint.withValues(alpha: 0.6) : Colors.white.withValues(alpha: 0.12),
          ),
        ),
        child: Text(
          label,
          style: AppTypography.caption.copyWith(
            color: active ? Colors.white : Colors.white.withValues(alpha: 0.6),
            fontWeight: active ? FontWeight.w600 : FontWeight.w400,
          ),
        ),
      ),
    );
  }

  /// Template Saran 面板：薄荷左边框卡 + AI 参考回复 + 「Pakai」（填输入框供编辑，不自动发，NFR-9）。
  Widget _templatePanel(ConsultAssist assist) {
    final l10n = AppLocalizations.of(context);
    if (assist.aiReferenceReply.isEmpty) return const SizedBox.shrink();
    return Container(
      key: const ValueKey('vetAssistPanel'),
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.md, 0),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.skyTint, // 紫浅底（原型 #F8F6FF）
        borderRadius: BorderRadius.circular(12),
        border: const Border(left: BorderSide(color: AppColors.mint, width: 3)), // 紫左边框
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.vetAssistTitle, style: AppTypography.micro.copyWith(color: AppColors.mint, letterSpacing: 0.5)),
          const SizedBox(height: 6),
          Text(assist.aiReferenceReply, style: AppTypography.body.copyWith(color: AppColors.ink, height: 1.5)),
          const SizedBox(height: AppSpacing.sm),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton(
              key: const ValueKey('vetAssistAdopt'),
              // 「采用」填入输入框供编辑后发送（不自动发，NFR-9）。
              onPressed: () {
                _chatInput.text = assist.aiReferenceReply;
                _chatInput.selection =
                    TextSelection.collapsed(offset: _chatInput.text.length);
              },
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                minimumSize: Size.zero,
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(l10n.vetAssistAdopt),
            ),
          ),
        ],
      ),
    );
  }

  /// 病例区:薄荷薄条(病例 · N 张照片 · 展开/收起),展开渲染 [VetAiContextCard](症状+真图缩略,点开大图)。
  /// 解决「兽医看不到病例图」:后端 aiContext 已返签名 URL,此处真正渲染。
  Widget _caseSection(ConsultAiContext ctx) {
    final l10n = AppLocalizations.of(context);
    final photoCount = ctx.imageUrls.length;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        InkWell(
          key: const ValueKey('vetCaseBar'),
          onTap: () => setState(() => _caseExpanded = !_caseExpanded),
          child: Container(
            width: double.infinity,
            color: AppColors.mintTint,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            child: Row(
              children: [
                const Icon(Icons.assignment_outlined, size: 15, color: AppColors.mint),
                const SizedBox(width: 9),
                Expanded(
                  child: Text(
                    photoCount > 0 ? l10n.vetQueuePhotosAttached(photoCount) : l10n.vetAiContextTitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.mint),
                  ),
                ),
                Icon(_caseExpanded ? Icons.expand_less : Icons.expand_more,
                    size: 18, color: AppColors.mint),
              ],
            ),
          ),
        ),
        if (_caseExpanded)
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 260),
            child: SingleChildScrollView(child: VetAiContextCard(context_: ctx)),
          ),
      ],
    );
  }

  /// Riwayat 面板：历史摘要（冷启动空）。
  Widget _historyPanel(ConsultAssist assist) {
    final l10n = AppLocalizations.of(context);
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.md, 0),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.skyTint, // 紫浅底，与 Template 辅助区一致
        borderRadius: BorderRadius.circular(12),
        border: const Border(left: BorderSide(color: AppColors.mint, width: 3)),
      ),
      child: assist.historySummaries.isEmpty
          ? Text(l10n.vetAssistHistoryEmpty, style: AppTypography.body.copyWith(color: AppColors.textTertiary))
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final h in assist.historySummaries) ...[
                  Text('• $h', style: AppTypography.body.copyWith(color: AppColors.ink, height: 1.5)),
                  const SizedBox(height: 6),
                ],
              ],
            ),
    );
  }
}

class _VetConvData {
  _VetConvData(this.session, this.aiContext, this.assist);

  final VetSession session;
  final ConsultAiContext aiContext;
  final ConsultAssist assist;
}
