import 'dart:async';

import 'package:tailtopia/features/profile/presentation/health_list_page.dart';
import 'package:tailtopia/features/tailsonality/data/ts_remote_art.dart';

/// 全部测试共用的前置（flutter_test 自动加载本文件）。
///
/// Tailsonality 角色卡 / 配型卡是运行期从服务器下载的（[TsRemoteArt]）：测试里默认「取不到」，不碰网络与文件系统；
/// 要测出图的用例自己装 `TsRemoteArt.debugLoader` 并在 tearDown 里 `debugReset`。
///
/// 健康记录页 Konsultasi 卡的常驻脉冲（V1.5.0 Story 3.1）会让 `pumpAndSettle` 不收敛 → 默认关；
/// `test/profile/health_list_page_test.dart` 里有专门用例打开并断言 `pulsing: true`。
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  TsRemoteArt.debugLoader = (_) async => null;
  HealthListPage.debugConsultPulse = false;
  await testMain();
}
