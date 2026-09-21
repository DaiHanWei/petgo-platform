/// 成长档案统计栏（Story 2.4 AC5）。「快乐时刻 X 条 · 问诊 X 次」+ 里程碑零态进度。
class ArchiveStats {
  const ArchiveStats({
    required this.happyMomentCount,
    required this.consultCount,
    required this.milestoneCompleted,
    required this.milestoneTotal,
    this.healthRecordCount = 0,
    this.milestoneUncelebrated = 0,
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

  /// 「已完成且未庆祝」的里程碑数（V1.3.0 Story 1.5 · FR-111 · AD-A2.2）——
  /// 档案 Tab 里程碑进度条上那枚红色角标就用这个数。
  ///
  /// 由档案头部**本来就要发的那个请求**顺带下发，不为角标多发一次请求。
  /// ⚠️ 档案 Tab **只显示角标、永不弹全屏庆祝**（AD-A2.1）：补弹只挂里程碑列表页，
  /// 否则用户从档案 Tab 点进列表页会被连弹两次。
  final int milestoneUncelebrated;

  factory ArchiveStats.fromJson(Map<String, dynamic> json) => ArchiveStats(
        happyMomentCount: (json['happyMomentCount'] ?? 0) as int,
        consultCount: (json['consultCount'] ?? 0) as int,
        milestoneCompleted: (json['milestoneCompleted'] ?? 0) as int,
        milestoneTotal: (json['milestoneTotal'] ?? 0) as int,
        healthRecordCount: (json['healthRecordCount'] ?? 0) as int,
        milestoneUncelebrated: (json['milestoneUncelebrated'] ?? 0) as int,
      );
}
