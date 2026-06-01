import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/placeholder_scaffold.dart';

/// 新用户引导占位页（Story 1.3 分流锚点）。
///
/// 昵称确认 + 宠物状态选择本体由 Story 1.6 实现；本页仅承接新用户分流路由。
class OnboardingPlaceholderPage extends StatelessWidget {
  const OnboardingPlaceholderPage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PlaceholderScaffold(
      title: l10n.onboardingWelcomeTitle,
      message: l10n.onboardingWelcomeBody,
    );
  }
}
