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

    // 直连病例 dangerLevel=null:不显假评级,标题用「病例」,左边框中性薄荷;AI 升级才显 GREEN/YELLOW 评级。
    final hasLevel = context_.dangerLevel != null;
    final isYellow = context_.dangerLevel == 'YELLOW';
    final levelLabel = isYellow ? l10n.vetAiContextLevelYellow : l10n.vetAiContextLevelGreen;
    final levelColor = !hasLevel
        ? AppColors.mint
        : (isYellow ? AppColors.triageYellow : AppColors.triageGreen);

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
          Text(hasLevel ? l10n.vetAiContextTitle : l10n.vetCaseTitle, style: AppTypography.caption),
          if (hasLevel) ...[
            const SizedBox(height: 2),
            Text(levelLabel, style: AppTypography.title.copyWith(color: levelColor)),
          ],
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
                itemBuilder: (ctx, i) => GestureDetector(
                  onTap: () => showCaseImageFullScreen(ctx, context_.imageUrls[i]),
                  child: ClipRRect(
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
            ),
          ],
        ],
      ),
    );
  }
}

/// 病例图全屏查看（黑底 + 双指缩放 + 点击关闭）。会话页/预览页缩略图共用。
Future<void> showCaseImageFullScreen(BuildContext context, String url) {
  return showDialog<void>(
    context: context,
    barrierColor: Colors.black87,
    builder: (ctx) => GestureDetector(
      onTap: () => Navigator.of(ctx).pop(),
      child: Stack(
        children: [
          Positioned.fill(
            child: InteractiveViewer(
              minScale: 1,
              maxScale: 4,
              child: Center(
                child: Image.network(
                  url,
                  fit: BoxFit.contain,
                  errorBuilder: (_, _, _) =>
                      const Icon(Icons.broken_image_outlined, color: Colors.white54, size: 48),
                ),
              ),
            ),
          ),
          Positioned(
            top: 40,
            right: 16,
            child: IconButton(
              icon: const Icon(Icons.close, color: Colors.white, size: 28),
              onPressed: () => Navigator.of(ctx).pop(),
            ),
          ),
        ],
      ),
    ),
  );
}
