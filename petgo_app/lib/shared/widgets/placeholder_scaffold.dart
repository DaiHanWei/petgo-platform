import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';

/// 通用空白占位页（Story 1.2 五 Tab 占位；各 Epic 后续填充真实内容）。
///
/// 居中标题 + 副文，全部文案由调用方传入（须来自 .arb，禁止写死）。
/// 底色继承全局 #FAF8F5（不自设背景色，符合 UX-DR1）。
class PlaceholderScaffold extends StatelessWidget {
  const PlaceholderScaffold({super.key, required this.title, required this.message});

  final String title;
  final String message;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.base,
      body: Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(title, style: AppTypography.title, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.sm),
              Text(message, style: AppTypography.caption, textAlign: TextAlign.center),
            ],
          ),
        ),
      ),
    );
  }
}
