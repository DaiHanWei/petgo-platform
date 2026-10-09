import 'package:facebook_app_events/facebook_app_events.dart';
import 'package:flutter/foundation.dart';

/// Meta（Facebook）归因客户端（App Events，2026-10-09 接入）。
///
/// 背景：投放渠道只有 Meta + TikTok，直接接两家自归因 SDK 取代 MMP（AppsFlyer 试用到期）。
///
/// 设计约束：
/// - **安装 / 启动由原生 SDK 自动上报**（这就是归因），开关在原生配置里按构建类型注入：
///   Android `fbAutoLogAppEvents`（build.gradle.kts，debug=false）、iOS
///   `FB_AUTO_LOG_APP_EVENTS`（Debug.xcconfig=NO）。本类不负责启动。
/// - **业务事件只有「注册完成」一个**，且只从 `Analytics.capture` 的白名单分发进来
///   （TT-DPR-2026-001 红线：业务代码不得直接调本类 / Meta SDK）。
/// - 只有正式构建上报：debug 默认不报（`META_DEBUG=true` 可联调）；stag 出包传
///   `META_ENABLED=false`——Android 的 build.gradle.kts 也读这个 define，连原生自动上报一起关
///   （iOS 没有 stag 包，未接）。`META_DEBUG` 只管本类发的事件，不会打开 debug 包的原生自动上报。
/// - 用户关联只传哈希后的用户 id（与 PostHog distinct_id 同值），不传手机号 / 邮箱 / 昵称。
/// - 所有调用 try/catch 吞错——归因失败绝不阻断主流程。
class MetaClient {
  MetaClient._();

  static final MetaClient instance = MetaClient._();

  static const bool _enabledByDefine = bool.fromEnvironment('META_ENABLED', defaultValue: true);
  static const bool _debugOptIn = bool.fromEnvironment('META_DEBUG');

  static final FacebookAppEvents _fb = FacebookAppEvents();

  /// 本构建是否向 Meta 发事件：总开关开，且（release 构建，或 debug 下显式 opt-in）。
  @visibleForTesting
  static bool shouldReport({required bool debug}) => _enabledByDefine && (!debug || _debugOptIn);

  bool get _active => !kIsWeb && shouldReport(debug: kDebugMode);

  /// 登录后关联哈希用户 id（调用方传 `Analytics.distinctIdFor(userId)`）。
  Future<void> setUserId(String distinctId) async {
    if (!_active) return;
    try {
      await _fb.setUserID(distinctId);
    } catch (e) {
      debugPrint('[Meta] setUserID failed: $e');
    }
  }

  /// 登出 / 换账号：解除关联，防止下一个账号的事件串到上一个人。
  Future<void> clearUserId() async {
    if (!_active) return;
    try {
      await _fb.clearUserID();
    } catch (e) {
      debugPrint('[Meta] clearUserID failed: $e');
    }
  }

  /// 注册完成（Meta 标准事件 `fb_mobile_complete_registration`）。[method] = google / apple。
  Future<void> logRegistration(String? method) async {
    if (!_active) return;
    try {
      await _fb.logCompletedRegistration(registrationMethod: method);
    } catch (e) {
      debugPrint('[Meta] registration failed: $e');
    }
  }
}
