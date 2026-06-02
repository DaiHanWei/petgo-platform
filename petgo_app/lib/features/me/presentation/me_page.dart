import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/placeholder_scaffold.dart';

/// 「我的」Tab 占位（Story 1.2）。个人中心本体由 Epic 7 填充。
class MePage extends StatelessWidget {
  const MePage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PlaceholderScaffold(title: l10n.tabMe, message: l10n.placeholderComingSoon);
  }
}
