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
    this.hasPet = true,
    this.oneWeekDaysLeft = 0,
    this.replyGoPostId,
  });

  final List<NewbieTaskItem> items;
  final int completedCount;
  final int total;
  final bool lulusPemulaUnlocked;

  /// 账号下有 ACTIVE 或只读宠物（V1.5.0 onboarding-recall Story 3.1；缺省 true = 旧后端口径「有档案才有接口」）。
  /// false 时任务 ① 的按钮是「Buat profil」→ 建档。
  final bool hasPet;

  /// ⑦「注册满一周」未完成时剩余天数（≥ 1），完成为 0（缺省 0）。
  final int oneWeekDaysLeft;

  /// ⑥「回复一条评论」未完成且有人评论过我的帖子 → 最近一条被评论的帖子 id；否则 null（只显示提示、不放按钮）。
  final int? replyGoPostId;

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
      hasPet: json['hasPet'] as bool? ?? true,
      oneWeekDaysLeft: (json['oneWeekDaysLeft'] as num?)?.toInt() ?? 0,
      replyGoPostId: (json['replyGoPostId'] as num?)?.toInt(),
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
