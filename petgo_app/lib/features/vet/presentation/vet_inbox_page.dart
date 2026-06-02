import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import 'vet_empty_state.dart';

/// 待接单 Tab（Story 5.2 框架，空态占位）。真实 WAITING 请求由 Story 5.3 写入 + 渲染。
class VetInboxPage extends StatelessWidget {
  const VetInboxPage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.vetTabInbox)),
      body: VetEmptyState(icon: Icons.inbox_outlined, message: l10n.vetInboxEmpty),
    );
  }
}
