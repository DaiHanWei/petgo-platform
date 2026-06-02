import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../features/auth/domain/auth_state.dart';
import '../../../features/profile/domain/profile_prompt_controller.dart';
import '../../../features/profile/domain/profile_prompt_state.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/profile_prompt_bar.dart';

/// 首页 Tab（Story 1.5 游客只读容器 + Story 1.7 档案提示条）。
///
/// 游客可进、可滚动只读容器 + 空状态占位（FR-0A）；真实 Feed 由 Epic 3 填充。
/// 顶部档案提示条：仅「状态 A 且未完成档案」用户显示（FR-0H，3 次重启状态机）；B/C 不显示。
class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);
    final promptState = ref.watch(profilePromptProvider);
    final bool showPrompt = shouldShowProfilePrompt(
      petStatus: auth.profile?.petStatus,
      hasPetProfile: auth.profile?.hasPetProfile ?? false,
      state: promptState,
    );

    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: Column(
          children: [
            if (showPrompt)
              Padding(
                padding: const EdgeInsets.fromLTRB(
                    AppSpacing.screenEdge, AppSpacing.sm, AppSpacing.screenEdge, 0),
                child: ProfilePromptBar(
                  onCreate: () => context.go('/onboarding/profile'),
                  onDismiss: () => ref.read(profilePromptProvider.notifier).dismiss(),
                ),
              ),
            Expanded(
              child: CustomScrollView(
                slivers: [
                  SliverFillRemaining(
                    hasScrollBody: false,
                    child: EmptyState(title: l10n.homeEmptyTitle, message: l10n.homeEmptyBody),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
