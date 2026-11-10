/// 券选择抽屉（v1.4.2 shop Story 7.1 · D10 满减 / D11 免运）。
///
/// 「Bisa dipakai」组按服务端顺序（抵扣额从大到小）、单选、默认选中当前券；「Tidak pakai kupon」单选项；
/// 「Belum bisa dipakai」组置灰、不可选、标原因。底部「Pakai」确认后关闭并返回所选。
/// 被其他待付款订单占用的券服务端不下发（App 不做过滤）。
library;

import 'package:flutter/material.dart';

import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/checkout_preview.dart';
import '../../presentation/widgets/shop_buttons.dart';
import '../../presentation/widgets/checkout_sheets.dart' show CheckoutSheetHeader;
import '../../presentation/widgets/shop_controls.dart';
import 'coupon_card.dart';

/// 抽屉结果：[token] 为 null = 不用券。
class CouponPick {
  const CouponPick(this.token);
  final String? token;
}

/// 打开券抽屉。关闭（点遮罩 / 关闭键）返回 null = 不改。
Future<CouponPick?> showCouponPickerSheet(
  BuildContext context, {
  required CouponKind kind,
  required CouponGroup group,
}) =>
    showModalBottomSheet<CouponPick>(
      context: context,
      isScrollControlled: true,
      backgroundColor: ShopColors.surface,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
      builder: (_) => _CouponPickerSheet(kind: kind, group: group),
    );

class _CouponPickerSheet extends StatefulWidget {
  const _CouponPickerSheet({required this.kind, required this.group});

  final CouponKind kind;
  final CouponGroup group;

  @override
  State<_CouponPickerSheet> createState() => _CouponPickerSheetState();
}

class _CouponPickerSheetState extends State<_CouponPickerSheet> {
  late String? _token = widget.group.selectedToken;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final g = widget.group;
    final title = widget.kind == CouponKind.freeShipping
        ? l10n.couponFreeShippingSheetTitle
        : l10n.couponDiscountSheetTitle;
    return FractionallySizedBox(
      heightFactor: .8,
      child: SafeArea(
        top: false,
        child: Column(
          children: [
            CheckoutSheetHeader(title: title),
            Expanded(
              child: ListView(
                key: const ValueKey('couponSheetList'),
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                children: [
                  _groupTitle(l10n.couponAvailableCount(g.available.length)),
                  for (final c in g.available)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: CouponCard(
                        key: ValueKey('couponOption_${c.userCouponToken}'),
                        coupon: c,
                        selected: _token == c.userCouponToken,
                        onTap: () => setState(() => _token = c.userCouponToken),
                      ),
                    ),
                  ShopRadioTile(
                    key: const ValueKey('couponDontUse'),
                    label: l10n.couponDontUse,
                    selected: _token == null,
                    onTap: () => setState(() => _token = null),
                  ),
                  if (g.unavailable.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    _groupTitle(l10n.couponUnavailableCount(g.unavailable.length)),
                    for (final c in g.unavailable)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: CouponCard(
                          key: ValueKey('couponUnavailable_${c.userCouponToken}'),
                          coupon: c,
                          disabled: true,
                          reasonText: couponReasonText(l10n, c),
                        ),
                      ),
                  ],
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: SizedBox(
                width: double.infinity,
                height: 48,
                child: ShopButton(
                  key: const ValueKey('couponApply'),
                  label: l10n.couponApply,
                  variant: ShopButtonVariant.purple,
                  onTap: () => Navigator.of(context).pop(CouponPick(_token)),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _groupTitle(String text) => Padding(
        padding: const EdgeInsets.only(top: 4, bottom: 8),
        child: Text(text,
            style: AppTypography.caption.copyWith(fontWeight: FontWeight.w600)),
      );
}
