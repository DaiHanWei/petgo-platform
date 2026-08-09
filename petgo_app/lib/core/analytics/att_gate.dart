import 'dart:async';
import 'dart:io' show Platform;

import 'package:app_tracking_transparency/app_tracking_transparency.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

/// ATT（App Tracking Transparency）授权门——**独立于任何三方 SDK**。
///
/// App Store 审核拒信（2026-08-06，Guideline 2.1，1.1.0(7)）根因修复：
/// 1. 原实现把 ATT 请求写在 `AppsFlyerClient.start()` 里、且被 `_initialized`
///    短路——AppsFlyer initSdk 一旦失败，合规必需的系统弹窗永远不会请求。
///    现在解耦：本类只管 ATT，AppsFlyer 成败与弹窗互不影响。
/// 2. iOS 15+ 要求 app 处于 **active** 状态时请求，首帧回调时 scene 可能仍是
///    inactive，系统会**静默不弹**。现在先等 `AppLifecycleState.resumed`
///    （已 resumed 则直通，最多等 3s 防挂起），再延迟 1s 兜底后请求。
///
/// TODO(产品/合规)：iOS 上应先展示一屏价值说明页再弹 ATT（提升同意率，
/// Apple 审核友好），待产品出文案后接入（交付文档 §3.5 待确认项 3/4）。
class AttGate {
  AttGate._();

  /// 单飞：前台补弹与冷启动请求可能并发（作答后 resumed 触发新一轮，而旧一轮的 15s
  /// 轮询还没退出）——同一时刻只允许一个请求周期，后来者直接返回「未落定」。
  static bool _inFlight = false;

  /// iOS：等前台活跃后请求 ATT 授权（状态非 notDetermined 则跳过）。
  /// Android/异常一律静默返回——绝不抛错、绝不无限挂起，调用方可放心 await。
  ///
  /// 返回值 = **ATT 是否已真正落定**（PR#34 finding #14 + 2026-08-09 iPad 拒信收紧）：
  /// - `true`：已作答 / 系统禁止询问 / 本就无需询问——调用方可以安全地接着弹下一个系统弹窗；
  /// - `false`：仍是 notDetermined（弹窗开着没作答，**或请求被系统吞掉**）——调用方
  ///   **不得**弹任何系统弹窗（会盖住/挤掉 ATT，Guideline 2.1 两封拒信的成因），
  ///   等 [installForegroundRetry] 的下一次前台补弹。
  ///
  /// ⚠️ 2026-08-09 拒信（iPad Air/iPadOS 26.6，iPhone-only App 跑兼容模式）教训：
  /// 冷启动这一次机会的重试窗口（~11s）在 iPad 上不够——请求被吞后本会话内再无请求时机，
  /// 审核员全新安装启动一次就永远看不到 ATT 弹窗。故不再把「三次都被吞」当安全放行，
  /// 并配合 [installForegroundRetry] 在每次回前台时补弹（系统保证弹窗只真正出现一次：
  /// 仅 notDetermined 状态有 UI，已作答后调用是无 UI 的空转）。
  static Future<bool> requestIfNeeded() async {
    if (kIsWeb || !Platform.isIOS) return true;
    if (_inFlight) return false; // 已有周期在跑，本轮不重入（结果由在跑周期负责）
    _inFlight = true;
    try {
      await _waitUntilResumed();
      // 刚转 active 立即请求仍有概率不弹（iOS 已知行为），延迟 1s 兜底。
      await Future<void>.delayed(const Duration(seconds: 1));
      // 诊断（长期保留）：ATT 不弹窗时这是唯一可判读的信号——
      // denied/restricted 多半是「设置→隐私→跟踪」总开关关闭或旧安装遗留状态，非 App 缺陷。
      debugPrint('[ATT] status=${await AppTrackingTransparency.trackingAuthorizationStatus}');
      return await _requestWithRetry();
    } catch (e) {
      debugPrint('[ATT] request failed: $e'); // 失败不阻断启动链路
      return true; // 异常 ⇒ 弹窗大概率没出现，不因 ATT 插件故障饿死通知权限
    } finally {
      _inFlight = false;
    }
  }

