import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:permission_handler/permission_handler.dart' as ph;

import '../../../core/storage/prefs.dart';
import '../domain/push_permission_gate.dart';

/// 推送权限门控 provider（Story 6.4）。真机路径用 permission_handler 申请通知权限；
/// 测试可 override 注入 fake 请求。AppPrefs 沿用项目 ad-hoc 创建方式（与 main/profile 一致）。
final pushPermissionGateProvider = FutureProvider<PushPermissionGate>((ref) async {
  final prefs = await AppPrefs.create();
  return PushPermissionGate(
    prefs: prefs,
    requestSystemPermission: () async {
      final status = await ph.Permission.notification.request();
      return status.isGranted;
    },
  );
});

/// 是否当前已授予通知权限（「我的」页引导可见性判定，L2 真机）。
Future<bool> isPushPermissionGranted() async => (await ph.Permission.notification.status).isGranted;

/// 打开系统设置页（拒绝后「去设置」深链，统一复用 Story 2.1 模式）。
Future<bool> openPushSettings() => ph.openAppSettings();
