import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/notify/domain/notification_grouping.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';

/// V1.3.4 follow · Story 3.4 · AC1 / T-40：五段分组按手机本地时间的自然日。
///
/// 时区相关用例一律构造**本地**时间（`DateTime(...)`）再转 UTC 喂进去，CI 跑在什么时区都稳定。
void main() {
  final now = DateTime(2026, 10, 12, 8, 0); // 本地时间

  DateTime daysAgo(int d, {int hour = 12}) => DateTime(2026, 10, 12 - d, hour).toUtc();

  test('相差天数 → 五段（T-40：2–7 / 8–30 / >30）', () {
    expect(groupOf(daysAgo(0), now), NotificationGroup.today);
    expect(groupOf(daysAgo(1), now), NotificationGroup.yesterday);
    expect(groupOf(daysAgo(2), now), NotificationGroup.last7Days);
    expect(groupOf(daysAgo(7), now), NotificationGroup.last7Days);
    expect(groupOf(daysAgo(8), now), NotificationGroup.last30Days);
    expect(groupOf(daysAgo(30), now), NotificationGroup.last30Days);
    expect(groupOf(daysAgo(31), now), NotificationGroup.earlier);
  });

  test('null 与「未来时间」都归今天', () {
    expect(groupOf(null, now), NotificationGroup.today);
    expect(groupOf(now.add(const Duration(hours: 3)).toUtc(), now), NotificationGroup.today);
  });

  test('按自然日而不是 24 小时：昨晚 23:59 与今早 00:01 只差 2 分钟也分属两段', () {
    final today0001 = DateTime(2026, 10, 12, 0, 1);
    expect(groupOf(DateTime(2026, 10, 11, 23, 59).toUtc(), today0001), NotificationGroup.yesterday);
    expect(groupOf(DateTime(2026, 10, 12, 0, 0).toUtc(), today0001), NotificationGroup.today);
  });

  test('UTC 输入按本地日期判：本地今天 00:30 的通知（UTC 可能是昨天）归今天', () {
    final localMidnightish = DateTime(2026, 10, 12, 0, 30);
    expect(groupOf(localMidnightish.toUtc(), now), NotificationGroup.today);
  });

  /// code-review F3：CI 跑在 UTC，默认 toLocal() 与 UTC 无差别 —— 注入固定 +7（WIB）证明「先换本地再取日期」。
  test('注入 WIB +7：UTC 昨天 18:00 = 本地今天 01:00 → 今天；去掉换算就会错判成昨天', () {
    DateTime wib(DateTime d) => d.toUtc().add(const Duration(hours: 7));
    final createdUtc = DateTime.utc(2026, 10, 11, 18, 0);
    final nowUtc = DateTime.utc(2026, 10, 12, 1, 0); // = WIB 08:00
    expect(groupOf(createdUtc, nowUtc, toLocal: wib), NotificationGroup.today);
    expect(localDayDiff(createdUtc, nowUtc, toLocal: (d) => d.toUtc()), 1, reason: '不换算按 UTC 就是昨天');
  });

  test('跨月边界', () {
    final nov2 = DateTime(2026, 11, 2, 9);
    expect(groupOf(DateTime(2026, 10, 31, 9).toUtc(), nov2), NotificationGroup.last7Days);
    expect(groupOf(DateTime(2026, 11, 1, 9).toUtc(), nov2), NotificationGroup.yesterday);
  });

  group('NotificationItem.fromJson：V1.3.4 新字段', () {
    test('缺键容错（旧后端 / NON_NULL 不下发 null）', () {
      final i = NotificationItem.fromJson(const {'type': 'CONTENT_LIKED', 'read': false});
      expect(i.actorName, isNull);
      expect(i.actorCount, 0);
      expect(i.actorUserId, isNull);
      expect(i.actorToken, isNull);
      expect(i.actorIFollow, isNull);
      expect(i.actorFollowsMe, isNull);
      expect(i.hasServerCopy, isFalse);
    });

    test('有值', () {
      final i = NotificationItem.fromJson(const {
        'type': 'USER_FOLLOWED',
        'read': true,
        'title': 'Rani mulai mengikutimu',
        'actorName': 'Rani',
        'actorCount': 1,
        'actorUserId': 42,
        'actorToken': 'tok42',
        'actorIFollow': false,
        'actorFollowsMe': true,
      });
      expect(i.actorName, 'Rani');
      expect(i.actorCount, 1);
      expect(i.actorUserId, 42);
      expect(i.actorToken, 'tok42');
      expect(i.actorIFollow, isFalse);
      expect(i.actorFollowsMe, isTrue);
      expect(i.hasServerCopy, isTrue);
    });

    test('系统类即便带了 actorName 也不算后端文案条目', () {
      final i = NotificationItem.fromJson(const {'type': 'VET_REPLY', 'read': true, 'actorName': 'x'});
      expect(i.hasServerCopy, isFalse);
    });
  });
}
