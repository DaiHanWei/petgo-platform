import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/placeholder_scaffold.dart';

/// 成长档案 Tab 占位（Story 1.2）。时间线本体由 Epic 2 填充。
class ProfilePage extends StatelessWidget {
  const ProfilePage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PlaceholderScaffold(title: l10n.tabProfile, message: l10n.placeholderComingSoon);
  }
}
