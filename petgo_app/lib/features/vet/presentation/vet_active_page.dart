import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import 'vet_empty_state.dart';

/// 进行中 Tab（Story 5.2 框架，空态占位）。进行中会话内容由 Story 5.5 填充。
class VetActivePage extends StatelessWidget {
  const VetActivePage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.vetTabActive)),
      body: VetEmptyState(icon: Icons.chat_outlined, message: l10n.vetActiveEmpty),
    );
  }
}
