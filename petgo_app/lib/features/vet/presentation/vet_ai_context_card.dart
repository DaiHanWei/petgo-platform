import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/consult_ai_context.dart';

/// 兽医侧「AI 上下文卡」（Story 5.4 F3）。挂载在 5.5 对话界面顶部 / 待接单详情。
///
/// AI_UPGRADE 会话渲染评级 + 症状描述 + 图片缩略（私密桶签名 URL，点开看大图）；
/// DIRECT 会话（`hasAiContext=false`）**不渲染**（返回 SizedBox.shrink）。
class VetAiContextCard extends StatelessWidget {
  const VetAiContextCard({super.key, required this.context_});

  final ConsultAiContext context_;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    if (!context_.hasAiContext) return const SizedBox.shrink();

    final isYellow = context_.dangerLevel == 'YELLOW';
    final levelLabel = isYellow ? l10n.vetAiContextLevelYellow : l10n.vetAiContextLevelGreen;
    final levelColor = isYellow ? AppColors.triageYellow : AppColors.triageGreen;

    return Container(
      key: const ValueKey('vetAiContextCard'),
      width: double.infinity,
      margin: const EdgeInsets.all(AppSpacing.md),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border(left: BorderSide(color: levelColor, width: 3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.vetAiContextTitle, style: AppTypography.caption),
          const SizedBox(height: 2),
          Text(levelLabel, style: AppTypography.title.copyWith(color: levelColor)),
          if (context_.symptomText != null && context_.symptomText!.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.sm),
            Text(context_.symptomText!, style: AppTypography.body),
          ],
          if (context_.imageUrls.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.md),
            SizedBox(
              height: 72,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: context_.imageUrls.length,
                separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.sm),
                itemBuilder: (ctx, i) => ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.network(
                    context_.imageUrls[i],
                    width: 72,
                    height: 72,
                    fit: BoxFit.cover,
                    errorBuilder: (_, _, _) => Container(
                      width: 72,
                      height: 72,
                      color: AppColors.divider,
                      child: const Icon(Icons.broken_image_outlined, color: AppColors.textTertiary),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
