import 'dart:async';

import 'package:flutter/material.dart';
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
import '../domain/vet_inbox_item.dart';

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

  // 默认展开「Template Saran」辅助参考（原型默认激活态）。
  _Tool _activeTool = _Tool.template;

  @override
  void initState() {
    super.initState();
    _imService = ref.read(imServiceProvider);
    _data = _load();
  }

  @override
  void dispose() {
    if (_imLoginStarted) _imService?.logout();
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

  Future<void> _endSession() async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      // 弹窗挂根 Navigator（在 _vetScoped 主题子树之上）→ 显式包薄荷主题，避免按钮回落紫色。
      builder: (ctx) => Theme(
        data: AppTheme.vet,
        child: AlertDialog(
          title: Text(l10n.vetEndConfirmTitle),
          actions: [
            TextButton(
              key: const ValueKey('vetEndConfirmNo'),
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text(l10n.vetEndConfirmNo),
            ),
            FilledButton(
              key: const ValueKey('vetEndConfirmYes'),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: Text(l10n.vetEndConfirmYes),
            ),
          ],
        ),
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      await ref.read(vetRepositoryProvider).endSession(widget.sessionId);
    } catch (_) {
      // 结束失败也返回（服务端状态权威）。
    }
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
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(l10n.vetChatToolUnavailable)));
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
          return Column(
            children: [
              _topBar(d),
              _toolsBar(),
              if (_activeTool == _Tool.template)
                _templatePanel(d.assist)
              else
                _historyPanel(d.assist),
              // 消息区 + 输入栏（已对齐原型；气泡/发送色随兽医薄荷主题）。Expanded 贴底。
              ImChatPlaceholder(
                imConversationId: d.session.imConversationId,
                peerId: d.session.userId != null ? 'u_${d.session.userId}' : null,
                accent: AppColors.vetPrimary, // 兽医侧气泡/发送钮薄荷 #5BCBBB（非 M3 偏移色）
                selfIsVet: true, // 兽医视角：己方薄荷「D」/ 对端用户紫「A」
              ),
            ],
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
              OutlinedButton(
                key: const ValueKey('vetEndSession'),
                onPressed: _endSession,
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
    switch (s.petSex) {
      case 'MALE':
        parts.add(l10n.vetSexMale);
      case 'FEMALE':
        parts.add(l10n.vetSexFemale);
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
          color: active ? AppColors.vetPrimary.withValues(alpha: 0.4) : Colors.white.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: active ? AppColors.vetPrimary.withValues(alpha: 0.6) : Colors.white.withValues(alpha: 0.12),
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
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: const Border(left: BorderSide(color: AppColors.vetPrimary, width: 3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.vetAssistTitle, style: AppTypography.micro.copyWith(color: AppColors.vetPrimaryDeep, letterSpacing: 0.5)),
          const SizedBox(height: 6),
          Text(assist.aiReferenceReply, style: AppTypography.body.copyWith(color: AppColors.ink, height: 1.5)),
          const SizedBox(height: AppSpacing.sm),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton(
              key: const ValueKey('vetAssistAdopt'),
              // 「采用」填入输入框供编辑后发送（不自动发，NFR-9）；真实输入框接入随 IM SDK（L2）。
              onPressed: () {},
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.vetPrimary,
                foregroundColor: AppColors.vetOnAccent,
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

  /// Riwayat 面板：历史摘要（冷启动空）。
  Widget _historyPanel(ConsultAssist assist) {
    final l10n = AppLocalizations.of(context);
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.md, 0),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: const Border(left: BorderSide(color: AppColors.vetPrimary, width: 3)),
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
