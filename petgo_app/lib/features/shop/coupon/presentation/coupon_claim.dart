/// 领券动作 + 反馈（v1.4.2 shop Story 8.1 AC2.5 / AC3.3；8.2 详情领券复用）。
///
/// 成功：toast「Kupon diklaim. Cek di Kupon Saya」+ `toko_coupon_claimed{coupon_id, source}` + 刷新「给你的券」。
/// 失败：已领 / 领完各一句 toast；`not-in-period` 静默刷新列表（该券随之消失）；网络等其它错误不弹（story 待确认 4）。
library;

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/router/route_intent.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../../auth/domain/auth_state.dart';
import '../../../auth/domain/login_guide_controller.dart';
import '../data/coupon_repository.dart';
import '../domain/shop_coupon.dart';

/// 领一张券。返回 null = 成功，否则为失败分类。
///
/// 🔴 `coupon_id` 传券**模板** token（不可枚举，非用户券 token）。
Future<CouponClaimFailure?> claimCouponWithFeedback(
  BuildContext context,
  WidgetRef ref, {
  required String couponToken,
  required CouponClaimSource source,
}) async {
  final l10n = AppLocalizations.of(context);
  try {
    await ref.read(couponRepositoryProvider).claim(couponToken, source);
  } catch (e) {
    final failure = CouponClaimFailure.from(e);
    if (!context.mounted) return failure;
    switch (failure) {
      case CouponClaimFailure.alreadyClaimed:
        showAppToast(context, l10n.couponAlreadyClaimed);
        ref.invalidate(couponsForYouProvider);
      case CouponClaimFailure.soldOut:
        showAppToast(context, l10n.couponSoldOut);
        ref.invalidate(couponsForYouProvider);
      case CouponClaimFailure.notInPeriod:
        ref.invalidate(couponsForYouProvider);
      case CouponClaimFailure.unauthorized:
      case CouponClaimFailure.other:
        break;
    }
    return failure;
  }
  Analytics.capture('toko_coupon_claimed', {'coupon_id': couponToken, 'source': source.analytics});
  if (context.mounted) {
    showAppToast(context, l10n.couponClaimedToast);
    ref.invalidate(couponsForYouProvider);
  }
  return null;
}

/// 游客先走软登录浮层，登录成功后自动领一次；已登录直接领。
Future<void> claimCouponOrLogin(
  BuildContext context,
  WidgetRef ref, {
  required String couponToken,
  required CouponClaimSource source,
}) async {
  if (ref.read(authControllerProvider).isLoggedIn) {
    await claimCouponWithFeedback(context, ref, couponToken: couponToken, source: source);
    return;
  }
  await ref.read(loginGuideControllerProvider).showSoftSheet(
        context,
        pendingAction: RouteIntent(onResume: () {
          if (!context.mounted) return;
          claimCouponWithFeedback(context, ref, couponToken: couponToken, source: source);
        }),
        entrySource: 'toko_coupon_claim',
        allowRepeat: true,
      );
}
