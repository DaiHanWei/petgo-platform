/// 成长档案统计栏（Story 2.4 AC5）。「快乐时刻 X 条 · 问诊 X 次」+ 里程碑零态进度。
class ArchiveStats {
  const ArchiveStats({
    required this.happyMomentCount,
    required this.consultCount,
    required this.milestoneCompleted,
    required this.milestoneTotal,
    this.healthRecordCount = 0,
  });

  final int happyMomentCount;
  final int consultCount;

  /// 里程碑已完成数（mini-epic 未就绪 → 零态 0）。
  final int milestoneCompleted;

  /// 里程碑总数（按 pet_type：猫/狗 30，其他 15）。
  final int milestoneTotal;

  /// 结构化健康记录条数（V1.1.2）。**统计栏不展示它** —— 只供页头健康入口的副文案
  /// 按 0 / 非 0 分支（UI 稿 A4 近空态：还没有记录时副文案改「Belum ada catatan」）。
  final int healthRecordCount;

  factory ArchiveStats.fromJson(Map<String, dynamic> json) => ArchiveStats(
        happyMomentCount: (json['happyMomentCount'] ?? 0) as int,
        consultCount: (json['consultCount'] ?? 0) as int,
        milestoneCompleted: (json['milestoneCompleted'] ?? 0) as int,
        milestoneTotal: (json['milestoneTotal'] ?? 0) as int,
        healthRecordCount: (json['healthRecordCount'] ?? 0) as int,
      );
}
