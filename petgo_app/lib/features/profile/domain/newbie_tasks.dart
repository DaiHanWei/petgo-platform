import 'package:flutter/widgets.dart';

/// 新手任务进度（Story 7.3 · FR-47）。后端只回稳定 `key` + 布尔完成态 + 计数；**显示标签一律客户端
/// 按 locale 本地化**（不渲染后端串）。全部完成 → 聚合里程碑 Lulus Pemula 解锁。
@immutable
class NewbieTasks {
  const NewbieTasks({
    required this.items,
    required this.completedCount,
    required this.total,
    required this.lulusPemulaUnlocked,
  });

  final List<NewbieTaskItem> items;
  final int completedCount;
  final int total;
  final bool lulusPemulaUnlocked;

  bool get allDone => total > 0 && completedCount >= total;

  factory NewbieTasks.fromJson(Map<String, dynamic> json) {
    final rawItems = (json['items'] as List? ?? const []);
    return NewbieTasks(
      items: rawItems
          .map((e) => NewbieTaskItem.fromJson(e as Map<String, dynamic>))
          .toList(growable: false),
      completedCount: (json['completedCount'] as num?)?.toInt() ?? 0,
      total: (json['total'] as num?)?.toInt() ?? rawItems.length,
      lulusPemulaUnlocked: json['lulusPemulaUnlocked'] as bool? ?? false,
    );
  }
}

/// 单个新手任务。[key] 为稳定标识（CREATE_PROFILE / FIRST_PHOTO / … / FIRST_HEALTH_RECORD）。
@immutable
class NewbieTaskItem {
  const NewbieTaskItem({required this.key, required this.done});

  final String key;
  final bool done;

  factory NewbieTaskItem.fromJson(Map<String, dynamic> json) => NewbieTaskItem(
        key: json['key'] as String? ?? '',
        done: json['done'] as bool? ?? false,
      );
}
