/// 券面组件（v1.4.2 shop Story 7.1 首建；8.1 / 8.2 / 9.3 复用）。
///
/// 两种模式：
/// - **抽屉单选**（结算页 D10 / D11）：传 [selected] + [onTap]，右侧画单选圆；
/// - **按钮位**（领券，8.x）：传 [trailing]，右侧放调用方给的按钮。
/// - **紧凑竖排**（8.1 首页「Kupon buat kamu」横滑，两张占满一行）：[compact] = true，
///   面额 → 门槛 / 封顶 → 按钮位自上而下；[accent] = 紫底白字（新人券），[badge] = 左上角标。
/// - **我的券**（9.3，横排）：[validityText] 换掉默认有效期行（「s/d {date}」/「Berakhir {datetime}」），
///   [tag] = 名称旁小标签（「Baru」），[extraLines] = 右栏末尾追加行（范围 / 占用 / 「Dipakai…」），
///   [dimmed] = 整卡不透明度 0.55 且不画「Segera berakhir」（已使用 G2）。四者默认值保持 7.1 / 8.x 原样。
///
/// 🔴 面额 / 门槛 / 封顶全是服务端值，本组件只格式化（`formatIdr` / `formatIdrAmount`），不算。
library;

import 'package:flutter/material.dart';

import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/format/idr_compact.dart';
import '../../../../shared/utils/date_format.dart';
import '../../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../../domain/checkout_preview.dart';
import '../../domain/shop_product.dart' show formatIdr;
import '../../presentation/widgets/shop_pressable.dart';

class CouponCard extends StatelessWidget {
  const CouponCard({
    super.key,
    required this.coupon,
    this.selected,
    this.onTap,
    this.trailing,
    this.reasonText,
    this.disabled = false,
    this.compact = false,
    this.accent = false,
    this.badge,
    this.dimmed = false,
    this.tag,
    this.validityText,
    this.extraLines = const [],
  });

  /// 已使用降权（9.3 G2）：整卡 0.55 不透明度、不画「Segera berakhir」。默认 false。
  final bool dimmed;

  /// 名称旁小标签（9.3 G6「Baru」）。null = 不画。
  final String? tag;

  /// 替换默认有效期行的文案（已本地化）。null = 7.1 原样「Berlaku sampai {date}」。
  final String? validityText;

  /// 右栏末尾追加的行（9.3：范围 / 占用说明 / 「Dipakai {date} · {no}」）。默认空。
  final List<Widget> extraLines;

  /// 紧凑竖排（8.1）。默认 false = 7.1 横排原样。
  final bool compact;

  /// 紫底白字（新人券，仅 [compact] 生效）。
  final bool accent;

  /// 左上角标（如「KHUSUS PENGGUNA BARU」，仅 [compact] 生效）。
  final String? badge;

  final CouponOption coupon;

  /// 非 null = 抽屉单选模式。
  final bool? selected;
  final VoidCallback? onTap;

  /// 领券模式的按钮位（与 [selected] 二选一）。
  final Widget? trailing;

  /// 不可用原因（已本地化）；null = 不显示原因。
  final String? reasonText;

  /// 置灰、不可点（「Belum bisa dipakai」组）。
  final bool disabled;

