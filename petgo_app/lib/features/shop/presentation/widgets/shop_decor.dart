/// 电商板块的装饰件：商品图 / 占位斜纹 / 售罄蒙层 / 徽标 / 角标 / 左色条块 / 图上圆按钮。
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../../core/theme/shop_tokens.dart';
import '../../../../shared/widgets/app_image.dart';


// ============================================================
// 商品图与占位
// ============================================================

/// 商品图。1:1，圆角按用途传。
///
/// 🔴 <b>URL 为 null 或加载失败都落到斜纹占位，绝不白屏</b>（沿用既有 Toko 页的处置）。
/// 设计稿里所有商品图都是占位斜纹，那是**稿子在等运营出图**，不是产品形态 ——
/// 真实图到位后这里自动显示真图，不需要改代码。
class ShopImage extends StatelessWidget {
  const ShopImage({
    super.key,
    required this.url,
    required this.size,
    this.radius = ShopShape.radiusChip,
    this.onInk = false,
    this.fillWidth = false,
    this.fit = BoxFit.cover,
  });

  final String? url;

  /// 边长（1:1）。[fillWidth] 为 true 时它只作为**高度**，宽度撑满父约束。
  final double size;
  final double radius;

  /// 是否位于墨底之上（Diary 触发卡内的商品条）—— 占位斜纹换深色版。
  final bool onInk;

  /// 宽度撑满、只锁高度（网格卡：设计稿是 104px 高的横图，不是方图）。
  final bool fillWidth;

  /// 图片填充方式。
  ///
  /// 🔴 **默认 [BoxFit.cover]（裁切）不能改** —— 十余处调用方（购物车行、订单行、
  /// 退款选择、详情页轮播）都依赖这个默认值把图铺满各自的方框；改默认值会一次性
  /// 影响全部，而它们并没有提出「要看完整图」的诉求。
  final BoxFit fit;

  @override
  Widget build(BuildContext context) {
    final placeholder =
        _StripePlaceholder(size: size, radius: radius, onInk: onInk, fillWidth: fillWidth);
    if (url == null || url!.isEmpty) return placeholder;
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: AppImage.widget(
        url!,
        width: fillWidth ? double.infinity : size,
        height: size,
        fit: fit,
        // 物理像素：列表里的小图走 OSS 缩略图，省流量（AppImage 只对 OSS 网络图生效）。
        thumbWidth: (size * MediaQuery.devicePixelRatioOf(context)).round(),
        errorBuilder: (_, _, _) => placeholder,
      ),
    );
  }
}

class _StripePlaceholder extends StatelessWidget {
  const _StripePlaceholder({
    required this.size,
    required this.radius,
    required this.onInk,
    this.fillWidth = false,
  });

  final double size;
  final double radius;
  final bool onInk;
  final bool fillWidth;

  @override
  Widget build(BuildContext context) {
    final painter = CustomPaint(painter: _StripePainter(onInk: onInk));
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: fillWidth
          // 宽度由父约束给；CustomPaint 无 size 时会填满可用空间。
          ? SizedBox(height: size, width: double.infinity, child: painter)
          : SizedBox.square(dimension: size, child: painter),
    );
  }
}

/// 45° 重复斜纹（设计稿 `repeating-linear-gradient`）。
///
/// ⚠️ 与 `shared/widgets/design/striped_photo.dart` **有意不复用**：那个是 135°、
/// 带虚线描边与 `foto` 字标签、取全局米白色板，是原型阶段的「此处放照片」标记；
/// 本组件是电商稿的商品图占位，无标签无描边、45°、走 [ShopColors]。
class _StripePainter extends CustomPainter {
  const _StripePainter({required this.onInk});

  final bool onInk;

