import '../../../core/storage/prefs.dart';

/// 推送权限申请时机门控（Story 6.4，FR-22D / NFR-12）。
///
/// **绝不 App 首启请求**：仅当「完成首次问诊 && 从未问过推送权限」才触发一次；
/// 无论同意/拒绝都置 `pushPermissionAsked=true`，拒绝后不再主动弹（改「我的」页被动引导）。
class PushPermissionGate {
  PushPermissionGate({required this.prefs, required this.requestSystemPermission});

  final AppPrefs prefs;

  /// 触发系统权限弹窗（注入：真机用 permission_handler，测试用 fake）。返回是否授予。
  final Future<bool> Function() requestSystemPermission;

  /// 纯判定：首次问诊完成且未问过 → 应触发一次。
  static bool shouldRequest({required bool firstConsultDone, required bool alreadyAsked}) {
    return firstConsultDone && !alreadyAsked;
  }

  /// 首次问诊完成后尝试请求（软引导同意后调用）。已问过则跳过；请求后落 asked=true（不论结果）。
  /// 返回：true=本次触发了请求，false=被门控跳过。
  Future<bool> maybeRequestAfterFirstConsult({required bool firstConsultDone}) async {
    if (!shouldRequest(firstConsultDone: firstConsultDone, alreadyAsked: prefs.pushPermissionAsked)) {
      return false;
    }
    try {
      await requestSystemPermission();
    } finally {
      // 无论同意/拒绝/异常，都标记已问过——拒绝后不再主动弹。
      await prefs.setPushPermissionAsked(true);
    }
    return true;
  }
}
