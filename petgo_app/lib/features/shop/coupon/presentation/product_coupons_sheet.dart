/// 商品详情「可领的券」抽屉（v1.4.2 shop Story 8.2 · UI B7 / B8，FR-130.4、D-6）。
///
/// 逐张 [CouponCard]（满减 / 免运两种券面：面额、门槛、名称、有效期），按钮「Klaim」/ 已领「Sudah diklaim」（灰、不可点）。
/// - 游客点 Klaim → 软登录浮层，登录后自动领这一张（[claimCouponOrLogin]）；
/// - 已登录 → `claim(source=DETAIL)`，在途按钮转圈、不可重复点；
/// - **B8**：成功 toast 放顶部（抽屉开着，底部会被挡）、**抽屉不关**；之后刷新本列表，「已领」以服务端 `claimed` 为准。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../auth/domain/auth_state.dart';
import '../../presentation/widgets/shop_buttons.dart';
import '../data/coupon_repository.dart';
import '../domain/shop_coupon.dart';
import 'coupon_card.dart';
import 'coupon_claim.dart';

Future<void> showProductCouponsSheet(BuildContext context, {required String productToken}) =>
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (_) => ProductCouponsSheet(productToken: productToken),
    );

class ProductCouponsSheet extends ConsumerStatefulWidget {
  const ProductCouponsSheet({super.key, required this.productToken});

  final String productToken;

  @override
  ConsumerState<ProductCouponsSheet> createState() => _ProductCouponsSheetState();
}

class _ProductCouponsSheetState extends ConsumerState<ProductCouponsSheet> {
  /// 在途（按钮转圈、不可重复点）。🔴 不在本地记「已领」，只取服务端 `claimed`（同 8.1 复审 #1）。
  final Set<String> _pending = {};

  Future<void> _claim(ShopCoupon c) async {
    if (_pending.contains(c.couponToken)) return;
    // 🔴 8.2 复审 #2：容器级引用 —— 在途时关掉抽屉，本 State 的 ref 已失效；详情页券行仍要刷新。
    final container = ProviderScope.containerOf(context, listen: false);
    final token = widget.productToken;
    void refresh() => container.invalidate(productCouponsProvider(token));
    if (!ref.read(authControllerProvider).isLoggedIn) {
      // 游客：只开软登录浮层（浮层开着时按钮不转圈 —— 还没发请求）；登录后自动领这一张。
      await claimCouponOrLogin(context, ref,
          couponToken: c.couponToken,
          source: CouponClaimSource.detail,
          topToast: true,
          onSettled: refresh);
      return;
    }
    setState(() => _pending.add(c.couponToken));
    try {
      await claimCouponOrLogin(context, ref,
          couponToken: c.couponToken,
          source: CouponClaimSource.detail,
          topToast: true,
          onSettled: refresh);
      if (!mounted) return;
      // 等新列表落定再解除在途：期间旧数据仍显示「Klaim」也点不动。
      try {
        await ref.read(productCouponsProvider(widget.productToken).future);
      } catch (_) {}
    } finally {
      if (mounted) setState(() => _pending.remove(c.couponToken));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final coupons = ref.watch(productCouponsProvider(widget.productToken)).maybeWhen(
        data: (v) => v, orElse: () => const <ShopCoupon>[]);
    return ConstrainedBox(
      key: const ValueKey('productCouponsSheet'),
      constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * .72),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(AppSpacing.screenEdge, AppSpacing.md, 4, 0),
            child: Row(
              children: [
                Expanded(
                  child: Text(l10n.couponForProductTitle,
                      style: AppTypography.title.copyWith(fontSize: 16, color: AppColors.ink)),
                ),
                IconButton(
                  key: const ValueKey('productCouponsClose'),
                  icon: const Icon(Icons.close),
                  tooltip: MaterialLocalizations.of(context).closeButtonTooltip,
                  onPressed: () => Navigator.of(context).pop(),
                ),
              ],
            ),
          ),
          Flexible(
            child: ListView(
              shrinkWrap: true,
              padding: const EdgeInsets.fromLTRB(
                  AppSpacing.screenEdge, AppSpacing.sm, AppSpacing.screenEdge, AppSpacing.xl),
              children: [
                for (final c in coupons)
                  Padding(
                    padding: const EdgeInsets.only(bottom: AppSpacing.md),
                    child: CouponCard(
                      key: ValueKey('productCoupon_${c.couponToken}'),
                      coupon: c.toOption(),
                      trailing: ShopButton(
                        key: ValueKey('productCouponClaim_${c.couponToken}'),
                        label: c.claimed ? l10n.couponClaimedDone : l10n.couponClaim,
                        variant: c.claimed ? ShopButtonVariant.disabled : ShopButtonVariant.purple,
                        dense: true,
                        loading: _pending.contains(c.couponToken),
                        onTap: c.claimed || _pending.contains(c.couponToken)
                            ? null
                            : () => _claim(c),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
