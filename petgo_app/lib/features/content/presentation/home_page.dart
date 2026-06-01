import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/placeholder_scaffold.dart';

/// 首页 Tab 占位（Story 1.2）。
///
/// Story 1.5 收紧为游客可滚动只读容器 + 空状态占位；真实 Feed 由 Epic 3 填充。
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PlaceholderScaffold(title: l10n.tabHome, message: l10n.placeholderComingSoon);
  }
}
