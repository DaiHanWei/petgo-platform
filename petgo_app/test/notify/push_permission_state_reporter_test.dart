import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/notify/domain/push_permission_state_reporter.dart';

/// Story 8.1：系统通知权限状态快照（E-21 `push_permission_state_reported`）。
///
/// 这个事件的**趋势**是 FR-85 唯一的裁决指标 —— 四个触发点做完之后，
/// 「授权率到底涨没涨」只能靠它回答（E-19/E-20 只能解释过程）。
/// 所以本文件钉的不是"能不能上报"，而是**它作为一个指标能不能被正确判读**：
/// 一次冷启动恰好一次（分母才准）、属性齐（缺了结论就做不出来）、
/// 失败不拖垮启动（拿不到状态只是少一条数据，不能让 App 起不来）。
void main() {
  setUp(PushPermissionStateReporter.resetForTest);
  tearDown(() {
    Analytics.debugCaptureSink = null;
    PushPermissionStateReporter.resetForTest();
  });

  /// 断言看的是**端上真正发出的形态** —— `debugCaptureSink` 挂在属性净化之后
  /// （Story 6.1 建立的观察点）。
  List<(String, Map<String, Object>?)> record() {
    final seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
    return seen;
  }

  group('AC1 / AC2 · 事件名与属性', () {
    test('权限已开 → 报一次 granted=true', () async {
      final seen = record();

      await PushPermissionStateReporter.reportOnColdStart(
        isGranted: () async => true,
      );

      expect(seen, hasLength(1));
      // 🔴 名字写错就锁死：这是 push_permission_state_reported 的首次落地，
      //    一旦发版改名会切断历史序列（旧名 push_permission_state_snapshot 已作废）。
      expect(seen.first.$1, 'push_permission_state_reported');
      expect(seen.first.$2?['granted'], true);
    });

    test('权限已关 → 报一次 granted=false', () async {
      final seen = record();

      await PushPermissionStateReporter.reportOnColdStart(
        isGranted: () async => false,
      );

      expect(seen, hasLength(1));
      expect(seen.first.$2?['granted'], false);
    });

    test('属性只有 granted —— 多余属性会让判读口径变模糊', () async {
      final seen = record();

      await PushPermissionStateReporter.reportOnColdStart(
        isGranted: () async => true,
      );

      expect(seen.first.$2?.keys, ['granted']);
    });
  });

  group('AC1 · 一次冷启动恰好一次', () {
    /// 🛡 这条是**指标准确性**的护栏，不是性能优化。
    /// 这个指标按启动数算授权率；若 resume / 切 Tab 也报，重度用户会被反复计入，
    /// 授权率被拉向他们的个人状态 —— 趋势就失真了，而趋势正是唯一裁决指标。
    test('同一进程内重复调用只报一次', () async {
      final seen = record();

      await PushPermissionStateReporter.reportOnColdStart(isGranted: () async => true);
      await PushPermissionStateReporter.reportOnColdStart(isGranted: () async => false);
      await PushPermissionStateReporter.reportOnColdStart(isGranted: () async => true);

      expect(seen, hasLength(1));
      expect(seen.first.$2?['granted'], true, reason: '报的应是第一次的状态');
    });

    /// ⚠️ 一次性闸必须是**进程内**的：冷启动天然重置它，正是要的语义。
    /// 若落 prefs，语义会变成「这台设备永远只报一次」，趋势就永远只有一个点。
    test('新进程（模拟冷启动）会重新报', () async {
      final seen = record();

      await PushPermissionStateReporter.reportOnColdStart(isGranted: () async => true);
      PushPermissionStateReporter.resetForTest(); // 模拟下一次冷启动
      await PushPermissionStateReporter.reportOnColdStart(isGranted: () async => false);

      expect(seen, hasLength(2));
      expect(seen.last.$2?['granted'], false);
    });
  });

  group('AC4 · 绝不拖垮启动', () {
    /// 拿不到权限状态只是少一条数据；让启动挂掉是完全不成比例的代价。
    /// 启动路径上那四步的时序是两次审核拒信换来的，这里绝不能抛出去干扰它。
    test('读取权限抛异常 → 不向上抛、也不报事件', () async {
      final seen = record();

      await expectLater(
        PushPermissionStateReporter.reportOnColdStart(
          isGranted: () async => throw StateError('插件在测试环境没有平台实现'),
        ),
        completes,
      );

      expect(seen, isEmpty);
    });

    /// 异常之后一次性闸不应被"用掉" —— 否则一次偶发失败会让这次启动彻底没有数据，
    /// 而调用方（前台补弹链路落定时）本还有机会补上。
    test('异常不消耗一次性闸，之后仍可成功上报一次', () async {
      final seen = record();

      await PushPermissionStateReporter.reportOnColdStart(
        isGranted: () async => throw StateError('boom'),
      );
      await PushPermissionStateReporter.reportOnColdStart(isGranted: () async => true);

      expect(seen, hasLength(1));
      expect(seen.first.$2?['granted'], true);
    });
  });
}
