import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';

/// 首页 Tab（Story 1.5）。
///
/// 游客可进、可滚动只读容器 + 空状态占位（FR-0A / UX-DR8）；真实 Feed 由 Epic 3 填充。
/// 软浮层滚动触发（首页浏览至第 3 页）由 Epic 3 提供滚动深度后接线（本 Story 不接）。
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        // 可滚动容器：即使空状态也可下拉滚动（游客只读，不白屏）。
        child: CustomScrollView(
          slivers: [
            SliverFillRemaining(
              hasScrollBody: false,
              child: EmptyState(title: l10n.homeEmptyTitle, message: l10n.homeEmptyBody),
            ),
          ],
        ),
      ),
    );
  }
}
