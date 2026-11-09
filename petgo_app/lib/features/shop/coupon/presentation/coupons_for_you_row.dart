/// 首页「Kupon buat kamu」（v1.4.2 shop Story 8.1 AC3，FR-130.4 / .5，UI A1 / A3）。
///
/// - 仅已登录渲染；空 / 失败 → **整块不出现**（不出错误态）。
/// - 横滑，两张正好占满一行宽，其余左滑出现；右上「Kupon Saya ›」进我的券。
/// - 可领「Klaim」/ 已领「Diklaim」（灰、不可点）；新人券置顶紫底、标「KHUSUS PENGGUNA BARU」、
///   按钮位「Sudah masuk」（自动发的，不可领），点券面进我的券。**不弹到账弹窗**（11-05 取代）。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../auth/domain/auth_state.dart';
import '../../presentation/widgets/shop_pressable.dart';
import '../data/coupon_repository.dart';
import '../domain/shop_coupon.dart';
import 'coupon_card.dart';
import 'coupon_claim.dart';

/// 我的券页路径（9.3 实现；8.1 先挂占位页）。
const String kMyCouponsLocation = '/me/coupons';

class CouponsForYouRow extends ConsumerStatefulWidget {
  const CouponsForYouRow({super.key});

  @override
  ConsumerState<CouponsForYouRow> createState() => _CouponsForYouRowState();
}

class _CouponsForYouRowState extends ConsumerState<CouponsForYouRow> {
  /// 请求在途（按钮转圈、不可重复点）。
  ///
  /// 🔴 **不在本地记「已领」**（8.1 复审 #1）：「已领」只取服务端 `claimed`。本地只增集合会跨账号残留
  /// （换号后同一张券仍显示 Diklaim），也会把 `perUserLimit > 1` 的券永久锁死。
  /// 领券后 `claimCouponWithFeedback` 已 invalidate 列表；这里等新列表回来再解除在途，
  /// 期间旧数据仍显示「Klaim」也点不动（防重复点）。
  final Set<String> _pending = {};

  Future<void> _claim(ShopCoupon c) async {
    if (_pending.contains(c.couponToken)) return;
    setState(() => _pending.add(c.couponToken));
    try {
      await claimCouponWithFeedback(context, ref,
          couponToken: c.couponToken, source: CouponClaimSource.home);
      if (!mounted) return;
      try {
        await ref.read(couponsForYouProvider.future);
      } catch (_) {
        // 列表刷新失败按无券处理（provider 内已吞错），这里只是等它落定。
      }
    } finally {
      if (mounted) setState(() => _pending.remove(c.couponToken));
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!ref.watch(authControllerProvider).isLoggedIn) return const SizedBox.shrink();
    final coupons = ref.watch(couponsForYouProvider).maybeWhen(
        data: (v) => v, orElse: () => const <ShopCoupon>[]);
    if (coupons.isEmpty) return const SizedBox.shrink();
    final l10n = AppLocalizations.of(context);
    // 新人券置顶（服务端已排，这里稳定排序兜底）。
    final sorted = [...coupons.where((c) => c.newbie), ...coupons.where((c) => !c.newbie)];
    // UI A1 `.v-cps`：窄卡定宽 172、间距 8（新人券 264），横滑露出下一张。
    return _row(l10n, sorted, 8);
  }

  static const double _cardW = 172;
  static const double _newbieCardW = 264;

  Widget _row(AppLocalizations l10n, List<ShopCoupon> sorted, double gap) {
    return Padding(
      key: const ValueKey('couponsForYouRow'),
      padding: const EdgeInsets.only(top: AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
            child: Row(
              children: [
                Expanded(
                  child: Text(l10n.couponForYouTitle,
                      style: AppTypography.title.copyWith(fontSize: 16, color: AppColors.ink)),
                ),
                ShopPressable(
                  key: const ValueKey('couponsForYouMyCoupons'),
                  onTap: () => context.push(kMyCouponsLocation),
                  minSize: kShopMinTapTarget,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(l10n.couponMyCoupons,
                          style: AppTypography.caption.copyWith(color: AppColors.mint)),
                      const Icon(Icons.chevron_right, size: 16, color: AppColors.mint),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
            child: IntrinsicHeight(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (var i = 0; i < sorted.length; i++) ...[
                    if (i > 0) SizedBox(width: gap),
                    // 稿件定宽 172（新人券 264）；本机字体 / 字号放大时按内容撑宽，宁可宽一点也不截断面额。
                    ConstrainedBox(
                      constraints: BoxConstraints(
                          minWidth: sorted[i].newbie ? _newbieCardW : _cardW, maxWidth: 300),
                      child: IntrinsicWidth(child: _card(l10n, sorted[i])),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _card(AppLocalizations l10n, ShopCoupon c) {
    if (c.newbie) {
      return CouponCard(
        key: ValueKey('couponForYou_${c.couponToken}'),
        coupon: c.toOption(),
        compact: true,
        accent: true,
        badge: l10n.couponNewbieBadge,
        onTap: () => context.push(kMyCouponsLocation),
        trailing: Row(
          key: const ValueKey('couponNewbieAdded'),
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.check_circle, size: 16, color: ShopColors.surface),
            const SizedBox(width: 4),
            Flexible(
              child: Text(l10n.couponNewbieAdded,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.caption
                      .copyWith(color: ShopColors.surface, fontWeight: FontWeight.w600)),
            ),
          ],
        ),
      );
    }
    final claimed = c.claimed;
    final pending = _pending.contains(c.couponToken);
    return CouponCard(
      key: ValueKey('couponForYou_${c.couponToken}'),
      coupon: c.toOption(),
      compact: true,
      trailing: CouponClaimPill(
        key: ValueKey('couponClaim_${c.couponToken}'),
        label: claimed ? l10n.couponClaimedShort : l10n.couponClaim,
        claimed: claimed,
        loading: pending,
        onTap: claimed || pending ? null : () => _claim(c),
      ),
    );
  }
}

/// 窄卡右侧的「Klaim / Diklaim」胶囊（UI A1 `.v-cbtn`：紫底白字 12/700、4×12 内边距；已领灰底灰字）。
/// 视觉是小胶囊，命中区仍撑到 [kShopMinTapTarget]。
class CouponClaimPill extends StatelessWidget {
  const CouponClaimPill({
    super.key,
    required this.label,
    required this.claimed,
    required this.loading,
    required this.onTap,
  });

  final String label;
  final bool claimed;
  final bool loading;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final fg = claimed ? ShopColors.text3 : ShopColors.surface;
    return ShopPressable(
      onTap: onTap,
      minSize: kShopMinTapTarget,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        decoration: BoxDecoration(
          color: claimed ? ShopColors.border2 : ShopColors.purple,
          borderRadius: BorderRadius.circular(999),
        ),
        child: loading
            ? SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(strokeWidth: 2, color: fg),
              )
            : Text(label,
                maxLines: 1,
                style: AppTypography.caption
                    .copyWith(fontSize: 12, fontWeight: FontWeight.w700, color: fg)),
      ),
    );
  }
}
