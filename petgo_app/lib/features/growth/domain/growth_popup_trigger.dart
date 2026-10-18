import 'package:flutter/foundation.dart';

/// 成长弹窗拉取触发点（V1.4.0 ① Story 2.3 · AD-11）。
enum GrowthPullReason { coldStart, resumed, afterAction, pushClicked }

/// 进程内触发器（不依赖 Riverpod）：Dio 拦截器、支付成功点、推送点击、回前台都只调它，
/// 由 `GrowthPopupCoordinator` 在 attach 时绑定；未绑定（测试 / 冷启动早期）时调用是空操作。
class GrowthPopupTrigger {
  GrowthPopupTrigger._();

  static final GrowthPopupTrigger instance = GrowthPopupTrigger._();

  void Function(GrowthPullReason reason)? _sink;

  /// 未绑定时点了升级推送（被杀进程冷启动：推送回调早于 AppShell 首帧）→ 记下，绑定时补发一次。
  bool _pendingPushClick = false;

  void bind(void Function(GrowthPullReason reason) sink) {
    _sink = sink;
    if (_pendingPushClick) {
      _pendingPushClick = false;
      sink(GrowthPullReason.pushClicked);
    }
  }

  void unbind(void Function(GrowthPullReason reason) sink) {
    if (identical(_sink, sink)) _sink = null;
  }

  void request(GrowthPullReason reason) {
    final sink = _sink;
    if (sink != null) return sink(reason);
    if (reason == GrowthPullReason.pushClicked) _pendingPushClick = true;
  }

  @visibleForTesting
  void resetForTest() {
    _sink = null;
    _pendingPushClick = false;
  }

  /// 自己完成可能得 EXP 的动作成功后（发帖 / 评论 / 点赞 / 打卡 / 支付）。
  void afterAction() => request(GrowthPullReason.afterAction);
}

/// 全局触发器（照 `StartupPopupLedger.instance` 的单例姿态）。
final GrowthPopupTrigger growthPopupTrigger = GrowthPopupTrigger.instance;
