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
