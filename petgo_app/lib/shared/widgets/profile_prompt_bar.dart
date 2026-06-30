import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../l10n/app_localizations.dart';

/// 首页档案提示条（Story 1.7，FR-0H）。
///
/// 卡片式：柔和紫渐变底 + 左侧爪印徽章 + 文案 + 弱化关闭 X，下方实心「立即创建」pill。
/// 文案/CTA 复用既有 l10n；CTA 独占一行以容纳较长的印尼语文案。无障碍 ≥44pt 触摸目标（NFR-13）。
class ProfilePromptBar extends StatelessWidget {
  const ProfilePromptBar({super.key, required this.onCreate, required this.onDismiss});

  final VoidCallback onCreate;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mintTint, AppColors.cream2],
        ),
        borderRadius: BorderRadius.circular(AppRounded.lg),
        border: Border.all(color: AppColors.mint.withValues(alpha: 0.16)),
        boxShadow: [
          BoxShadow(
            color: AppColors.mint.withValues(alpha: 0.10),
            blurRadius: 16,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      padding: const EdgeInsets.fromLTRB(14, 12, 8, 14),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 爪印徽章（实心紫圆 + 柔光）。
              Container(
                width: 40,
                height: 40,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.mint,
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(
                      color: AppColors.mint.withValues(alpha: 0.35),
                      blurRadius: 10,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: const Icon(Icons.pets, size: 20, color: AppColors.onAccent),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(top: 3),
                  child: Text(
                    l10n.profilePromptMessage,
                    style: const TextStyle(
                      fontSize: 13,
                      height: 1.45,
                      fontWeight: FontWeight.w600,
                      color: AppColors.ink,
                    ),
                  ),
                ),
              ),
              // 关闭 X：弱化，44pt 触摸目标。
              InkWell(
                key: const ValueKey('profilePromptClose'),
                onTap: onDismiss,
                borderRadius: BorderRadius.circular(AppRounded.full),
                child: const SizedBox(
                  width: 44,
                  height: 44,
                  child: Icon(Icons.close_rounded, size: 18, color: AppColors.textTertiary),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          // CTA：实心 pill，缩进对齐文案；独占一行容纳较长文案。
          Padding(
            padding: const EdgeInsets.only(left: 52),
            child: Align(
              alignment: Alignment.centerLeft,
              child: FilledButton(
                key: const ValueKey('profilePromptCreate'),
                onPressed: onCreate,
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.mint,
                  foregroundColor: AppColors.onAccent,
                  elevation: 0,
                  padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRounded.full)),
                  textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Flexible(
                      child: Text(l10n.profileOnboardingCreate, overflow: TextOverflow.ellipsis),
                    ),
                    const SizedBox(width: 6),
                    const Icon(Icons.arrow_forward_rounded, size: 16),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
