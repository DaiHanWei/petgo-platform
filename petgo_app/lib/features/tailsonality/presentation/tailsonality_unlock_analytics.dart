import '../../../core/analytics/analytics.dart';
import '../../profile/domain/id_card.dart';

/// Tailsonality 付费漏斗 · App 端埋点（V1.3.2 Story 3.2 · AC8），照 `KtpUnlockAnalytics`。
///
/// 🔴 **成功（`tailsonality_unlocked`）只由服务端报**：用户关掉二维码面板后再付款，App 永远不知道。
/// 属性只有代号、数字、渠道枚举（`role_code` / `price` / `result_index` / `method`）；
/// 不带宠物名、品种、token（`Analytics` 也会丢 `name` / `breed` / `token` 这类键）。
class TailsonalityUnlockAnalytics {
  TailsonalityUnlockAnalytics._();

  /// 锁态区首次进入可视区域（每个页面实例一次）。
  static void viewed({required String roleCode, required int resultIndex, int? price}) {
    Analytics.capture('tailsonality_unlock_viewed', {
      'role_code': roleCode,
      'price': ?price,
      'result_index': resultIndex,
    });
  }

  /// 抽屉内选定渠道并确认。
  static void initiated(
      {required String roleCode, required int resultIndex, required HdPayChannel method, int? price}) {
    Analytics.capture('tailsonality_unlock_initiated', {
      'role_code': roleCode,
      'price': ?price,
      'result_index': resultIndex,
      'method': method.wire,
    });
  }

  /// 配型锁态页首次展示（每个页面实例一次；2026-10-09 配型改回付费）。
  static void matchViewed({required String roleCode, required int resultIndex, int? price}) {
    Analytics.capture('tailsonality_match_unlock_viewed', {
      'role_code': roleCode,
      'price': ?price,
      'result_index': resultIndex,
    });
  }

  /// 配型锁态页上选定渠道并确认。[product] = `match`（单买配型 3k）/ `full`（完整解读，含配型）。
  /// 成功（`tailsonality_match_unlocked` / `tailsonality_unlocked`）同样只由服务端报。
  static void matchInitiated({
    required String roleCode,
    required int resultIndex,
    required HdPayChannel method,
    required String product,
    int? price,
  }) {
    Analytics.capture('tailsonality_match_unlock_initiated', {
      'role_code': roleCode,
      'price': ?price,
      'result_index': resultIndex,
      'method': method.wire,
      'product': product,
    });
  }

  /// 挽留弹窗点「Nanti aja」。
  static void abandoned({required int resultIndex}) {
    Analytics.capture('tailsonality_paywall_abandoned', {'result_index': resultIndex});
  }
}
