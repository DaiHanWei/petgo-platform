import 'package:firebase_analytics/firebase_analytics.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/foundation.dart';

/// Firebase（GA4）日活统计（spec-v132-ga4-dau-daily-report）。
///
/// 只开 SDK **自动采集**（first_open / session_start / user_engagement），日报「日活（含游客）」
/// 由后端经 GA4 Data API 读 `activeUsers`。设计约束：
/// - **不加自定义事件、不调 `setUserId`、不设用户属性**——日活只需设备维度，业务埋点仍走
///   `Analytics.capture` 门面（TT-DPR-2026-001 红线，不另开旁路）。
/// - 首帧后初始化，3s 超时 + 吞错：统计失败绝不影响启动（同 `AppsFlyerClient.init` 口径）。
/// - 测试数据不进生产口径：debug 构建一律关采集（Android 另在 debug manifest 默认关，
///   连首启那一下都不报）；stag 包由 stag 分支的出包脚本传
///   `--dart-define=FIREBASE_ANALYTICS_ENABLED=false`。
/// - iOS 需要 `ios/Runner/GoogleService-Info.plist`；缺失时 `initializeApp` 抛错被吞，iOS 端不统计。
class FirebaseStats {
  FirebaseStats._();

  static const bool _enabledByDefine = bool.fromEnvironment(
    'FIREBASE_ANALYTICS_ENABLED',
    defaultValue: true,
  );

  /// 本构建是否向 GA4 上报。debug 一律否。
  static bool get collectionEnabled => _enabledByDefine && !kDebugMode;

  static bool _initialized = false;

  /// 首帧后调用一次，幂等，失败不抛。
  static Future<void> init() async {
    if (_initialized || kIsWeb) return;
    _initialized = true;
    try {
      await Firebase.initializeApp().timeout(const Duration(seconds: 3));
      // 显式写一次：该设置会被 SDK 持久化，开 / 关都以本构建为准（stag 包装过正式包也能纠正回来）。
      await FirebaseAnalytics.instance
          .setAnalyticsCollectionEnabled(collectionEnabled);
    } catch (e) {
      debugPrint('[Firebase] init failed: $e');
    }
  }
}
