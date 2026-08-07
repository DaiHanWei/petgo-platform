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

  /// iOS：等前台活跃后请求 ATT 授权（状态非 notDetermined 则跳过）。
  /// Android/异常一律静默返回——绝不抛错、绝不无限挂起，调用方可放心 await。
  ///
  /// **返回即代表 ATT 已落定**（用户作答完毕或本就无需询问）——这是对调用方的硬保证，
  /// 后续的系统弹窗（通知权限）依赖它串行排队，见 [_waitUntilDetermined]。
  static Future<void> requestIfNeeded() async {
    if (kIsWeb || !Platform.isIOS) return;
    try {
      await _waitUntilResumed();
      // 刚转 active 立即请求仍有概率不弹（iOS 已知行为），延迟 1s 兜底。
      await Future<void>.delayed(const Duration(seconds: 1));
      final status = await AppTrackingTransparency.trackingAuthorizationStatus;
      if (status == TrackingStatus.notDetermined) {
        await AppTrackingTransparency.requestTrackingAuthorization();
        await _waitUntilDetermined();
      }
    } catch (e) {
      debugPrint('[ATT] request failed: $e'); // 失败不阻断启动链路
    }
  }

  /// 轮询到 ATT 状态不再是 notDetermined（最多 15s）。
  ///
  /// 🔴 为什么需要（L2 实测 2026-08-07，iPhone / iOS 26.5）：
  /// `requestTrackingAuthorization()` 的 Future 在新系统上**会在用户作答前就 resolve**，
  /// 导致调用方以为 ATT 已结束、立刻请求通知权限 ⇒ 通知弹窗盖住 ATT 弹窗，
  /// 用户根本没机会对跟踪作答（ATT 静默留在 notDetermined）——正是 2026-08-06
  /// App Store 拒信（Guideline 2.1）的同款表现，只是成因换成了弹窗互挤。
  /// 状态查询是唯一可信信号，故以轮询兜底，不依赖插件 Future 的时序语义。
  ///
  /// 超时（用户一直不作答）也照常返回，不阻断启动链路。
  static Future<void> _waitUntilDetermined() async {
    final deadline = DateTime.now().add(const Duration(seconds: 15));
    while (DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 300));
      final s = await AppTrackingTransparency.trackingAuthorizationStatus;
      if (s != TrackingStatus.notDetermined) return;
    }
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
