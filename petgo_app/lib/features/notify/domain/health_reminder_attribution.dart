import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 最近一次点开的健康提醒（按宠物）。
class HealthReminderAttributionEntry {
  const HealthReminderAttributionEntry({
    required this.notificationId,
    required this.openedAt,
    this.notificationKind,
  });

  final String notificationId;
  final String? notificationKind;
  final DateTime openedAt;
}

/// 健康提醒 → 记录保存的 30 分钟归因（V1.5.0 Story 3.4 · AC4.2，PRD §4 Attribution）。
///
/// 进程内存：`petId → 最近一次 opened`，每次 opened 覆盖。Story 3.2 的 `health_record_saved` 读它：
/// 同宠物、30 分钟内 → `source=health_reminder` + `notification_id` + `notification_kind`。
/// 另记已报过 `source=push` 的 token（每个通知推送点开最多报一次）。换账号由 `resetUserScopedCaches` 清空。
class HealthReminderAttribution extends Notifier<Map<int, HealthReminderAttributionEntry>> {
  static const Duration window = Duration(minutes: 30);

  /// 测试注入时钟。
  DateTime Function() now = DateTime.now;

  final Set<String> _pushReported = {};

  @override
  Map<int, HealthReminderAttributionEntry> build() {
    _pushReported.clear();
    return const {};
  }

  void recordOpened(int petId, String notificationId, String? notificationKind) {
    state = {
      ...state,
      petId: HealthReminderAttributionEntry(
          notificationId: notificationId, notificationKind: notificationKind, openedAt: now()),
    };
  }

  /// 30 分钟内同宠物的归因；过期 / 没有 → null。
  HealthReminderAttributionEntry? lookup(int? petId) {
    if (petId == null) return null;
    final e = state[petId];
    if (e == null || now().difference(e.openedAt) > window) return null;
    return e;
  }

  /// `source=push` 每个 token 只报一次：第一次返回 true 并记下。
  bool claimPushReport(String token) => _pushReported.add(token);
}

final healthReminderAttributionProvider =
    NotifierProvider<HealthReminderAttribution, Map<int, HealthReminderAttributionEntry>>(
        HealthReminderAttribution.new);
