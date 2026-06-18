import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:permission_handler/permission_handler.dart' as ph;

import '../../../core/router/app_router.dart' show rootNavigatorKey;
import '../../../core/storage/prefs.dart';
import '../domain/push_permission_gate.dart';
import '../presentation/push_permission_sheet.dart';

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
    // 系统弹窗前先弹 P-09 前置说明（用全局 navigator context）。
    // 兜底取「安全侧」：无 context / sheet 异常 → 返回 false（跳过本次，绝不在无前置说明时盲弹系统权限，
    // 也保证 gate 不因 sheet 抛错而上抛——否则建档庆祝页 onStartExplore 无法续跳 /home）。
    confirmViaRationale: () async {
      final ctx = rootNavigatorKey.currentContext;
      if (ctx == null) return false;
      try {
        return await showPushPermissionSheet(ctx);
      } catch (_) {
        return false;
      }
    },
  );
});

/// 是否当前已授予通知权限（「我的」页引导可见性判定，L2 真机）。
Future<bool> isPushPermissionGranted() async => (await ph.Permission.notification.status).isGranted;

/// 打开系统设置页（拒绝后「去设置」深链，统一复用 Story 2.1 模式）。
Future<bool> openPushSettings() => ph.openAppSettings();