  @override
  Widget build(BuildContext context) {
    if (compact) return _buildCompact(context);
    final l10n = AppLocalizations.of(context);
    final lang = Localizations.localeOf(context).languageCode;
    final isFs = coupon.kind == CouponKind.freeShipping;
    final on = selected == true && !disabled;
    final faceColor = disabled ? ShopColors.text4 : ShopColors.purple;
    final until = coupon.validUntil;
    final name = coupon.nameFor(lang);

    final card = Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: disabled ? ShopColors.bg : ShopColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: on ? ShopColors.purple : ShopColors.border2,
          width: on ? 1.5 : 1,
        ),
      ),
      child: Row(
        children: [
          SizedBox(
            width: 96,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  isFs ? l10n.checkoutCouponFreeShipping : formatIdr(coupon.amount ?? 0),
                  key: const ValueKey('couponFace'),
                  style: AppTypography.body.copyWith(
                    fontWeight: FontWeight.w700,
                    color: faceColor,
                    fontFeatures: const [FontFeature.tabularFigures()],
                  ),
                ),
                // 免运券：只显示封顶金额（「Ongkir Maks.」不在 §6，见 story 待确认 4）
                if (isFs && coupon.amount != null)
                  Text(formatIdr(coupon.amount!),
                      style: AppTypography.caption.copyWith(
                          color: faceColor,
                          fontFeatures: const [FontFeature.tabularFigures()])),
              ],
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 复审 #5：无 tag 时保持 7.1 / 8.x 原组件树（只在 tag != null 时包 Row）。
                if (tag == null && name.isNotEmpty)
                  Text(name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: AppTypography.body.copyWith(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: disabled ? ShopColors.text3 : ShopColors.text))
                else if (tag != null)
                  Row(
                    children: [
                      if (name.isNotEmpty)
                        Flexible(
                          child: Text(name,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: AppTypography.body.copyWith(
                                  fontSize: 14,
                                  fontWeight: FontWeight.w600,
                                  color: disabled ? ShopColors.text3 : ShopColors.text)),
                        ),
                      if (tag != null) ...[
                        const SizedBox(width: 6),
                        Container(
                          key: const ValueKey('couponTag'),
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                          decoration: BoxDecoration(
                            color: ShopColors.purple,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(tag!,
                              style: AppTypography.micro.copyWith(
                                  color: ShopColors.surface, fontWeight: FontWeight.w700)),
                        ),
                      ],
                    ],
                  ),
                if ((coupon.minSpend ?? 0) > 0)
                  Text(l10n.couponMinSpend(formatIdrAmount(coupon.minSpend!)),
                      style: AppTypography.caption),
                if (validityText != null)
                  Text(validityText!,
                      key: const ValueKey('couponValidity'), style: AppTypography.caption)
                else if (until != null)
                  Text(l10n.qrPaySavedValidUntil(formatDayMonthYear(context, until.toLocal())),
                      style: AppTypography.caption),
                if (coupon.expiringSoon && !disabled && !dimmed)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(l10n.couponExpiringSoon,
                        key: const ValueKey('couponExpiringSoon'),
                        style: AppTypography.caption.copyWith(color: ShopColors.errorText)),
                  ),
                if (reasonText != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(reasonText!,
                        key: const ValueKey('couponReason'),
                        style: AppTypography.caption.copyWith(color: ShopColors.text3)),
                  ),
                ...extraLines,
              ],
            ),
          ),
          if (trailing != null) ...[const SizedBox(width: 8), trailing!],
          if (selected != null && !disabled) ...[
            const SizedBox(width: 8),
            _Radio(on: on),
          ],
        ],
      ),
    );
    if (dimmed) {
      return Opacity(key: const ValueKey('couponDimmed'), opacity: 0.55, child: card);
    }
    if (disabled || onTap == null) return card;
    return Semantics(
      inMutuallyExclusiveGroup: selected != null,
      selected: on,
      button: true,
      child: ShopPressable(onTap: onTap, child: card),
    );
  }
}

