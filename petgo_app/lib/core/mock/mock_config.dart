import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';

bool _isFlutterTest() {
  try {
    return Platform.environment.containsKey('FLUTTER_TEST');
  } catch (_) {
    return false;
  }
}

/// 全局 Mock 模式开关。
///
/// - **debug 构建默认开启**（无需 dart-define 即可离线、无后端跑通全流程假数据）。
/// - 连真后端：`--dart-define=PETGO_MOCK=false`。
/// - **release 恒关**（`kDebugMode` 护栏）：即便传 `PETGO_MOCK=true` 也不生效,生产绝不 mock。
/// - **测试环境恒关**（`FLUTTER_TEST`）：widget/单元测试走各自注入的 fake,不被全局 mock 干扰。
final bool kMockMode = kDebugMode &&
    !_isFlutterTest() &&
    const bool.fromEnvironment('PETGO_MOCK', defaultValue: true);

/// Debug-only 视觉验收用「强制状态」开关：`--dart-define=DEV_STATE=<state>` 让 mock 返回特定态，
/// 把弹窗/空态/错误态/分诊红黄绿/等待态等否则不可达的屏变成可直达截图。
///
/// 支持值：`feed-empty` `feed-error` `timeline-empty` `notif-empty`
/// `consult-waiting`（match-wait 停在 WAITING）`rate`（补弹评分）
/// `triage-red` `triage-yellow` `triage-green`（强制分诊结果等级）。
/// release/test 恒空（不影响生产与单测）。
const String _devStateRaw = String.fromEnvironment('DEV_STATE');
final String kDevState = kDebugMode && !_isFlutterTest() ? _devStateRaw : '';