  /// 前台补弹（2026-08-09 iPad 拒信根治）：每次 App 回到前台、ATT 仍未落定就再请求。
  ///
  /// [onSettled] 在**某一轮请求真正落定**后回调（最多一次语义由调用方自持）——
  /// 用于把被推迟的通知权限接回来。Android/Web 直接空转不挂监听。
  static void installForegroundRetry({required Future<void> Function() onSettled}) {
    if (kIsWeb || !Platform.isIOS) return;
    WidgetsBinding.instance.addObserver(_ForegroundRetryObserver(onSettled));
  }

  /// 请求 ATT，**弹窗没真正出现就退避重试**（最多 3 次）。返回语义同 [requestIfNeeded]。
  ///
  /// 🔴 为什么需要（L2 实测 2026-08-07，iPhone / iOS 26.5）：**全新安装的首次冷启动不弹、
  /// 第二次启动才弹**。首启时 scene 尚未被系统认定为 active（iOS 26 + SceneDelegate 下
  /// Flutter 报出的 `resumed` 早于系统的 active），此时的请求被**静默吞掉**、状态仍留在
  /// notDetermined。审核员通常只跑首次启动 ⇒ 正是 2026-08-06 拒信（Guideline 2.1）的成因。
  ///
  /// 判据用「App 是否被系统弹窗夺焦」：ATT 弹窗一旦显示，App 必然转 inactive。
  /// 没夺焦 ⇒ 这次请求被吞了 ⇒ 退避重试；夺焦了 ⇒ 等用户作答完再返回（保证后续的
  /// 通知权限弹窗不会挤掉它）。
  static Future<bool> _requestWithRetry() async {
    for (var attempt = 0; attempt < 3; attempt++) {
      final status = await AppTrackingTransparency.trackingAuthorizationStatus;
      if (status != TrackingStatus.notDetermined) return true; // 已作答/系统禁止，无需再问
      final shown = _waitUntilInactive(const Duration(milliseconds: 1500));
      await AppTrackingTransparency.requestTrackingAuthorization();
      if (await shown) {
        // 弹窗确实出现了：等用户作答落定。超时（15s 未作答）⇒ 弹窗还开着，返回 false
        // 告知调用方绝不能再叠系统弹窗（PR#34 finding #14）。
        return _waitUntilDetermined();
      }
      // 被系统吞了：退避（2s / 4s）后重试，给 scene 更多时间真正 active。
      await Future<void>.delayed(Duration(seconds: 2 * (attempt + 1)));
    }
    debugPrint('[ATT] prompt never appeared after retries — will retry on next foreground');
    // 2026-08-09 iPad 拒信收紧：三次都被吞 ≠ 安全。iPad 兼容模式下弹窗可能「已在屏上但
    // 失焦判据没探到」（此时放行通知权限就是盖弹窗），也可能真被吞（那就等前台补弹）。
    // 两种情形的正确动作一致：按未落定处理，通知权限一并推迟。
    return false;
  }

  /// 监听 App 是否在 [within] 内失去前台焦点（=系统弹窗盖上来了）。
  static Future<bool> _waitUntilInactive(Duration within) {
    final binding = WidgetsBinding.instance;
    final completer = Completer<bool>();
    late final _LifecycleProbe probe;
    probe = _LifecycleProbe((state) {
      if (state != AppLifecycleState.resumed && !completer.isCompleted) {
        binding.removeObserver(probe);
        completer.complete(true);
      }
    });
    binding.addObserver(probe);
    return completer.future.timeout(within, onTimeout: () {
      binding.removeObserver(probe);
      return false;
    });
  }

