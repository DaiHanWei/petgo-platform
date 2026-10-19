import 'dart:async';

import 'package:flutter/foundation.dart';

/// 本次冷启动出现过的功能弹窗种类（V1.3.4 Story 5.3 · AD-12）。
enum StartupPopupKind {
  att,
  pushPermission,
  forceUpdate,
  coachmark,
  milestoneCelebration,
  // V1.4.0 ① Tailrent Level（Story 2.3）：升级庆祝 / 存量汇总弹窗（只在末尾追加）。
  growthCelebration,
  growthBackfill,
}

/// 本次冷启动功能弹窗记录（V1.3.4 Story 5.3 · AD-12）：**只登记、不排队**。
///
/// - 纯 Dart 进程内单例，**不依赖 Riverpod**：ATT 与首启通知权限在 `main.dart` 的首帧回调里执行，
///   那里没有 `ref`。
/// - 不持久化：进程结束即清空 = 「本次冷启动」口径。
/// - 不提供删除接口：弹窗关闭后记录仍在（本次冷启动出现过就算，PRD AC2）。
/// - 每个功能弹窗**真正出现**的那一刻调 [record]；`main.dart` 启动链末尾调 [markStartupChainDone]，
///   供运营弹窗（Story 5.4）判定前等待。
class StartupPopupLedger {
  StartupPopupLedger._();
  static final StartupPopupLedger instance = StartupPopupLedger._();

  final Set<StartupPopupKind> _recorded = <StartupPopupKind>{};
  Completer<void> _chainDone = Completer<void>();

  /// 登记；同一种重复登记无副作用。
  void record(StartupPopupKind kind) {
    if (_recorded.add(kind)) debugPrint('[StartupPopupLedger] $kind');
  }

  /// 本次冷启动是否出现过任一功能弹窗。
  bool get hasAny => _recorded.isNotEmpty;

  /// 只读视图。
  Set<StartupPopupKind> get recorded => Set.unmodifiable(_recorded);

  /// 启动链已结束（幂等）。
  void markStartupChainDone() {
    if (!_chainDone.isCompleted) _chainDone.complete();
  }

  bool get isStartupChainDone => _chainDone.isCompleted;

  /// 启动链结束时完成；永不 error。
  Future<void> get startupChainDone => _chainDone.future;

  /// 清空记录 + 换一个新的未完成 Completer（单例跨用例会串）。
  @visibleForTesting
  void resetForTest() {
    _recorded.clear();
    _chainDone = Completer<void>();
  }
}
