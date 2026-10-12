/// 通知中心五段分组（V1.3.4 follow · Story 3.4 · FR-141.3 / AD-12 / T-40）。
enum NotificationGroup { today, yesterday, last7Days, last30Days, earlier }

/// 某条通知属于哪一段 —— 按**手机本地时间的自然日**相减（不按 24 小时）。
///
/// - 相差 ≤ 0 天 → [NotificationGroup.today]（含 `createdAt == null`；时钟偏差导致的「未来时间」也归今天）；
/// - 1 → [NotificationGroup.yesterday]；2 ~ 7 → [NotificationGroup.last7Days]；
///   8 ~ 30 → [NotificationGroup.last30Days]；> 30 → [NotificationGroup.earlier]。
///
/// ⚠️ 后端时间是 UTC（`…Z`），必须先 `toLocal()` 再取年月日：WIB 早上 0 ~ 7 点的通知按 UTC 算会落到「昨天」。
NotificationGroup groupOf(DateTime? createdAt, DateTime now) {
  if (createdAt == null) return NotificationGroup.today;
  final days = localDayDiff(createdAt, now);
  if (days <= 0) return NotificationGroup.today;
  if (days == 1) return NotificationGroup.yesterday;
  if (days <= 7) return NotificationGroup.last7Days;
  if (days <= 30) return NotificationGroup.last30Days;
  return NotificationGroup.earlier;
}

/// `now` 与 `createdAt` 在本地日历上相差几天（按年月日，跨夏令时也不受影响：用 UTC 日期做差）。
int localDayDiff(DateTime createdAt, DateTime now) {
  final a = createdAt.toLocal();
  final b = now.toLocal();
  final dayA = DateTime.utc(a.year, a.month, a.day);
  final dayB = DateTime.utc(b.year, b.month, b.day);
  return dayB.difference(dayA).inDays;
}
