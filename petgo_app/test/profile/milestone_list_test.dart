import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/newbie_task_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/newbie_tasks.dart';
import 'package:tailtopia/features/profile/presentation/milestone_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget（Story 8.2 · FR-42）：L/M/S 分区 + 进度 + 徽章彩色/灰锁 + 点击弹层分流 + 失败重试。
///
/// ⚠️ V1.3.0 Story 1.5 起，**已完成的条目必须带 `celebratedAt`**，否则本页进入时会自动补弹
/// 庆祝（AC2 的正常行为），把这些用例的 `pumpAndSettle` 卡在庆祝动效上。
/// 这也正是线上的真实形态 —— 1.4 的迁移把存量完成行的 `celebrated_at` 全部回填了。
/// 想验补弹本身请看下面 `_uncelebrated()` 那一组。
final DateTime _celebrated = DateTime.utc(2026, 6, 5);

MilestoneList _sample() => MilestoneList(
      petName: 'Momo',
      completedCount: 2,
      totalCount: 5,
      groups: [
        const MilestoneGroup(level: MilestoneLevel.l, completedCount: 0, totalCount: 1, items: [
          MilestoneItem(
              code: 'C-L1', title: '第一个生日', level: MilestoneLevel.l,
              trigger: MilestoneTrigger.pushPublish, completed: false),
        ]),
        // totalCount 2>completedCount 1：M 级「未全完成」，默认「Belum Semua Selesai」筛选下可见
        // （0711 新增分级筛选：默认隐藏已全完成的级别）。
        MilestoneGroup(level: MilestoneLevel.m, completedCount: 1, totalCount: 2, items: [
          MilestoneItem(
              code: 'C-M8', title: '陪伴满 30 天', level: MilestoneLevel.m,
              trigger: MilestoneTrigger.systemAuto, completed: true,
              celebratedAt: _celebrated),
        ]),
        MilestoneGroup(level: MilestoneLevel.s, completedCount: 1, totalCount: 3, items: [
          MilestoneItem(
              code: 'C-S1', title: '宠物档案创建完成', level: MilestoneLevel.s,
              trigger: MilestoneTrigger.systemAuto, completed: true,
              celebratedAt: _celebrated),
          const MilestoneItem(
              code: 'C-S6', title: '第一次洗澡', level: MilestoneLevel.s,
              trigger: MilestoneTrigger.userCheckin, completed: false),
        ]),
      ],
    );

Widget _wrap({MilestoneList? data, Object? error, Set<String> justCelebrated = const {}}) =>
    ProviderScope(
      overrides: [
        milestoneListProvider.overrideWith((ref) async {
          if (error != null) throw error;
          return data ?? _sample();
        }),
        // 新手卡：这些用例不关心，用达成态渲染紧凑横幅，避免真网络调用干扰。
        newbieTasksProvider.overrideWith((ref) async => const NewbieTasks(
              items: [], completedCount: 6, total: 6, lulusPemulaUnlocked: true)),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: MilestoneListPage(justCelebrated: justCelebrated),
      ),
    );

/// 有两条**已完成但未庆祝**的数据（1.5 补弹的输入形态）：M 级 C-M8 + S 级 C-S1。
MilestoneList _uncelebrated() => const MilestoneList(
      petName: 'Momo',
      completedCount: 2,
      totalCount: 5,
      groups: [
        MilestoneGroup(level: MilestoneLevel.m, completedCount: 1, totalCount: 2, items: [
          MilestoneItem(
              code: 'C-M8', title: '陪伴满 30 天', level: MilestoneLevel.m,
              trigger: MilestoneTrigger.systemAuto, completed: true),
        ]),
        MilestoneGroup(level: MilestoneLevel.s, completedCount: 1, totalCount: 3, items: [
          MilestoneItem(
              code: 'C-S1', title: '宠物档案创建完成', level: MilestoneLevel.s,
              trigger: MilestoneTrigger.systemAuto, completed: true),
        ]),
      ],
    );

