import 'package:flutter/foundation.dart';

/// 点开健康提醒的落点（后端 `HealthNotificationOpenResponse`，V1.5.0 Story 2.4 / 架构增量 §5.4）。
@immutable
class HealthReminderOpenResult {
  const HealthReminderOpenResult({
    required this.status,
    this.petId,
    this.route,
    this.focusRecordId,
    this.prefill,
    this.safetyBoxes = const [],
    this.analytics,
  });

  /// `OK` / `PET_UNAVAILABLE`。
  final String status;
  final int? petId;

  /// `FORM` / `PAGE` / `PAGE_SATISFIED`。
  final String? route;
  final int? focusRecordId;
  final HealthReminderOpenPrefill? prefill;

  /// `PREGNANT`、`PERMETHRIN`（后端已排好：怀孕在前）。
  final List<String> safetyBoxes;
  final HealthReminderOpenAnalytics? analytics;

  bool get isOk => status == 'OK';

  factory HealthReminderOpenResult.fromJson(Map<String, dynamic> json) {
    final pre = json['prefill'];
    final an = json['analytics'];
    return HealthReminderOpenResult(
      status: json['status'] as String? ?? '',
      petId: (json['petId'] as num?)?.toInt(),
      route: json['route'] as String?,
      focusRecordId: (json['focusRecordId'] as num?)?.toInt(),
      prefill: pre is Map<String, dynamic> ? HealthReminderOpenPrefill.fromJson(pre) : null,
      safetyBoxes: [for (final s in (json['safetyBoxes'] as List<dynamic>? ?? const [])) if (s is String) s],
      analytics: an is Map<String, dynamic> ? HealthReminderOpenAnalytics.fromJson(an) : null,
    );
  }
}

/// FORM 路由的表单预填。
@immutable
class HealthReminderOpenPrefill {
  const HealthReminderOpenPrefill({
    this.type,
    this.vaccinePreset,
    this.dose,
    this.dewormType,
    this.customName,
    this.eventDateEmpty = false,
  });

  final String? type;
  final String? vaccinePreset;
  final int? dose;
  final String? dewormType;
  final String? customName;
  final bool eventDateEmpty;

  factory HealthReminderOpenPrefill.fromJson(Map<String, dynamic> json) => HealthReminderOpenPrefill(
        type: json['type'] as String?,
        vaccinePreset: json['vaccinePreset'] as String?,
        dose: (json['dose'] as num?)?.toInt(),
        dewormType: json['dewormType'] as String?,
        customName: json['customName'] as String?,
        eventDateEmpty: json['eventDateEmpty'] as bool? ?? false,
      );
}

/// `health_notification_opened` 的属性（后端已给好；kind = 附录 C ID，pushPoint / origin 小写蛇形）。
@immutable
class HealthReminderOpenAnalytics {
  const HealthReminderOpenAnalytics({
    required this.notificationId,
    this.notificationKind,
    this.pushPoint,
    this.origin,
    this.mergedCount = 1,
  });

  final String notificationId;
  final String? notificationKind;
  final String? pushPoint;
  final String? origin;
  final int mergedCount;

  factory HealthReminderOpenAnalytics.fromJson(Map<String, dynamic> json) => HealthReminderOpenAnalytics(
        notificationId: json['notificationId'] as String? ?? '',
        notificationKind: json['notificationKind'] as String?,
        pushPoint: json['pushPoint'] as String?,
        origin: json['origin'] as String?,
        mergedCount: (json['mergedCount'] as num?)?.toInt() ?? 1,
      );
}

/// 点开结果 → `/profile/health` 的 location（Story 3.1 的页面参数）。纯函数，L0 可测。
///
/// - FORM → `add=<type>&preset=&dose=&deworm=&custom=&dateEmpty=1&nid=<t>`；
/// - PAGE / PAGE_SATISFIED → `focus=<id>&safety=<逗号连接>&nid=<t>`。
String healthReminderTargetLocation(HealthReminderOpenResult r, String token) {
  final q = <String, String>{};
  if (r.route == 'FORM') {
    final p = r.prefill;
    if (p?.type != null) q['add'] = p!.type!;
    if (p?.vaccinePreset != null) q['preset'] = p!.vaccinePreset!;
    if (p?.dose != null) q['dose'] = '${p!.dose}';
    if (p?.dewormType != null) q['deworm'] = p!.dewormType!;
    if (p?.customName != null && p!.customName!.isNotEmpty) q['custom'] = p.customName!;
    if (p?.eventDateEmpty ?? false) q['dateEmpty'] = '1';
  } else {
    if (r.focusRecordId != null) q['focus'] = '${r.focusRecordId}';
    if (r.safetyBoxes.isNotEmpty) q['safety'] = r.safetyBoxes.join(',');
  }
  q['nid'] = token;
  return Uri(path: '/profile/health', queryParameters: q).toString();
}

/// `route` 埋点值（小写蛇形）。
String healthReminderRouteValue(HealthReminderOpenResult r) => switch (r.route) {
      'FORM' => 'form',
      'PAGE_SATISFIED' => 'page_satisfied',
      _ => 'page',
    };
