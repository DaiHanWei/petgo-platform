import 'package:flutter/material.dart';

import '../../../../core/theme/shop_tokens.dart';

/// 电商板块的按钮。
///
/// 设计稿里按钮出现了 8 种外观，但它们只是**同一个形状的配色变体**（圆角 8px、
/// 无阴影、字重 700）。因此这里做成一个 [ShopButton] + [ShopButtonVariant] 枚举，
/// 而不是 8 个 widget —— 后者必然在某一处漏掉「无阴影」或写错圆角。
///
/// 🔴 <b>不用 Material 的 FilledButton/OutlinedButton</b>：它们带 M3 的 elevation、
/// 波纹溢出与 40px 最小高度，三者都和本设计冲突（设计稿明令产品 UI 内不使用阴影，
/// 且小按钮实测高度 30–34px）。改用 [InkWell] + [DecoratedBox] 自绘。
enum ShopButtonVariant {
  /// 强调实色 —— **未完成的付款动作**与促销转化（`Bayar` / `Checkout` / `Beli Lagi`）。
  ///
  /// ⚠️ 2026-08-21 由玫红改为品牌紫，与 [ShopButtonVariant.purple] 当前**同色**。
  /// 两个变体**刻意不合并**：它们表达的是不同语义（付钱 vs 平台能力），
  /// 合并后日后想重新拉开，就得回头逐个辨认 25 个调用点当初是哪一类。
  pay,

  /// 紫实色 —— 平台能力侧的主操作（`Lihat Alternatif` / `Ya, ingatkan saya`）。
  purple,

  /// 墨底实色 —— 中性主操作（`+ Keranjang` / `Barang Diterima`）。
  ink,

  /// 浅描边 + 灰字 —— 低权重次操作（`Batalkan` / `Hapus` / `Ajukan Kembali`）。
  outlineMuted,

  /// 紫描边 + 紫字 —— 有明确去向的次操作（`Lacak` / `Cari mirip`）。
  outlinePurple,

  /// 白描边 + 白字，**用于墨底之上**（Diary 触发卡的 `Nanti`）。
  outlineOnInk,

  /// 置灰不可点（售罄的 `Stok Habis`、超服务范围的 `Bayar`）。
  ///
  /// 🔴 <b>置灰但不消失</b>：设计稿刻意保留主按钮的位置，传达「同一个页面、只是买不了」。
  /// 真正的出口在旁边的次按钮。**不要因为不可点就把它 remove 掉。**
  disabled,
}

class ShopButton extends StatelessWidget {
  const ShopButton({
    super.key,
    required this.label,
    required this.variant,
    this.onTap,
    this.subtitle,
    this.dense = false,
    this.padding,
  });

  final String label;
  final ShopButtonVariant variant;

  /// `null` 或 [variant] 为 [ShopButtonVariant.disabled] 时不可点。
  final VoidCallback? onTap;

  /// 副文案（详情页 `Beli Sekarang` 的第二行 `Bayar Rp 154.000`）。
  ///
  /// 🔴 副文案是 **售价 − 可抵扣 PawCoin，未含运费**，故设计稿要求必须带 `Bayar` 前缀
  /// 以示非最终金额。文案由调用方按 l10n 拼好，本组件只负责排版。
  /// 无 PawCoin 余额时调用方传 `null`，第二行整行消失（不留空行）。
  final String? subtitle;

  /// 小尺寸（行内按钮：`Beli Lagi` / `Cari mirip` / `Hapus`）。
  final bool dense;

  final EdgeInsets? padding;

  bool get _enabled => onTap != null && variant != ShopButtonVariant.disabled;

  @override
  Widget build(BuildContext context) {
    final (Color bg, Color fg, Color? border) = switch (variant) {
      ShopButtonVariant.pay => (ShopColors.accent, ShopColors.surface, null),
      ShopButtonVariant.purple => (ShopColors.purple, ShopColors.surface, null),
      ShopButtonVariant.ink => (ShopColors.ink, ShopColors.surface, null),
      ShopButtonVariant.outlineMuted => (
          ShopColors.surface,
          ShopColors.text3,
          ShopColors.border2,
        ),
      ShopButtonVariant.outlinePurple => (
          ShopColors.surface,
          ShopColors.purple,
          ShopColors.border,
        ),
      ShopButtonVariant.outlineOnInk => (Colors.transparent, ShopColors.onInk85, ShopColors.onInk20),
      ShopButtonVariant.disabled => (ShopColors.border2, ShopColors.text4, null),
    };

    final effectivePadding = padding ??
        (dense
            ? const EdgeInsets.symmetric(horizontal: 12, vertical: 9)
            : const EdgeInsets.symmetric(horizontal: 20, vertical: 13));

    final textStyle =
        (dense ? ShopText.buttonSecondary : ShopText.buttonPrimary).copyWith(color: fg);

    return Semantics(
      button: true,
      enabled: _enabled,
      child: InkWell(
        onTap: _enabled ? onTap : null,
        borderRadius: BorderRadius.circular(ShopShape.radiusButton),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(ShopShape.radiusButton),
            border: border == null ? null : Border.all(color: border),
          ),
          child: Padding(
            padding: effectivePadding,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(label, textAlign: TextAlign.center, style: textStyle),
                if (subtitle != null) ...[
                  const SizedBox(height: 1),
                  Text(
                    subtitle!,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                        fontSize: 9.5, fontWeight: FontWeight.w400, color: fg.withValues(alpha: .85)),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