  @override
  void paint(Canvas canvas, Size size) {
    final colors = onInk ? ShopColors.placeholderStripeOnInk : ShopColors.placeholderStripe;
    // 设计稿：白底之上 5px/10px 一个周期；墨底之上 4px/8px。
    final band = onInk ? 4.0 : 5.0;
    final period = band * 2;

    canvas.drawRect(Offset.zero & size, Paint()..color = colors[1]);
    canvas.save();
    canvas.clipRect(Offset.zero & size);
    canvas.translate(size.width / 2, size.height / 2);
    canvas.rotate(45 * math.pi / 180);
    final span = (size.width + size.height) * 1.5;
    canvas.translate(-span / 2, -span / 2);
    final paint = Paint()..color = colors[0];
    for (double x = 0; x < span; x += period) {
      canvas.drawRect(Rect.fromLTWH(x, 0, band, span), paint);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _StripePainter old) => old.onInk != onInk;
}

/// 售罄蒙层。盖在商品图之上。
///
/// 三档浓度对应三个场景（见 [ShopColors] 的三个 scrim 常量）：详情页整图 .5、
/// 网格/列表卡 .58、购物车失效分组 .45。
class ShopSoldOutOverlay extends StatelessWidget {
  const ShopSoldOutOverlay({
    super.key,
    required this.label,
    required this.scrim,
    this.subtitle,
    this.labelSize = 11,
    this.radius = ShopShape.radiusChip,
  });

  final String label;
  final Color scrim;

  /// 详情页的第二行（`Perkiraan tersedia lagi 3–5 hari`）。
  ///
  /// 🔴 到货时间**必须是区间或不显示**。设计稿把 `segera`（很快）列入禁用文案 ——
  /// 无信息量的承诺比不承诺更伤信任。后台无补货计划时调用方传 `null`。
  final String? subtitle;
  final double labelSize;
  final double radius;

  @override
  Widget build(BuildContext context) {
    return Positioned.fill(
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: ColoredBox(
          color: scrim,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(label,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                        fontSize: labelSize,
                        fontWeight: FontWeight.w700,
                        color: ShopColors.surface)),
                if (subtitle != null) ...[
                  const SizedBox(height: 4),
                  Text(subtitle!,
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                          fontSize: 11, color: ShopColors.surface, height: 1.4)),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ============================================================
// 徽标与角标
// ============================================================

/// 类型徽标（订单卡的 `TOKO` / `KONSULTASI`）与推荐来源标签共用。
/// 9px/700，内边距 `3px 6px`，圆角 3px。
class ShopBadge extends StatelessWidget {
  const ShopBadge({
    super.key,
    required this.label,
    required this.background,
    required this.foreground,
  });

  final String label;
  final Color background;
  final Color foreground;

  /// 电商订单徽标（玫红系）——「实物、需等快递」。
  factory ShopBadge.toko(String label) => ShopBadge(
      label: label, background: ShopColors.accentBg, foreground: ShopColors.accentDark);

  /// 虚拟服务徽标（紫系）——问诊等。
  factory ShopBadge.service(String label) => ShopBadge(
      label: label, background: ShopColors.purpleTagBg, foreground: ShopColors.purpleDark);

  /// 推荐来源标签（紫系）。🔴 每张个性化推荐卡**必须**带它且能指回一条具体记录（含日期）；
  /// 取不到来源的商品不能进个性化区。
  factory ShopBadge.recoSource(String label) => ShopBadge(
      label: label, background: ShopColors.purpleTagBg, foreground: ShopColors.purple);

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(ShopShape.radiusBadge),
        ),
        child: Text(label, style: ShopText.badge.copyWith(color: foreground)),
      );
}

/// 折扣角标（`-20%`）。贴商品图右上角，圆角 `0 8px 0 6px`。
///
/// 🔴 <b>不允许单独出现</b>：设计稿要求促销价与划线原价成对出现，后台无原价则两者
/// 与本角标**都不显示**。调用方在原价为空时不要渲染本组件。
class ShopDiscountCorner extends StatelessWidget {
  const ShopDiscountCorner({super.key, required this.label});

  final String label;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
        decoration: const BoxDecoration(
          color: ShopColors.accent,
          borderRadius: BorderRadius.only(
            topRight: Radius.circular(ShopShape.radiusButton),
            bottomLeft: Radius.circular(ShopShape.radiusChip),
          ),
        ),
        child: Text(label,
            style: ShopText.badge
                .copyWith(color: ShopColors.surface, fontWeight: FontWeight.w800)),
      );
}

/// 商品图上的半透明圆角按钮（返回 / 收藏 / 购物车 / 页码）。30×30。
class ShopImageButton extends StatelessWidget {
  const ShopImageButton({super.key, required this.icon, this.onTap, this.semanticLabel});

