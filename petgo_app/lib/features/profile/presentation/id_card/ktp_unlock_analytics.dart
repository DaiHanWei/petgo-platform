import 'package:dio/dio.dart';

import '../../../../core/analytics/analytics.dart';
import '../../domain/id_card.dart';

/// KTP（身份证高清图）付费漏斗 · App 端埋点（2026-09-25）。
///
/// 生成页与卡详情页两个付费入口共用这里，事件名与属性键只有一份定义，两处口径不会跑偏。
///
/// 分工（与后端 `KtpUnlockAnalyticsListener` 配合）：
/// - App 报：`ktp_unlock_paywall_shown`（弹付费弹窗）、`ktp_unlock_started`（选定支付方式）、
///   `ktp_unlock_failed` 里**只有服务端看不到**的两类 —— 余额不足（409）、请求没到服务端（网络）。
/// - 🔴 **成功只由服务端报**：App 只在二维码弹窗开着时轮询到账，用户关掉弹窗后再付款
///   App 永远不知道；客户端报成功会系统性少计收入。二维码超时 / 网关失败也由服务端报，
///   App 再报一次就是重复计数。
///
/// 属性键 `method` / `price_idr` / `failure_reason` 与服务端同名，跨端漏斗才拼得起来。
class KtpUnlockAnalytics {
  KtpUnlockAnalytics._();

  static const String entryCreate = 'create';
  static const String entryDetail = 'detail';

  static void paywallShown({required String entry, int? priceIdr}) {
    Analytics.capture('ktp_unlock_paywall_shown', {
      'entry': entry,
      'price_idr': ?priceIdr,
    });
  }

  static void started({required String entry, required HdPayChannel method, int? priceIdr}) {
    Analytics.capture('ktp_unlock_started', {
      'entry': entry,
      'method': method.wire,
      'price_idr': ?priceIdr,
    });
  }

  /// 购买请求失败时调用；只对服务端看不到的两类失败上报，其余静默（服务端已报）。
  static void failedFromError({
    required String entry,
    required HdPayChannel method,
    required Object error,
    int? priceIdr,
  }) {
    final reason = failureReasonOf(error);
    if (reason == null) return;
    Analytics.capture('ktp_unlock_failed', {
      'entry': entry,
      'method': method.wire,
      'failure_reason': reason,
      'price_idr': ?priceIdr,
    });
  }

  /// 失败归类。null = 不由 App 上报（服务端已覆盖或无法归类）。
  static String? failureReasonOf(Object error) {
    if (error is! DioException) return null;
    final status = error.response?.statusCode;
    if (status == 409) return 'INSUFFICIENT_BALANCE';
    // 没拿到响应 = 请求没到服务端，服务端对此一无所知，只能 App 报。
    if (error.response == null) return 'NETWORK_ERROR';
    return null;
  }
}
