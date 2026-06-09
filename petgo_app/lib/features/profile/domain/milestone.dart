/// 里程碑领域模型（Story 8.2 · FR-42）。**后端契约镜像**（C5）：字段集严格对应后端
/// `MilestoneListResponse`/`MilestoneGroupResponse`/`MilestoneItemResponse`，不得自创字段。
library;

/// 里程碑级别（线格式 S/M/L）。
enum MilestoneLevel {
  s,
  m,
  l;

  static MilestoneLevel fromWire(String wire) => switch (wire) {
        'S' => MilestoneLevel.s,
        'M' => MilestoneLevel.m,
        'L' => MilestoneLevel.l,
        _ => MilestoneLevel.s,
      };
}

/// 触发方式（线格式 UPPER_SNAKE）。决定徽章点击交互（系统类→说明 / 打卡类→两入口）。
enum MilestoneTrigger {
  systemAuto,
  userCheckin,
  pushPublish;

  static MilestoneTrigger fromWire(String wire) => switch (wire) {
        'SYSTEM_AUTO' => MilestoneTrigger.systemAuto,
        'USER_CHECKIN' => MilestoneTrigger.userCheckin,
        'PUSH_PUBLISH' => MilestoneTrigger.pushPublish,
        _ => MilestoneTrigger.systemAuto,
      };

  /// 用户打卡类：未完成时点击徽章弹「已打卡 / 去发布」两入口（FR-42）。
  bool get isCheckin => this == MilestoneTrigger.userCheckin;
}

/// 单个里程碑（对外用 code，非顺序 id）。
class MilestoneItem {
  const MilestoneItem({
    required this.code,
    required this.title,
    required this.level,
    required this.trigger,
    required this.completed,
    this.completedAt,
  });

  final String code;
  final String title;
  final MilestoneLevel level;
  final MilestoneTrigger trigger;
  final bool completed;
  final DateTime? completedAt;

  factory MilestoneItem.fromJson(Map<String, dynamic> json) => MilestoneItem(
        code: json['code'] as String,
        title: json['title'] as String,
        level: MilestoneLevel.fromWire(json['level'] as String),
        trigger: MilestoneTrigger.fromWire(json['triggerType'] as String),
        completed: (json['completed'] ?? false) as bool,
        completedAt: json['completedAt'] == null
            ? null
            : DateTime.parse(json['completedAt'] as String),
      );
}

/// 一个级别分组（L/M/S 独立分区）。
class MilestoneGroup {
  const MilestoneGroup({
    required this.level,
    required this.completedCount,
    required this.totalCount,
    required this.items,
  });

  final MilestoneLevel level;
  final int completedCount;
  final int totalCount;
  final List<MilestoneItem> items;

  factory MilestoneGroup.fromJson(Map<String, dynamic> json) => MilestoneGroup(
        level: MilestoneLevel.fromWire(json['level'] as String),
        completedCount: (json['completedCount'] ?? 0) as int,
        totalCount: (json['totalCount'] ?? 0) as int,
        items: ((json['items'] ?? const []) as List)
            .map((e) => MilestoneItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// 里程碑列表页数据（顶部宠物信息 + 总进度 + L/M/S 分区）。
class MilestoneList {
  const MilestoneList({
    required this.petName,
    this.petAvatarUrl,
    required this.completedCount,
    required this.totalCount,
    required this.groups,
  });

  final String petName;
  final String? petAvatarUrl;
  final int completedCount;
  final int totalCount;
  final List<MilestoneGroup> groups;

  factory MilestoneList.fromJson(Map<String, dynamic> json) => MilestoneList(
        petName: (json['petName'] ?? '') as String,
        petAvatarUrl: json['petAvatarUrl'] as String?,
        completedCount: (json['completedCount'] ?? 0) as int,
        totalCount: (json['totalCount'] ?? 0) as int,
        groups: ((json['groups'] ?? const []) as List)
            .map((e) => MilestoneGroup.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}
