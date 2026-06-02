import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';

/// 实时对话区占位（Story 5.5）。
///
/// 腾讯 IM Flutter SDK 直连收发文字/图片/视频（≤60s）+ 后台保连消息连续（NFR-5）属 **L2**
/// （需真机 + 真实 SDKAppID/UserSig），云端 headless 不可运行。本占位标注接入位，
/// 真机接入时在此替换为 IM SDK 会话组件（用 `imConversationId` 加载会话）。
class ImChatPlaceholder extends StatelessWidget {
  const ImChatPlaceholder({super.key, this.imConversationId});

  final String? imConversationId;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Expanded(
      child: Container(
        key: const ValueKey('imChatPlaceholder'),
        width: double.infinity,
        margin: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.border),
        ),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.forum_outlined, size: 40, color: AppColors.textTertiary),
              const SizedBox(height: AppSpacing.sm),
              Text(l10n.imChatPlaceholder, style: AppTypography.body),
              const SizedBox(height: 4),
              Text(l10n.imChatPlaceholderHint,
                  style: AppTypography.disclaimer, textAlign: TextAlign.center),
            ],
          ),
        ),
      ),
    );
  }
}
