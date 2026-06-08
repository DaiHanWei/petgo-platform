import '../../../core/storage/prefs.dart';

/// 推送权限申请时机门控（Story 6.4，FR-22D / NFR-12）。
///
/// **绝不 App 首启请求**：在「完成首次问诊」**或**「完成建档（仅从未问诊用户）」两时机取最早、
/// 仅触发一次（`!alreadyAsked`）；无论同意/拒绝都置 `pushPermissionAsked=true`，拒绝后不再主动弹
/// （改「我的」页被动引导）。
///
/// 🔄 PRD V1.0.0 修订（F7 · 2026-06-08）：由单时机（首次问诊后）扩为双时机取最早。
/// 建档时机加 `neverConsulted` 限定，避免与问诊时机对已问诊用户重复触发。
class PushPermissionGate {
  PushPermissionGate({required this.prefs, required this.requestSystemPermission});

  final AppPrefs prefs;

  /// 触发系统权限弹窗（注入：真机用 permission_handler，测试用 fake）。返回是否授予。
  final Future<bool> Function() requestSystemPermission;

  /// 纯判定：两时机任一成立且未问过 → 应触发一次。
  /// - 首次问诊完成（`firstConsultDone`）；或
  /// - 建档完成且从未问诊（`profileCreated && neverConsulted`，避免与问诊触发重复）。
  static bool shouldRequest({
    required bool alreadyAsked,
    bool firstConsultDone = false,
    bool profileCreated = false,
    bool neverConsulted = false,
  }) {
    if (alreadyAsked) return false;
    return firstConsultDone || (profileCreated && neverConsulted);
  }

  /// 首次问诊完成后尝试请求（软引导同意后调用）。已问过则跳过；请求后落 asked=true（不论结果）。
  /// 返回：true=本次触发了请求，false=被门控跳过。
  Future<bool> maybeRequestAfterFirstConsult({required bool firstConsultDone}) {
    return _maybeRequest(firstConsultDone: firstConsultDone);
  }

  /// 建档完成后尝试请求（仅从未问诊用户触发；已问诊者由问诊时机负责，取最早、仅一次）。
  Future<bool> maybeRequestAfterProfileCreated({required bool neverConsulted}) {
    return _maybeRequest(profileCreated: true, neverConsulted: neverConsulted);
  }

  Future<bool> _maybeRequest({
    bool firstConsultDone = false,
    bool profileCreated = false,
    bool neverConsulted = false,
  }) async {
    if (!shouldRequest(
      alreadyAsked: prefs.pushPermissionAsked,
      firstConsultDone: firstConsultDone,
      profileCreated: profileCreated,
      neverConsulted: neverConsulted,
    )) {
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
