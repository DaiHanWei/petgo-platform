import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import 'vet_empty_state.dart';

/// 历史记录 Tab（Story 5.2 框架，空态占位）。历史内容由 Story 5.6/5.8 填充。
class VetHistoryPage extends StatelessWidget {
  const VetHistoryPage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.vetTabHistory)),
      body: VetEmptyState(icon: Icons.history, message: l10n.vetHistoryEmpty),
    );
  }
}
