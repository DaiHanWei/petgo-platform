import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_catchup.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_celebration.dart';

/// V1.3.0 批次 A · Story 1.5（L0）：补庆祝的判定逻辑（FR-111 · AD-A2 / AD-A3）。
///
/// 补弹这条链路上最容易出事的两点 —— **该补哪些** 与 **要不要抑制** —— 全在纯函数
/// [resolveCatchup] 里，所以能在 L0 钉死，不必等真机。真机那部分（视觉、四条路径互不打架）
/// 见 story 的 L2 清单。
void main() {
  MilestoneItem item(
    String code,
    MilestoneLevel level, {
    bool completed = true,
    DateTime? celebratedAt,
  }) =>
      MilestoneItem(
        code: code,
        title: code,
        level: level,
        trigger: MilestoneTrigger.systemAuto,
        completed: completed,
        completedAt: completed ? DateTime.utc(2026, 9, 1) : null,
        celebratedAt: celebratedAt,
      );

  final celebrated = DateTime.utc(2026, 9, 2);

  group('AD-A1.3 唯一判据：completed && celebratedAt == null', () {
    test('未完成的不算未庆祝（没完成过自然谈不上庆祝）', () {
      expect(item('C-S6', MilestoneLevel.s, completed: false).isUncelebrated, isFalse);
    });

    test('已完成且无 celebratedAt → 未庆祝', () {
      expect(item('C-S1', MilestoneLevel.s).isUncelebrated, isTrue);
    });

    test('已完成且有 celebratedAt → 已庆祝', () {
      expect(item('C-S1', MilestoneLevel.s, celebratedAt: celebrated).isUncelebrated, isFalse);
    });

    test('线格式缺 celebratedAt 这个 key 即为未庆祝（后端 NON_NULL 会省略它）', () {
      final parsed = MilestoneItem.fromJson({
        'code': 'C-S15',
        'title': '第一次收到点赞',
        'level': 'S',
        'triggerType': 'SYSTEM_AUTO',
        'completed': true,
        'completedAt': '2026-09-01T00:00:00Z',
        // 刻意没有 celebratedAt
      });
      expect(parsed.isUncelebrated, isTrue);
    });
  });

  group('AC2/AC3 补弹哪一条', () {
    test('全部已庆祝 → 不补弹', () {
      final r = resolveCatchup([
        item('C-S1', MilestoneLevel.s, celebratedAt: celebrated),
        item('C-M3', MilestoneLevel.m, celebratedAt: celebrated),
      ]);
      expect(r.isEmpty, isTrue);
      expect(r.codesToReport, isEmpty);
    });

    test('多条未庆祝 → 只弹级别最高的一条', () {
      final r = resolveCatchup([
        item('C-S1', MilestoneLevel.s),
        item('C-L2', MilestoneLevel.l),
        item('C-M3', MilestoneLevel.m),
      ]);
      expect(r.toCelebrate?.code, 'C-L2');
    });

    /// 🔴 AC2：回报的是**本次展示覆盖的全部条目**（含被 KOLEKSI 圆点带过的），不是只有弹的那条。
    test('回报列表包含本次展示覆盖的全部条目，不只是弹的那条', () {
      final r = resolveCatchup([
        item('C-S1', MilestoneLevel.s),
        item('C-L2', MilestoneLevel.l),
        item('C-M3', MilestoneLevel.m),
      ]);
      expect(r.codesToReport, containsAll(<String>['C-S1', 'C-L2', 'C-M3']));
      expect(r.codesToReport, hasLength(3));
    });

    /// 🔴 AC5（服务端侧）的客户端一半：**已庆祝的不进回报列表**。
    /// 回报列表越窄越安全 —— 服务端按这份列表置位，多带一条就多吞一条。
    test('已庆祝的条目不进回报列表', () {
      final r = resolveCatchup([
        item('C-S1', MilestoneLevel.s, celebratedAt: celebrated),
        item('C-M3', MilestoneLevel.m),
      ]);
      expect(r.codesToReport, ['C-M3']);
    });

    /// AC3：入口是用户主动点进来的，没有打扰问题，所以不按级别过滤。
    test('只有 S 级未庆祝 → 照样补弹，不按级别过滤', () {
      final r = resolveCatchup([
        item('C-M3', MilestoneLevel.m, celebratedAt: celebrated),
        item('C-S1', MilestoneLevel.s),
      ]);
      expect(r.isEmpty, isFalse);
      expect(r.toCelebrate?.code, 'C-S1');
    });

    test('未完成的条目不参与补弹，也不进回报列表', () {
      final r = resolveCatchup([
        item('C-S6', MilestoneLevel.s, completed: false),
        item('C-M3', MilestoneLevel.m),
      ]);
      expect(r.toCelebrate?.code, 'C-M3');
      expect(r.codesToReport, ['C-M3']);
    });
  });

  /// 🔴 **本类最重要的一组**（AC4 / AD-A2.3c）。
  ///
  /// 「去发布」的既定时序是「发布成功 → 回填打卡 → 弹庆祝 → 关发布 sheet → 跳里程碑列表页」，
  /// 而庆祝回报是**异步**的（失败静默）。列表页极可能在回报落库前就读到 `celebratedAt == null`
  /// —— 两秒内**连弹两次同一条**。抑制必须靠客户端在同一次导航内持有的这份集合，
  /// **不依赖回报是否已落库**。
  group('AC4 🔴「去发布」路径不连弹', () {
    test('刚庆祝过的那条被扣除 → 不再补弹', () {
      final r = resolveCatchup(
        [item('C-M3', MilestoneLevel.m)], // 服务端还没落库，仍是未庆祝
        justCelebrated: const {'C-M3'},
      );
      expect(r.isEmpty, isTrue,
          reason: '刚在发布流程里弹过这条，跳到列表页不得再弹一次');
    });

    test('抑制只针对刚庆祝过的那条，其余仍照常补弹', () {
      final r = resolveCatchup(
        [item('C-M3', MilestoneLevel.m), item('C-S15', MilestoneLevel.s)],
        justCelebrated: const {'C-M3'},
      );
      expect(r.toCelebrate?.code, 'C-S15');
      expect(r.codesToReport, ['C-S15'],
          reason: '被抑制的那条不该再回报一次（它在上游已经回报过了）');
    });

    test('抑制集合为空（正常进入列表页）时行为不变', () {
      final r = resolveCatchup([item('C-M3', MilestoneLevel.m)]);
      expect(r.toCelebrate?.code, 'C-M3');
    });

    /// 抑制是**纯客户端**的：入参里没有任何「回报是否成功 / 是否已落库」的信号。
    /// 这条断言把该设计钉住 —— 若日后有人给 resolveCatchup 加一个 `reportLanded` 之类的参数，
    /// 就等于把抑制重新绑回那条会失败的异步写上。
    test('抑制不依赖回报状态：判定入参里没有任何落库信号', () {
      final withPending = resolveCatchup(
        [item('C-M3', MilestoneLevel.m)],
        justCelebrated: const {'C-M3'},
      );
      expect(withPending.isEmpty, isTrue);
    });
  });

  group('AC6 埋点 path 值域', () {
    test('恰好三值：instant / catchup / revisit', () {
      expect(
        MilestoneCelebrationPath.values.map((e) => e.wire).toList(),
        ['instant', 'catchup', 'revisit'],
      );
    });
  });
}
