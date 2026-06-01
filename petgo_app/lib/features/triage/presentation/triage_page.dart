import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/placeholder_scaffold.dart';

/// 问诊 Tab 占位（Story 1.2）。AI 分诊 / 兽医咨询本体由 Epic 4/5 填充。
class TriagePage extends StatelessWidget {
  const TriagePage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PlaceholderScaffold(title: l10n.tabTriage, message: l10n.placeholderComingSoon);
  }
}
