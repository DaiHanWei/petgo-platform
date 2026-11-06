/// A4 游客新人券弹窗（v1.4.2 shop Story 8.1 AC4.2 / AC4.3，FR-130.5、D-5）。
///
/// 右上关闭 ×、`redeem` 图标、标题「Daftar sekarang, dapat kupon Rp {amount}」、
/// 副标题「Khusus pengguna baru · min. belanja Rp {min}」、主按钮「Daftar & klaim」→ `/login`。
/// 「同设备同活动只弹一次」由调用方在弹出前写 `AppPrefs`，本组件只负责渲染与埋点。
library;

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../../presentation/widgets/shop_buttons.dart';
import '../domain/shop_coupon.dart';

/// 弹出 A4。`toko_newbie_coupon_popup_shown` 在弹出时上报一次。
Future<void> showNewbieCouponPopup(BuildContext context, NewbieCampaign campaign) {
  Analytics.capture('toko_newbie_coupon_popup_shown', {'coupon_id': campaign.campaignToken});
  return showDialog<void>(
    context: context,
    builder: (dialogContext) => NewbieCouponPopup(
      campaign: campaign,
      onClose: () {
        Analytics.capture('toko_newbie_coupon_popup_tapped',
            {'coupon_id': campaign.campaignToken, 'action': 'close'});
        Navigator.of(dialogContext).pop();
      },
      onRegister: () {
        Analytics.capture('toko_newbie_coupon_popup_tapped',
            {'coupon_id': campaign.campaignToken, 'action': 'register'});
        Navigator.of(dialogContext).pop();
        context.push('/login');
      },
    ),
  );
}

class NewbieCouponPopup extends StatelessWidget {
  const NewbieCouponPopup({
    super.key,
    required this.campaign,
    required this.onClose,
    required this.onRegister,
  });

  final NewbieCampaign campaign;
  final VoidCallback onClose;
  final VoidCallback onRegister;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final closeLabel = MaterialLocalizations.of(context).closeButtonTooltip;
    return Dialog(
      key: const ValueKey('newbieCouponPopup'),
      backgroundColor: AppColors.card,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.sm, AppSpacing.sm, AppSpacing.lg),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Align(
              alignment: Alignment.topRight,
              child: IconButton(
                key: const ValueKey('newbieCouponPopupClose'),
                tooltip: closeLabel,
                icon: const Icon(Icons.close, color: AppColors.muted),
                onPressed: onClose,
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(right: AppSpacing.sm),
              child: Column(
                children: [
                  Container(
                    width: 56,
                    height: 56,
                    alignment: Alignment.center,
                    decoration: const BoxDecoration(color: AppColors.mintTint, shape: BoxShape.circle),
                    child: const Icon(Icons.redeem, size: 30, color: AppColors.mint),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  Text(
                    l10n.couponNewbiePopupTitle(formatIdrAmount(campaign.amount)),
                    textAlign: TextAlign.center,
                    style: AppTypography.title.copyWith(color: AppColors.ink),
                  ),
                  if (campaign.minSpend > 0) ...[
                    const SizedBox(height: AppSpacing.xs),
                    Text(
                      l10n.couponNewbiePopupSub(formatIdrAmount(campaign.minSpend)),
                      textAlign: TextAlign.center,
                      style: AppTypography.caption,
                    ),
                  ],
                  const SizedBox(height: AppSpacing.lg),
                  SizedBox(
                    width: double.infinity,
                    child: ShopButton(
                      key: const ValueKey('newbieCouponPopupCta'),
                      label: l10n.couponNewbiePopupCta,
                      variant: ShopButtonVariant.purple,
                      onTap: onRegister,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
