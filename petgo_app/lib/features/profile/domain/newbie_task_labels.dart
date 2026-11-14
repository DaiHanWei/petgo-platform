import '../../../l10n/app_localizations.dart';

/// 新手任务 7 项的对外 key（V1.5.0 onboarding-recall Story 3.1 · FR-176，与后端 `NewbieTask` 逐字一致，固定顺序）。
const List<String> kNewbieTaskKeys = [
  'FIRST_DIARY',
  'FIRST_LIKE_RECEIVED',
  'FIRST_FOLLOW',
  'FIRST_LIKE_GIVEN',
  'FIRST_COMMENT',
  'FIRST_REPLY',
  'ONE_WEEK',
];

/// 任务名（本地化，arb 逐字照整合稿「新增文案」表）。
///
/// 🔴 未知 key 返回 null —— 调用方**不渲染该行**，不再把大写 key 原样露给用户（V1.5.0 之前的兜底会露原文）。
/// V1.5.0 之前的 6 个旧 key 已随后端改为 7 项而删除。
String? newbieTaskLabel(String key, AppLocalizations l10n) => switch (key) {
      'FIRST_DIARY' => l10n.newbieTaskFirstDiary,
      'FIRST_LIKE_RECEIVED' => l10n.newbieTaskFirstLikeReceived,
      'FIRST_FOLLOW' => l10n.newbieTaskFirstFollow,
      'FIRST_LIKE_GIVEN' => l10n.newbieTaskFirstLikeGiven,
      'FIRST_COMMENT' => l10n.newbieTaskFirstComment,
      'FIRST_REPLY' => l10n.newbieTaskFirstReply,
      'ONE_WEEK' => l10n.newbieTaskOneWeek,
      _ => null,
    };

/// 任务的对外序号 1–7（埋点 `task`）；未知 key → null。
int? newbieTaskOrdinal(String key) {
  final i = kNewbieTaskKeys.indexOf(key);
  return i < 0 ? null : i + 1;
}
