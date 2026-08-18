import 'package:permission_handler/permission_handler.dart' as ph;

import '../../../core/storage/prefs.dart';
import 'push_permission_change_reporter.dart';

/// 首启通知权限申请（2026-08-07 产品决策变更）。
///
/// **取代 FR-22D / 决策 F7 的「双时机」**：原设计为避免冷启动打扰，把权限申请推迟到
/// 「建档完成」或「首次问诊完成」。L2 实测暴露该设计对**存量用户**是死路——两个触发点
/// 都是一次性的，老用户换机/重装后既不会重走建档、也未必再问诊，App 于是永不申请权限；
/// 而 iOS 上「从未申请过」的 App **不会出现在系统设置的通知列表里**，用户连手动开启都做不到
/// ⇒ 永久收不到任何推送。故改为「首启即申请 + 设置页开关兜底」。
///
/// 与 F7 共用 `pushPermissionAsked` 标记：首启申请后置位，原 [PushPermissionGate] 的两个
/// 触发点自然跳过（不重复打扰），无需删除其代码。
class PushPermissionBootstrap {
  PushPermissionBootstrap._();

  /// 首启申请一次（已申请过 → 空转）。返回是否已授权。
  ///
  /// ⚠️ 调用时机必须在 iOS ATT 弹窗**之后**（见 main.dart）：两个系统弹窗同帧抛出会互相
  /// 遮挡、拉低两者的授权率。异常静默——权限拿不到只是收不到推送，绝不能拖垮启动。
  static Future<bool> requestOnFirstLaunch({
    AppPrefs? prefs,
    Future<bool> Function()? request,
  }) async {
    try {
      final p = prefs ?? await AppPrefs.create();
      if (p.pushPermissionAsked) {
        final granted = await _isGranted();
        // 🔴 Story 9.3：撤销通常发生在 App 被杀掉、用户去系统设置里关掉通知的时候 ——
        //    冷启动是端上唯一能察觉它的时机。错过这里就永远看不到这个信号。
        await PushPermissionChangeReporter.record(granted,
            fromScreen: 'app_launch', prefs: p);
        return granted;
      }
      final granted = await (request ?? _request)();
      await p.setPushPermissionAsked(true);
      // 首启这一次只写基线（reporter 内部：无上一次状态 → 不上报）
      await PushPermissionChangeReporter.record(granted,
          fromScreen: 'app_launch', prefs: p);
      return granted;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> _request() async =>
      (await ph.Permission.notification.request()).isGranted;

  static Future<bool> _isGranted() async =>
      (await ph.Permission.notification.status).isGranted;
}
