import 'dart:io' show Platform;

import 'package:app_tracking_transparency/app_tracking_transparency.dart';
import 'package:appsflyer_sdk/appsflyer_sdk.dart';
import 'package:flutter/foundation.dart';

/// AppsFlyer 移动归因客户端（《TailTopia-AppsFlyer-SDK接入交付文档》v1.0）。
///
/// 设计约束：
/// - **业务代码不得直接调本类的 [logEvent]**——事件一律走 `Analytics.capture`
///   门面，经同一 `scrub` 净化 + AppsFlyer 白名单分发（TT-DPR-2026-001 红线，
///   禁止另开绕过 sanitizer 的上报通路）。
/// - Dev Key 走 dart-define 注入带默认值，与 `POSTHOG_KEY` 同款管理方式
///   （write-only、打进包体可被逆向，非密钥级机密，但不贴公开渠道）。
/// - `manualStart: true`：initSdk 不上报启动，等首帧后（iOS 等 ATT 结果后）
///   显式 [start]，为后续隐私同意弹窗（印尼 PDP）留口子。
/// - 所有调用 try/catch 吞错——归因失败绝不阻断主流程；未初始化时全部 no-op
///   （widget 测试环境无原生插件，天然静默）。
class AppsFlyerClient {
  AppsFlyerClient._();

  static final AppsFlyerClient instance = AppsFlyerClient._();

  /// stag 分支专属总开关（2026-08-03 用户指令）：stag 环境不向 AppsFlyer 上报任何数据。
  /// 默认关闭 = init 跳过，其余方法因 `_initialized=false` 全部 no-op（含 iOS ATT 弹窗）；
  /// 如需临时验证归因可 `--dart-define=APPSFLYER_ENABLED=true` 打开。
  /// ⚠️ 本改动只留在 stag 分支，勿合回 v1.1-dev / main。
  static const bool _appsflyerEnabled = bool.fromEnvironment('APPSFLYER_ENABLED');

  /// 账号级 Dev Key（iOS/Android 共用同一个）。dart-define `APPSFLYER_DEV_KEY` 覆盖。
  static const String _devKey = String.fromEnvironment(
    'APPSFLYER_DEV_KEY',
    defaultValue: 'JvGbQC4FCpKYZd6rD9nZnF',
  );

  /// iOS App ID（App Store id6785361196，SDK 参数**不带 `id` 前缀**）；Android 忽略。
  static const String _iosAppId = '6785361196';

  AppsflyerSdk? _sdk;
  bool _initialized = false;
  bool _started = false;

  /// `runApp` 前调用一次（`main()`）。只 init 不上报（manualStart），失败不抛。
  Future<void> init() async {
    if (!_appsflyerEnabled) {
      debugPrint('[AppsFlyer] disabled (stag), skip init');
      return;
    }
    if (_initialized) return;
    try {
      final sdk = AppsflyerSdk(AppsFlyerOptions(
        afDevKey: _devKey,
        appId: _iosAppId,
        showDebug: kDebugMode, // release 自动关，避免日志泄露
        // iOS 14.5+：startSDK 后最多等 60s ATT 结果再上报启动（拿 IDFA 提升归因质量）。
        timeToWaitForATTUserAuthorization: 60,
        manualStart: true,
      ));
      // 超时上限（V1.1.2 Story 7.3 · NFR-13）：本方法在 `main.dart` 里被 `runApp` 前 await，
      // 卡住会直接拖慢首帧。原先只有下方的 try/catch 吞错、**没有超时**，initSdk 若不返回
      // 就会无限期阻塞启动。与 `Analytics.init()` 取同一口径（3s + 吞错）。
      // 超时后 `_initialized` 保持 false ⇒ 归因不上报，与初始化抛错的降级行为一致。
      await sdk
          .initSdk(
            registerConversionDataCallback: true, // 安装来源回调（GCD）
            registerOnAppOpenAttributionCallback: false,
            registerOnDeepLinkingCallback: false, // 之后做 OneLink 再打开
          )
          .timeout(const Duration(seconds: 3));
      _sdk = sdk;
      _initialized = true;
    } catch (e) {
      debugPrint('[AppsFlyer] init failed: $e');
    }
  }

  /// 首帧后调用：iOS 先请求 ATT 授权（结果出来/被跳过后）再上报启动；Android 直接上报。
  /// 幂等（只 start 一次）。
  ///
  /// TODO(产品/合规)：iOS 上应先展示一屏价值说明页再弹 ATT（提升同意率，Apple 审核友好），
  /// 待产品出文案后接入；当前直接请求（交付文档 §3.5 待确认项 3/4）。
  Future<void> start() async {
    if (!_initialized || _started) return;
    try {
      if (Platform.isIOS) {
        final status = await AppTrackingTransparency.trackingAuthorizationStatus;
        if (status == TrackingStatus.notDetermined) {
          await AppTrackingTransparency.requestTrackingAuthorization();
        }
      }
    } catch (e) {
      debugPrint('[AppsFlyer] ATT request failed: $e'); // ATT 失败不阻断启动上报
    }
    try {
      _sdk?.startSDK();
      _started = true;
    } catch (e) {
      debugPrint('[AppsFlyer] start failed: $e');
    }
  }

  /// 设置 CUID（跨端归因 P0）。值必须两端逐字节一致——调用方传
  /// `Analytics.distinctIdFor(userId)`（同一份 Dart 代码天然一致），不传邮箱/手机号。
  /// Android 用 `setCustomerIdAndLogSession` 补发一次带 CUID 的 session。
  void setUserId(String cuid) {
    if (!_initialized) return;
    try {
      if (Platform.isAndroid) {
        _sdk?.setCustomerIdAndLogSession(cuid);
      } else {
        _sdk?.setCustomerUserId(cuid);
      }
    } catch (e) {
      debugPrint('[AppsFlyer] setUserId failed: $e');
    }
  }

  /// 登出/换账号时清空 CUID，避免下一账号的事件挂到上一用户身上。
  void clearUserId() {
    if (!_initialized) return;
    try {
      _sdk?.setCustomerUserId('');
    } catch (e) {
      debugPrint('[AppsFlyer] clearUserId failed: $e');
    }
  }

  /// 上报 in-app 事件。**仅供 `Analytics._captureAppsFlyer` 白名单分发调用**，
  /// props 必须已经过 `Analytics.scrub`。
  void logEvent(String event, Map<String, Object>? props) {
    if (!_initialized) return;
    try {
      _sdk?.logEvent(event, props);
    } catch (e) {
      debugPrint('[AppsFlyer] logEvent failed: $e');
    }
  }
}