  /// 轮询到 ATT 状态不再是 notDetermined（最多 15s）。返回是否真正落定。
  ///
  /// 🔴 为什么需要（L2 实测 2026-08-07，iPhone / iOS 26.5）：
  /// `requestTrackingAuthorization()` 的 Future 在新系统上**会在用户作答前就 resolve**，
  /// 导致调用方以为 ATT 已结束、立刻请求通知权限 ⇒ 通知弹窗盖住 ATT 弹窗，
  /// 用户根本没机会对跟踪作答（ATT 静默留在 notDetermined）——正是 2026-08-06
  /// App Store 拒信（Guideline 2.1）的同款表现，只是成因换成了弹窗互挤。
  /// 状态查询是唯一可信信号，故以轮询兜底，不依赖插件 Future 的时序语义。
  ///
  /// 超时（用户 15s 一直不作答）返回 `false`——弹窗仍开着，调用方据此推迟后续系统弹窗
  ///（PR#34 finding #14：旧实现超时也静默正常返回，破坏「返回即已落定」契约）。
  static Future<bool> _waitUntilDetermined() async {
    final deadline = DateTime.now().add(const Duration(seconds: 15));
    while (DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 300));
      final s = await AppTrackingTransparency.trackingAuthorizationStatus;
      if (s != TrackingStatus.notDetermined) return true;
    }
    return false;
  }

  /// 等 app 进入 resumed；已是 resumed 直通。3s 超时兜底（生命周期事件缺失时不挂死）。
  static Future<void> _waitUntilResumed() async {
    final binding = WidgetsBinding.instance;
    if (binding.lifecycleState == AppLifecycleState.resumed) return;
    final completer = Completer<void>();
    late final _ResumeObserver observer;
    observer = _ResumeObserver(() {
      binding.removeObserver(observer);
      if (!completer.isCompleted) completer.complete();
    });
    binding.addObserver(observer);
    await completer.future.timeout(const Duration(seconds: 3), onTimeout: () {
      binding.removeObserver(observer);
    });
  }
}

class _ResumeObserver with WidgetsBindingObserver {
  _ResumeObserver(this.onResumed);

  final VoidCallback onResumed;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) onResumed();
  }
}

/// 通用生命周期探针（ATT 用它判断系统弹窗是否真的盖上来了）。
class _LifecycleProbe with WidgetsBindingObserver {
  _LifecycleProbe(this.onState);

  final void Function(AppLifecycleState) onState;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) => onState(state);
}

/// 前台补弹监听（2026-08-09 iPad 拒信）：resumed 且 ATT 仍 notDetermined → 再走一轮请求；
/// 某轮落定后回调 [onSettled] 并自摘（不再监听）。
///
/// 注意与请求周期内部的 `_waitUntilInactive`/`_waitUntilResumed` 探针互不干扰——
/// 周期内产生的 resumed（用户答完 ATT 弹窗回前台）会触发本监听，但此时要么状态已落定
/// （走 onSettled 收尾），要么 [AttGate._inFlight] 单飞挡掉重入。
class _ForegroundRetryObserver with WidgetsBindingObserver {
  _ForegroundRetryObserver(this.onSettled);

  final Future<void> Function() onSettled;
  bool _settledFired = false;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed || _settledFired) return;
    Future<void>(() async {
      try {
        final status = await AppTrackingTransparency.trackingAuthorizationStatus;
        if (status != TrackingStatus.notDetermined) {
          // 已落定（可能是上一轮弹窗刚被作答）：收尾一次并自摘。
          _fireSettled();
          return;
        }
        final settled = await AttGate.requestIfNeeded();
        if (settled) _fireSettled();
      } catch (e) {
        debugPrint('[ATT] foreground retry failed: $e');
      }
    });
  }

  void _fireSettled() {
    if (_settledFired) return;
    _settledFired = true;
    WidgetsBinding.instance.removeObserver(this);
    onSettled();
  }
}
