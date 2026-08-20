import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart' as ph;

import '../../../core/analytics/analytics.dart';

/// 系统通知权限状态快照（Story 8.1 / 埋点 E-21 `push_permission_state_reported`）。
///
/// **为什么这个小东西是 P0 前置**：FR-85 做了四个触发点去引导用户开通知，但 E-19（提示曝光）
/// 与 E-20（用户响应）**只能解释过程、不能证明结果** —— 「提示弹了 3 万次、各点响应分布如何」
/// 都回答不了「净授权率涨没涨」。只有本事件的**趋势**能回答，它是 FR-85 唯一的裁决指标
/// （架构 AD-16 Rule 6、埋点清单 §0.3）。
///
/// 🔴 **必须比四个触发点先上线**：晚一个版本不是"晚点拿到数据"，而是**改版前基线永久不存在**。
/// 埋点清单 §0.3 记着上一次的教训 —— Tab 改版因缺基线，五项核心指标里三项直接作废。
///
/// **本类刻意只做一件事**：读一次当前系统通知开关，上报一次。四个触发点的引导、
/// AD-14 的四个标记键，全部归 Story 8.2 / 8.3，本类一个都不碰。
class PushPermissionStateReporter {
  PushPermissionStateReporter._();

  /// 「本次启动是否已报过」。
  ///
  /// 🔴 **进程内布尔，绝不落盘。** 冷启动天然重置它，这正是 E-21「每次冷启动一次」要的语义。
  /// 若落 prefs，语义会变成「这台设备永远只报一次」—— 而这个指标要的恰恰是**趋势**，
  /// 单个快照没有任何用。
  static bool _reportedThisLaunch = false;

  /// 上报本次冷启动的通知权限状态。**一次冷启动恰好一次。**
  ///
  /// 调用时机必须在本次启动的**通知权限流程落定之后**（见 `main.dart`）：报"申请之前"的状态
  /// 会让首启永远是 `false`，把授权率系统性低估。
  ///
  /// ⚠️ 若本次启动因 ATT 未落定而**推迟**了权限申请（既有链路），仍照实上报**当前**状态 ——
  /// 那确实是一次真实观测。跳过会让分母缺失，且缺失**非随机**（只缺 iPad 兼容模式那类设备），
  /// 趋势会被带偏。
  ///
  /// [isGranted] 供测试注入；生产走 `permission_handler`，与
  /// `PushPermissionBootstrap` 同一口径。
  static Future<void> reportOnColdStart({
    Future<bool> Function()? isGranted,
  }) async {
    if (_reportedThisLaunch) return;
    try {
      final granted = await (isGranted ?? _isGranted)();
      // ⚠️ 属性只给 granted。埋点清单里加粗的属性是判读的必要条件，
      //    多加属性只会让口径变模糊（而事件一旦发版，形状就难改了）。
      await Analytics.capture('push_permission_state_reported', {'granted': granted});
      _reportedThisLaunch = true;
    } catch (e) {
      // 拿不到状态只是少一条数据；启动路径上那四步的时序是两次审核拒信换来的，
      // 绝不能让一个埋点把异常抛进去。
      //
      // ⚠️ 刻意**不**置位一次性闸：一次偶发失败不该让本次启动彻底没有数据 ——
      //    调用方（前台补弹链路落定时）还有机会补上。
      debugPrint('[PushPermissionState] report failed: $e');
    }
  }

  /// 与 `PushPermissionBootstrap._isGranted` 同一口径。
  ///
  /// ⚠️ **判读注记（给看数的人）**：Android 13 以下没有「通知权限」这个概念，
  /// 这里反映的是「用户有没有在系统设置里关掉通知」。所以低版本 Android 上 `true` 偏多是正常的，
  /// **跨平台的绝对值不要直接比**；FR-85 要看的是同一群体的**趋势**。
  static Future<bool> _isGranted() async =>
      (await ph.Permission.notification.status).isGranted;

  @visibleForTesting
  static void resetForTest() => _reportedThisLaunch = false;
}