extension on CouponCard {
  /// 首页横滑窄卡（UI A1 `.v-cp`）：左侧 4px 色条（满减紫 / 免运青）+ 面额（rb 简写）与门槛 / 封顶两行 + 右侧按钮；
  /// 新人券（[accent]）紫底白字、角标在上、按钮位在右下。宽度由调用方给（172 / 新人券 264）。
  Widget _buildCompact(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isFs = coupon.kind == CouponKind.freeShipping;
    final stripe = isFs ? ShopColors.shipTeal : ShopColors.purple;
    final fg = accent ? ShopColors.surface : (isFs ? ShopColors.shipTeal : ShopColors.purpleDark);
    final sub = accent ? ShopColors.surface : ShopColors.text2;
    // 满减：面额「Rp 20rb」+「Min. Rp 300rb」；免运：「Ongkir」+「Maks. Rp 15rb」（封顶）。
    final face = isFs ? l10n.couponStripShipping : _rbShort(coupon.amount ?? 0);
    final String? subLine = isFs
        ? (coupon.amount != null ? l10n.couponStripMax(_rbShort(coupon.amount!)) : null)
        : ((coupon.minSpend ?? 0) > 0 ? l10n.couponStripMin(_rbShort(coupon.minSpend!)) : null);
    final card = Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 12, 12),
      color: accent ? ShopColors.purple : ShopColors.surface,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        crossAxisAlignment: accent ? CrossAxisAlignment.end : CrossAxisAlignment.center,
        children: [
          Flexible(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (badge != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 2),
                    child: Text(badge!,
                        key: const ValueKey('couponBadge'),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.micro.copyWith(
                            color: ShopColors.newbieBadge,
                            fontWeight: FontWeight.w700,
                            letterSpacing: .4)),
                  ),
                Text(
                  face,
                  key: const ValueKey('couponFace'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.body.copyWith(
                    fontSize: 16,
                    height: 1.25,
                    fontWeight: FontWeight.w800,
                    color: fg,
                    fontFeatures: const [FontFeature.tabularFigures()],
                  ),
                ),
                if (subLine != null)
                  Text(subLine,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: AppTypography.caption.copyWith(fontSize: 11, color: sub)),
              ],
            ),
          ),
          if (trailing != null) ...[const SizedBox(width: 8), trailing!],
        ],
      ),
    );
    // 左侧色条：圆角卡上不能用非统一 Border（Flutter 断言），改为裁圆角后叠一条 4px 色块（同稿 inset box-shadow）。
    final framed = ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: Stack(
        children: [
          card,
          // 页底色比稿浅，白卡会「消失」：补一圈极淡描边（新人券紫底不需要）。
          if (!accent)
            Positioned.fill(
              child: IgnorePointer(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: ShopColors.border2),
                  ),
                ),
              ),
            ),
          if (!accent)
            Positioned(left: 0, top: 0, bottom: 0, width: 4, child: ColoredBox(color: stripe)),
        ],
      ),
    );
    if (onTap == null) return framed;
    return Semantics(button: true, child: ShopPressable(onTap: onTap, child: framed));
  }
}

/// 「Rp 20rb」「Rp 1,5jt」：券卡窄位用的简写（规则同 [formatIdrCompact]，Rp 后带空格对齐稿件）。
String _rbShort(int amount) {
  final p = idrCompactParts(amount);
  return 'Rp ${p.number}${p.unit}';
}

class _Radio extends StatelessWidget {
  const _Radio({required this.on});

  final bool on;

  @override
  Widget build(BuildContext context) => Container(
        width: 20,
        height: 20,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: on ? ShopColors.purple : Colors.transparent,
          border: on ? null : Border.all(color: ShopColors.border, width: 1.5),
        ),
        child: on ? const Icon(Icons.check, size: 13, color: ShopColors.surface) : null,
      );
}

/// 不可用原因文案（满减 / 免运两种券的 `OUT_OF_SCOPE` 文案不同）。未知 / 手选失效 → null（不显示原因）。
String? couponReasonText(AppLocalizations l10n, CouponOption c) => switch (c.reason) {
      CouponUnavailableReason.belowMinSpend => l10n.couponReasonMinSpend,
      CouponUnavailableReason.outOfScope => c.kind == CouponKind.freeShipping
          ? l10n.couponReasonSomeExcluded
          : l10n.couponReasonProductExcluded,
      CouponUnavailableReason.expired => l10n.couponReasonExpired,
      CouponUnavailableReason.ruleBetter => l10n.couponReasonRuleBetter,
      CouponUnavailableReason.unavailable || CouponUnavailableReason.unknown || null => null,
    };
