import 'package:flutter/foundation.dart';
import 'package:tiktok_events_sdk/tiktok_events_sdk.dart';

/// TikTok 归因客户端（TikTok Business SDK，2026-10-09 接入，**只做安装归因**）。
///
/// 背景：投放渠道只有 Meta + TikTok，直接接两家自归因 SDK 取代 MMP（AppsFlyer 试用到期）。
/// TikTok 投放后台的「测量合作方」选 TikTok SDK 即可，不需要第三方追踪链接。
///
/// 设计约束：
/// - **归因 + 唯一一个业务事件「注册完成」**：安装 / 启动 / 留存由 SDK 自动上报；注册完成只从
///   `Analytics.capture` 的白名单分发进来（TT-DPR-2026-001 红线），不得在业务代码里直接调本 SDK。
///   再加回传事件（如充值）同样只能走 `Analytics` 门面的白名单。
/// - **只有正式构建上报**：debug 构建默认不初始化（`TIKTOK_DEBUG=true` 可联调），
///   stag 出包传 `TIKTOK_ENABLED=false`——测试安装不能被算进投放归因。
/// - 🔴 **iOS 不许 SDK 自己弹 ATT**（`displayAtt: false` + `disableAppTrackingDialog: true`）：
///   ATT 统一由 `AttGate` 负责，2026-08 两次审核拒信都出在 ATT 时机上，多一个弹窗就是新的拒信。
///   本类在 ATT 落定之后才 init（见 `main.dart`），SDK 直接读到授权结果。
/// - 不开支付自动采集（我们没有 App 内购，避免误采）。
/// - 用户关联只传哈希后的用户 id（与 PostHog distinct_id 同值），不传手机号 / 邮箱 / 昵称。
/// - 所有调用 try/catch 吞错——归因失败绝不阻断主流程；未初始化时全部 no-op。
class TikTokClient {
  TikTokClient._();

  static final TikTokClient instance = TikTokClient._();

  /// TikTok 后台（Events Manager）给的 TikTok App ID / App Secret，Android 与 iOS 是两个 App。
  ///
  /// 与 AppsFlyer Dev Key 同款管理：写成代码默认值、dart-define 可覆盖。App Secret 名为 secret，
  /// 但它是**客户端 SDK 凭证**——会随安装包分发、可被逆向，不是服务端机密；不贴公开渠道即可。
  static const String _ttAppIdAndroid =
      String.fromEnvironment('TIKTOK_APP_ID_ANDROID', defaultValue: '7670429125149605895');
  static const String _appSecretAndroid =
      String.fromEnvironment('TIKTOK_APP_SECRET_ANDROID', defaultValue: 'TTuixVWFf2zmnjn2YNAAxKofayU0VxD2');
  static const String _ttAppIdIos =
      String.fromEnvironment('TIKTOK_APP_ID_IOS', defaultValue: '7670433130750148616');
  static const String _appSecretIos =
      String.fromEnvironment('TIKTOK_APP_SECRET_IOS', defaultValue: 'TTmy3hP9uP0AmoYB0qw5CxkbkmlEsrHd');

  /// 总开关（stag 出包传 `TIKTOK_ENABLED=false`，测试安装不进投放归因）。
  static const bool _enabledByDefine = bool.fromEnvironment('TIKTOK_ENABLED', defaultValue: true);

  /// debug 构建默认不报（本地开发装机会被算成安装，污染投放数据）；联调时传 `TIKTOK_DEBUG=true`，
  /// SDK 以 debug 模式运行，事件进 TikTok Events Manager 的「测试事件」。
  static const bool _debugOptIn = bool.fromEnvironment('TIKTOK_DEBUG');

  /// SDK 要的「本 App 标识」：Android = 包名，iOS = App Store 数字 id（不带 `id` 前缀）。
  static const String _androidPackage = 'com.tailtopia.app';
  static const String _iosAppStoreId = '6785361196';

  bool _started = false;

  /// init 之前到达的用户关联；空串 = 待补发的 logout；null = 无待发。
  String? _pendingUid;

  /// 本构建是否会初始化 TikTok：总开关开，且（release 构建，或 debug 下显式 opt-in）。
  @visibleForTesting
  static bool shouldStart({required bool debug}) => _enabledByDefine && (!debug || _debugOptIn);

  /// 首帧后、ATT 落定之后调用一次（见 `main.dart`）。幂等，失败不抛。
  Future<void> start() async {
    if (_started || kIsWeb || !shouldStart(debug: kDebugMode)) return;
    final isIos = defaultTargetPlatform == TargetPlatform.iOS;
    if (!isIos && defaultTargetPlatform != TargetPlatform.android) return;
    if ((isIos ? _ttAppIdIos : _ttAppIdAndroid).isEmpty) return;
    try {
      await TikTokEventsSdk.initSdk(
        androidAppId: _androidPackage,
        tikTokAndroidId: _ttAppIdAndroid,
        iosAppId: _iosAppStoreId,
        tiktokIosId: _ttAppIdIos,
        isDebugMode: kDebugMode,
        logLevel: kDebugMode ? TikTokLogLevel.debug : TikTokLogLevel.info,
        androidOptions: TikTokAndroidOptions(
          appSecret: _appSecretAndroid,
        ),
        iosOptions: TikTokIosOptions(
          accessToken: _appSecretIos,
          displayAtt: false,
          disableAppTrackingDialog: true,
          disablePaymentTracking: true,
        ),
      ).timeout(const Duration(seconds: 3));
      _started = true;
      final pending = _pendingUid;
      _pendingUid = null;
      if (pending != null) {
        pending.isEmpty ? await clearUserId() : await setUserId(pending);
      }
    } catch (e) {
      debugPrint('[TikTok] init failed: $e');
    }
  }

  /// 登录后关联哈希用户 id（调用方传 `Analytics.distinctIdFor(userId)`）。
  Future<void> setUserId(String distinctId) async {
    if (!_started) {
      _pendingUid = distinctId;
      return;
    }
    try {
      await TikTokEventsSdk.identify(identifier: TikTokIdentifier(externalId: distinctId));
    } catch (e) {
      debugPrint('[TikTok] identify failed: $e');
    }
  }

  /// 注册完成（TikTok 标准事件 `Registration`）。只由 `Analytics.capture` 的白名单分发进来。
  /// 不带注册方式等属性：TikTok 标准事件按名字识别即可用于投放优化，少传少风险。
  Future<void> logRegistration() async {
    if (!_started) return;
    try {
      await TikTokEventsSdk.logEvent(event: TikTokEvent(eventName: 'Registration'));
    } catch (e) {
      debugPrint('[TikTok] registration failed: $e');
    }
  }

  /// 登出 / 换账号：解除关联，防止下一个账号的会话串到上一个人。
  Future<void> clearUserId() async {
    if (!_started) {
      _pendingUid = '';
      return;
    }
    try {
      await TikTokEventsSdk.logout();
    } catch (e) {
      debugPrint('[TikTok] logout failed: $e');
    }
  }
}
