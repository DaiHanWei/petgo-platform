import 'dart:async';

import 'package:tailtopia/features/tailsonality/data/ts_role_art.dart';

/// 全部测试共用的前置（flutter_test 自动加载本文件）。
///
/// Tailsonality 角色卡是运行期从服务器下载的（[TsRoleArt]）：测试里默认「取不到」，不碰网络与文件系统；
/// 要测出图的用例自己装 `TsRoleArt.debugLoader` 并在 tearDown 里 `debugReset`。
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  TsRoleArt.debugLoader = (_) async => null;
  await testMain();
}
