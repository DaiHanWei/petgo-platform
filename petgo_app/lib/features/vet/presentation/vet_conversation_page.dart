import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../consult/presentation/im_chat_placeholder.dart';
import '../data/vet_repository.dart';
import '../domain/consult_ai_context.dart';
import '../domain/vet_inbox_item.dart';
import 'vet_ai_context_card.dart';

/// 兽医侧进行中会话界面（Story 5.5）。顶部 AI 上下文卡（5.4）+ FR-5 辅助面板 + IM 对话区（L2 占位）。
class VetConversationPage extends ConsumerStatefulWidget {
  const VetConversationPage({super.key, required this.sessionId});

  final int sessionId;

  @override
  ConsumerState<VetConversationPage> createState() => _VetConversationPageState();
}

class _VetConversationPageState extends ConsumerState<VetConversationPage> {
  late Future<_VetConvData> _data;

  @override
  void initState() {
    super.initState();
    _data = _load();
  }

  Future<_VetConvData> _load() async {
    final repo = ref.read(vetRepositoryProvider);
    final results = await Future.wait([
      repo.session(widget.sessionId),
      repo.aiContext(widget.sessionId),
      repo.assist(widget.sessionId),
    ]);
    return _VetConvData(
      results[0] as VetSession,
      results[1] as ConsultAiContext,
      results[2] as ConsultAssist,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.consultConversationTitle)),
      body: SafeArea(
        child: FutureBuilder<_VetConvData>(
          future: _data,
          builder: (context, snapshot) {
            if (!snapshot.hasData) {
              return const Center(child: CircularProgressIndicator());
            }
            final d = snapshot.data!;
            return Column(
              children: [
                // 顶部 AI 上下文卡（DIRECT 会话 hasAiContext=false → 不渲染）。
                VetAiContextCard(context_: d.aiContext),
                _AssistPanel(assist: d.assist),
                ImChatPlaceholder(imConversationId: d.session.imConversationId),
              ],
            );
          },
        ),
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

/// FR-5 辅助面板（Story 5.5）：AI 参考回复（点「采用」填输入框供编辑，不自动发）+ 历史摘要（冷启动空）。
class _AssistPanel extends StatelessWidget {
  const _AssistPanel({required this.assist});

  final ConsultAssist assist;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('vetAssistPanel'),
      width: double.infinity,
      margin: const EdgeInsets.symmetric(horizontal: AppSpacing.md),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.triageYellowSurface,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.vetAssistTitle, style: AppTypography.caption),
          const SizedBox(height: 4),
          Text(assist.aiReferenceReply, style: AppTypography.body),
          const SizedBox(height: AppSpacing.sm),
          Align(
            alignment: Alignment.centerRight,
            child: OutlinedButton(
              key: const ValueKey('vetAssistAdopt'),
              // 「采用」填入输入框供编辑后发送（不自动发，NFR-9）；真实输入框接入随 IM SDK（L2）。
              onPressed: () {},
              child: Text(l10n.vetAssistAdopt),
            ),
          ),
          if (assist.historySummaries.isEmpty)
            Text(l10n.vetAssistHistoryEmpty, style: AppTypography.disclaimer),
        ],
      ),
    );
  }
}
