import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/placeholder_scaffold.dart';

/// 状态 A 档案创建引导页（Story 1.6 分叉锚点；Story 1.7 实现入口 + 「跳过，稍后创建」+ 提示条逻辑）。
class ProfileOnboardingPage extends StatelessWidget {
  const ProfileOnboardingPage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PlaceholderScaffold(
      title: l10n.profileOnboardingTitle,
      message: l10n.profileOnboardingBody,
    );
  }
}
