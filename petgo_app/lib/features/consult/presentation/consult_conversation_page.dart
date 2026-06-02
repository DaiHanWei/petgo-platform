import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/consult_repository.dart';
import '../domain/consult_session.dart';
import 'im_chat_placeholder.dart';

/// 用户侧进行中会话界面（Story 5.5）。常驻免责提示（NFR-9）+ IM 对话区（L2 占位）。
class ConsultConversationPage extends ConsumerStatefulWidget {
  const ConsultConversationPage({super.key, required this.sessionId});

  final int sessionId;

  @override
  ConsumerState<ConsultConversationPage> createState() => _ConsultConversationPageState();
}

class _ConsultConversationPageState extends ConsumerState<ConsultConversationPage> {
  late Future<ConsultSession> _session;

  @override
  void initState() {
    super.initState();
    _session = ref.read(consultRepositoryProvider).get(widget.sessionId);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.consultConversationTitle)),
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
            FutureBuilder<ConsultSession>(
              future: _session,
              builder: (context, snapshot) => ImChatPlaceholder(
                imConversationId: snapshot.data == null ? null : 'session-${widget.sessionId}',
              ),
            ),
          ],
        ),
      ),
    );
  }
}
