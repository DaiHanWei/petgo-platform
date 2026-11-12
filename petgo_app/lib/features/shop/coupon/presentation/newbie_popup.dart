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

/// 弹出 A4。`toko_newbie_coupon_popup_shown` 在弹窗首帧后上报一次（analytics-pipeline Story 3.1：
/// 曝光按「露出超过 50%」计，弹出前上报时弹窗还没露出），与 `OpsPopupDialog.onShown` 同一做法。
///
/// 三个事件都带运营位 `placement_type = newbie_coupon` 与 `placement_token = campaignToken`；
/// token 解析失败（空串）时不带 `placement_token`，不上报空字符串。
Future<void> showNewbieCouponPopup(BuildContext context, NewbieCampaign campaign) {
  final placementToken = campaign.campaignToken.isEmpty ? null : campaign.campaignToken;
  return showDialog<void>(
    context: context,
    builder: (dialogContext) => NewbieCouponPopup(
      campaign: campaign,
      onShown: () {
        Analytics.capture('toko_newbie_coupon_popup_shown', {
          'coupon_id': campaign.campaignToken,
          'placement_type': 'newbie_coupon',
          'placement_token': ?placementToken,
        });
      },
      onClose: () {
        Analytics.capture('toko_newbie_coupon_popup_tapped', {
          'coupon_id': campaign.campaignToken,
          'action': 'close',
          'placement_type': 'newbie_coupon',
          'placement_token': ?placementToken,
        });
        Navigator.of(dialogContext).pop();
      },
      onRegister: () {
        Analytics.capture('toko_newbie_coupon_popup_tapped', {
          'coupon_id': campaign.campaignToken,
          'action': 'register',
          'placement_type': 'newbie_coupon',
          'placement_token': ?placementToken,
        });
        Navigator.of(dialogContext).pop();
        context.push('/login');
      },
    ),
  );
}

class NewbieCouponPopup extends StatefulWidget {
  const NewbieCouponPopup({
    super.key,
    required this.campaign,
    required this.onClose,
    required this.onRegister,
    this.onShown,
  });

  final NewbieCampaign campaign;
  final VoidCallback onClose;
  final VoidCallback onRegister;

  /// 首帧后回调一次（`toko_newbie_coupon_popup_shown`）；同一实例 rebuild 不重复。
  final VoidCallback? onShown;

  @override
  State<NewbieCouponPopup> createState() => _NewbieCouponPopupState();
}

class _NewbieCouponPopupState extends State<NewbieCouponPopup> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onShown?.call();
    });
  }

  @override
  Widget build(BuildContext context) {
    final campaign = widget.campaign;
    final onClose = widget.onClose;
    final onRegister = widget.onRegister;
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
