/// 成长档案统计栏（Story 2.4 AC5）。「快乐时刻 X 条 · 问诊 X 次」+ 里程碑零态进度。
class ArchiveStats {
  const ArchiveStats({
    required this.happyMomentCount,
    required this.consultCount,
    required this.milestoneCompleted,
    required this.milestoneTotal,
  });

  final int happyMomentCount;
  final int consultCount;

  /// 里程碑已完成数（mini-epic 未就绪 → 零态 0）。
  final int milestoneCompleted;

  /// 里程碑总数（按 pet_type：猫/狗 30，其他 15）。
  final int milestoneTotal;

  factory ArchiveStats.fromJson(Map<String, dynamic> json) => ArchiveStats(
        happyMomentCount: (json['happyMomentCount'] ?? 0) as int,
        consultCount: (json['consultCount'] ?? 0) as int,
        milestoneCompleted: (json['milestoneCompleted'] ?? 0) as int,
        milestoneTotal: (json['milestoneTotal'] ?? 0) as int,
      );
}