  final IconData icon;
  final VoidCallback? onTap;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        label: semanticLabel,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(ShopShape.radiusButton),
          child: Container(
            width: 30,
            height: 30,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: ShopColors.imageButtonScrim,
              borderRadius: BorderRadius.circular(ShopShape.radiusButton),
            ),
            child: Icon(icon, size: 16, color: ShopColors.surface),
          ),
        ),
      );
}

// ============================================================
// 左色条块
// ============================================================

/// 左色条块 —— 支付方式条、退款去向拆分段、紫色说明块。
///
/// 🔴 <b>表示既定结果，不可点选</b>。设计稿明令<b>不要加单选控件外观</b>：
/// 「一旦给出选择控件，用户会预期能选真钱」。混合支付的构成与退款去向都由下单时的
/// 支付快照决定，用户无从选择，页面只做告知 + 确认。
///
/// 色条语义在结算页 / 待支付页 / 退款页**一一对应**（紫 = PawCoin、玫红 = 真钱），
/// 用户据此把「怎么付的」和「怎么退的」对上 —— 因此三处必须用同一个组件同一套配色。
class ShopLeftAccentBlock extends StatelessWidget {
  const ShopLeftAccentBlock({
    super.key,
    required this.accent,
    required this.background,
    required this.child,
    this.padding = const EdgeInsets.fromLTRB(12, 10, 12, 10),
  });

  final Color accent;
  final Color background;
  final Widget child;
  final EdgeInsets padding;

  /// 紫 —— PawCoin / 平台能力侧。
  factory ShopLeftAccentBlock.pawcoin({Key? key, required Widget child}) =>
      ShopLeftAccentBlock(
          key: key, accent: ShopColors.purple, background: ShopColors.purpleBg, child: child);

  /// 玫红 —— 真钱侧（QRIS）与错误条。
  factory ShopLeftAccentBlock.money({Key? key, required Widget child}) =>
      ShopLeftAccentBlock(
          key: key, accent: ShopColors.accent, background: ShopColors.accentBg, child: child);

  /// 置灰 —— 超服务范围时下游支付块整体降权。
  factory ShopLeftAccentBlock.muted({Key? key, required Widget child}) =>
      ShopLeftAccentBlock(
          key: key,
          accent: ShopColors.disabledText,
          background: const Color(0xFFF5F3F9),
          child: child);

  @override
  Widget build(BuildContext context) => Container(
        // 🔴 撑满宽度。缺了它，child 是纯 Text 时整块会缩成文字那么宽 ——
        //    真机上地址页的范围提示就是这样只占了半行，看着像个标签而不是一个提示块。
        //    支付方式那几处没暴露这个问题，只是因为它们的 child 恰好是带 Expanded 的 Row。
        width: double.infinity,
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(ShopShape.radiusPayRow),
          border: Border(left: BorderSide(color: accent, width: 3)),
        ),
        padding: padding,
        child: child,
      );
}

/// 橙色警示块（开封不退 / 运费归属 / PawCoin 不可提现）。
///
/// 🔴 这三处文案属于**文案一致性契约**（设计稿 04 末尾）：开封不退在 3 处同文案、
/// PawCoin 不可提现在 3 处同文案，改一处必须同步全部。故文案一律走 l10n 常量 key，
/// **不要在调用点写字面量**。
class ShopWarnBlock extends StatelessWidget {
  const ShopWarnBlock({super.key, required this.title, required this.body, this.bordered = false});

  final String title;
  final String body;
  final bool bordered;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 11),
        decoration: BoxDecoration(
          color: ShopColors.warnBg,
          borderRadius: BorderRadius.circular(ShopShape.radiusPayRow),
          border: bordered ? Border.all(color: ShopColors.warnBorder) : null,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title,
                style: const TextStyle(
                    fontSize: 11.5, fontWeight: FontWeight.w700, color: ShopColors.warnTitle)),
            const SizedBox(height: 3),
            Text(body,
                style: const TextStyle(
                    fontSize: 10.5, height: 1.7, color: ShopColors.warnText)),
          ],
        ),
      );
}
