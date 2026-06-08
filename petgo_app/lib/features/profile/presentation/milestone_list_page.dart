import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';

/// 里程碑列表页（壳）（Story 6.1 · FR-42 深链承接）。
///
/// 承接 `MILESTONE_NODE` 推送深链（`/profile/milestones`）。里程碑本体（数据/卡片/时间线）
/// 属里程碑 mini-epic（PRD V1.0.0 修订 F2，排期 1.0.x/1.1.0），本 Story 仅落「壳」使深链可达、不白屏。
class MilestoneListPage extends StatelessWidget {
  const MilestoneListPage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.milestoneListTitle)),
      body: EmptyState(
        title: l10n.milestoneListTitle,
        message: l10n.milestoneListComingSoon,
        icon: Icons.flag_outlined,
      ),
    );
  }
}