void main() {
  testWidgets('header + 三级分区 + 各级进度渲染', (tester) async {
    await tester.binding.setSurfaceSize(const Size(500, 2600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('milestoneHeader')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneSection_l')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneSection_m')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneSection_s')), findsOneWidget);
    expect(find.text('Momo'), findsOneWidget);
    expect(find.text('1/3'), findsOneWidget); // S 级进度
  });

  testWidgets('徽章彩色（已完成）/灰锁（未完成）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(500, 2600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('milestoneBadge_C-S1')), findsOneWidget);
    // 已完成 → 奖杯图标；未完成 → 锁图标。
    expect(find.byIcon(Icons.emoji_events_rounded), findsWidgets);
    expect(find.byIcon(Icons.lock_outline_rounded), findsWidgets);
  });

  testWidgets('点击未完成非打卡徽章 → P-33b 只读说明（无打卡按钮）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(500, 2600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    // C-L1：未完成 + 推送发布类（非用户打卡）→ 弹 P-33b 详情，但不出打卡两入口。
    await tester.tap(find.byKey(const ValueKey('milestoneBadge_C-L1')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('milestoneCheckedIn')), findsNothing);
    expect(find.byKey(const ValueKey('milestoneGoPublish')), findsNothing);
  });

  testWidgets('点击已完成徽章 → P-35 解锁庆祝（而非 P-33b 详情）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(500, 2600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    // C-S1：已完成 → 重温 P-35 统一庆祝。（顶部新手卡下移 S 分区，先滚入可视区。）
    await tester.ensureVisible(find.byKey(const ValueKey('milestoneBadge_C-S1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('milestoneBadge_C-S1')));
    await tester.pump(); // 打开
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsOneWidget);
    // 不应是 P-33b 详情（无打卡按钮）。
    expect(find.byKey(const ValueKey('milestoneCheckedIn')), findsNothing);
  });

  testWidgets('点击用户打卡未完成徽章 → 「已打卡 / 去发布」两入口', (tester) async {
    await tester.binding.setSurfaceSize(const Size(500, 2600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const ValueKey('milestoneBadge_C-S6')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('milestoneBadge_C-S6')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('milestoneCheckedIn')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneGoPublish')), findsOneWidget);
  });

  testWidgets('加载失败 → F13 失败态 + 重试入口', (tester) async {
    await tester.pumpWidget(_wrap(error: Exception('boom')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('milestoneRetry')), findsOneWidget);
  });
  // ===== V1.3.0 Story 1.5：进页面补弹（AC2/AC4）=====
  //
  // 庆祝页有持续动效，**不能用 pumpAndSettle**（会超时）；列表里显示的又是客户端本地化标题
  // （不是后端 title 字段），按文字断言既脆又跟 locale 绑死。
  // 所以直接看 `milestone_celebration_shown` 这条埋点：它就是"弹了没、弹的哪条、走的哪条路径"
  // 的权威信号，与语言无关。

  List<Map<String, Object>> celebrationEvents(List<MapEntry<String, Map<String, Object>?>> seen) =>
      [for (final e in seen) if (e.key == 'milestone_celebration_shown') e.value ?? {}];

  Future<List<Map<String, Object>>> pumpAndCollect(
    WidgetTester tester,
    Widget app,
  ) async {
    final seen = <MapEntry<String, Map<String, Object>?>>[];
    Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
    addTearDown(() => Analytics.debugCaptureSink = null);
    await tester.binding.setSurfaceSize(const Size(500, 2600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(app);
    await tester.pump(); // provider 落数据
    await tester.pump(); // postFrameCallback 触发
    await tester.pump(const Duration(milliseconds: 400)); // 庆祝弹层进场
    return celebrationEvents(seen);
  }

  testWidgets('AC2 有未庆祝条目 → 进页面自动补弹一次，取级别最高的那条', (tester) async {
    final events = await pumpAndCollect(tester, _wrap(data: _uncelebrated()));

    expect(events, hasLength(1), reason: '只补弹一次，不是每条都弹');
    expect(events.single['code'], 'C-M8', reason: 'M 级高于 S 级，弹 C-M8 不弹 C-S1');
    expect(events.single['path'], 'catchup');
  });

  /// 🔴 AC4：「去发布」路径带过来的「刚庆祝过」集合必须挡住连弹。
  ///
  /// 这里 C-M8 在数据里**仍是未庆祝**（回报还没落库，正是异步回报的真实窗口），
  /// 全靠 justCelebrated 抑制 —— 断言的正是「不依赖回报是否已落库」。
  testWidgets('AC4 刚庆祝过的那条被抑制 → 不连弹（不依赖回报落库）', (tester) async {
    final events = await pumpAndCollect(
      tester,
      _wrap(
        data: const MilestoneList(
          petName: 'Momo',
          completedCount: 1,
          totalCount: 5,
          groups: [
            MilestoneGroup(level: MilestoneLevel.m, completedCount: 1, totalCount: 2, items: [
              MilestoneItem(
                  code: 'C-M8', title: '陪伴满 30 天', level: MilestoneLevel.m,
                  trigger: MilestoneTrigger.systemAuto, completed: true),
            ]),
          ],
        ),
        justCelebrated: const {'C-M8'},
      ),
    );

    expect(events, isEmpty,
        reason: '刚在发布流程里弹过这条，跳到列表页两秒内不得再弹一次');
  });

  testWidgets('AC4 抑制只针对那一条：同批其余未庆祝的照常补弹', (tester) async {
    final events = await pumpAndCollect(
      tester,
      _wrap(data: _uncelebrated(), justCelebrated: const {'C-M8'}),
    );

    expect(events, hasLength(1));
    expect(events.single['code'], 'C-S1', reason: 'C-M8 被抑制后，轮到 S 级那条');
  });

  testWidgets('全部已庆祝 → 进页面不补弹（线上回填后的正常形态）', (tester) async {
    final events = await pumpAndCollect(tester, _wrap());

    expect(events, isEmpty);
  });
}
