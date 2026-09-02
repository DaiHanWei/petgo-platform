/// 电商板块的表单控件：步进器 / 勾选框 / 单选 / 开关 / chips / 三档 segmented。
///
/// 🔴 <b>为什么全部自绘而不用 Material 原生</b>：设计稿把这几个控件的尺寸定死了
/// （勾选框 18×18、单选 15px、开关 38×22 与 34×19、步进器 22×22），而 Material 的
/// `Checkbox`/`Radio`/`Switch` <b>尺寸不可精确控制</b> —— `Switch` 恒 ~52×32 且无尺寸参数，
/// `Checkbox` 靠 `MaterialTapTargetSize` 只能在 48 与 40 之间二选一。
/// 硬套原生的结果是每一处都差几个像素，且没有任何办法对齐。
///
/// ⚠️ 自绘的代价是**触控区**：视觉 15–22px 远小于 44px 的可点最小尺寸。
/// 因此每个控件都用 [ShopPressable] 把命中区域撑到 44×44（视觉不变），
/// 不要为了「紧凑」把它去掉 —— 那会让购物车的勾选框实际点不中。
///
/// 🔴 2026-08-27：原本文件私有的 `_TapTarget` 已提升为 `widgets/shop_pressable.dart`
/// 的 [ShopPressable]，因为**页面层没有享受到这份纪律** —— 商品详情页的返回按钮
/// (`ShopImageButton`) 只有 30×30，顶栏购物车 30×30，六个「文字 + ›」按钮约 30×23。
/// 同时它顺带补上了这套控件此前完全没有的**按下反馈**。
///
/// 🔴 无障碍：自绘控件不会像 Material 的 `Checkbox`/`Switch` 那样自带角色与名称，
/// 因此每个控件都收一个 [semanticLabel]。**不要嫌麻烦省掉** —— 少了它，读屏用户
/// 听到的是「已勾选」而不知道勾的是什么。
library;

import 'package:flutter/material.dart';

import '../../../../core/theme/shop_tokens.dart';
import 'shop_pressable.dart';


// ============================================================
// 步进器
// ============================================================

/// 数量步进器（购物车行、退货申请行）。
///
/// 22×22 描边方块 + 12px/600 数量，间距 8–9px。
///
/// 🔴 <b>触顶不是禁用整个控件，只禁用 `+`</b>：数量上限 = 当前库存，触顶后 `+` 的边框与
/// 符号转灰，`−` 仍可点。设计稿同时要求在标签处提示 `Sisa n` —— 那部分由调用方渲染，
/// 本组件只负责按钮态。
///
/// 🔴 <b>[onRemove] 非空时，`−` 在触底那一刻变成垃圾桶</b>（2026-08-27 补回）。
/// v1 的 `cart_page.dart` 一直是这么做的，v2 改版时漏了，导致 2026-08-21 默认变体
/// 翻到 v2 之后**线上用户无法从购物车里删除商品** —— 减到 1 就到底，没有任何删除入口。
/// 图标必须跟着换：否则用户点了「−」东西却整行消失，会以为自己点错了。
/// 退货申请页不传 [onRemove]（那里的数量是「退几件」，退 0 件没有意义）。
class ShopStepper extends StatelessWidget {
  const ShopStepper({
    super.key,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
    this.onRemove,
    this.decrementLabel,
    this.incrementLabel,
    this.removeLabel,
  });

  final int value;
  final int min;

  /// 上限（= 当前库存 / 原购数）。
  final int max;
  final ValueChanged<int>? onChanged;

  /// 触底再减 = 删除整行。为 null 时触底即禁用 `−`（原行为）。
  final VoidCallback? onRemove;

  /// 无障碍名称。自绘控件不带角色与名称，读屏只会念出 `−` / `+` 这两个符号。
  final String? decrementLabel;
  final String? incrementLabel;
  final String? removeLabel;

  @override
  Widget build(BuildContext context) {
    final atFloor = value <= min;
    final isRemove = atFloor && onRemove != null;
    final canDec = onChanged != null && (!atFloor || isRemove);
    final canInc = onChanged != null && value < max;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _box(
          isRemove ? null : '−',
          canDec,
          isRemove ? onRemove! : () => onChanged!(value - 1),
          'stepperDec',
          isRemove ? removeLabel : decrementLabel,
          icon: isRemove ? Icons.delete_outline : null,
        ),
        SizedBox(
          width: 30,
          child: Text('$value',
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 12, fontWeight: FontWeight.w600, color: ShopColors.text)),
        ),
        _box('+', canInc, () => onChanged!(value + 1), 'stepperInc', incrementLabel),
      ],
    );
  }

  Widget _box(String? glyph, bool enabled, VoidCallback onTap, String keyName,
      String? semanticLabel,
      {IconData? icon}) {
    final fg = enabled ? ShopColors.text2 : ShopColors.disabledText;
    return Semantics(
      button: true,
      enabled: enabled,
      label: semanticLabel,
      child: ShopPressable(
        enabled: enabled,
        onTap: onTap,
        minSize: kShopMinTapTarget,
        child: Container(
          width: 22,
          height: 22,
          key: ValueKey(keyName),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            border: Border.all(color: enabled ? ShopColors.border : ShopColors.border2),
            borderRadius: BorderRadius.circular(ShopShape.radiusPayRow),
          ),
          child: icon != null
              ? Icon(icon, size: 14, color: fg)
              : Text(glyph!, style: TextStyle(fontSize: 13, height: 1, color: fg)),
        ),
      ),
    );
  }
}

// ============================================================
// 勾选框
// ============================================================

/// 勾选框。18×18（购物车 / 退货行）或 15×15（结算页协议）。
///
/// 选中 = 玫红实底 + 白 `✓`；未选 = 空块。
/// [enabled] 为 false 时（购物车失效分组）改为浅底空块且不可点 —— **不是灰色的勾**。
class ShopCheckbox extends StatelessWidget {
  const ShopCheckbox({
    super.key,
    required this.value,
    required this.onChanged,
    this.size = 18,
    this.enabled = true,
    this.semanticLabel,
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final double size;
  final bool enabled;

  /// 无障碍名称。🔴 不给的话读屏只会念「已勾选」，勾的是什么完全听不出来 ——
  /// 结算页那个勾选框勾的是「开封不退」协议（FR-104 合规位），尤其不能没有名字。
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final interactive = enabled && onChanged != null;
    return Semantics(
      checked: value,
      enabled: interactive,
      label: semanticLabel,
      child: ShopPressable(
        enabled: interactive,
        onTap: () => onChanged!(!value),
        minSize: kShopMinTapTarget,
        child: Container(
          width: size,
          height: size,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: !enabled
                ? ShopColors.border2
                : (value ? ShopColors.accent : ShopColors.surface),
            borderRadius: BorderRadius.circular(size >= 18 ? 4 : 3),
            border: (enabled && !value)
                ? Border.all(color: ShopColors.border, width: 1.5)
                : null,
          ),
          child: value && enabled
              ? Icon(Icons.check, size: size * .72, color: ShopColors.surface)
              : null,
        ),
      ),
    );
  }
}

// ============================================================
// 单选（仅退货原因）
// ============================================================

/// 退货原因单选项 —— **整行是一个选项容器**，不是「圆点 + 旁边一行字」。
///
/// 选中：1.5px 玫红边 + [ShopColors.accentBg] 底 + 文字转 700 + 右侧玫红实心圆 `✓`
/// 未选：1px [ShopColors.border2] 边 + 右侧空心圆
///
/// 🔴 <b>只有退货原因用单选</b>。支付方式与退款去向虽然长得像，但它们表示**既定结果、
/// 不可选**，设计稿明令不得加单选控件外观 —— 见 [ShopLeftAccentBlock]（shop_decor.dart）。
class ShopRadioTile extends StatelessWidget {
  const ShopRadioTile({
    super.key,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      inMutuallyExclusiveGroup: true,
      selected: selected,
      button: true,
      child: ShopPressable(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          decoration: BoxDecoration(
            color: selected ? ShopColors.accentBg : ShopColors.surface,
            borderRadius: BorderRadius.circular(ShopShape.radiusChip),
            border: Border.all(
              color: selected ? ShopColors.accent : ShopColors.border2,
              width: selected ? 1.5 : 1,
            ),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(label,
                    style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: selected ? FontWeight.w700 : FontWeight.w400,
                      color: selected ? ShopColors.text : ShopColors.text2,
                    )),
              ),
              const SizedBox(width: 8),
              Container(
                width: 15,
                height: 15,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: selected ? ShopColors.accent : Colors.transparent,
                  border: selected
                      ? null
                      : Border.all(color: ShopColors.border, width: 1.5),
                ),
                child: selected
                    ? const Icon(Icons.check, size: 10, color: ShopColors.surface)
                    : null,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ============================================================
// 开关
// ============================================================

/// 开关。大 38×22（推荐设置总开关）/ 小 34×19（到货通知、分项）。
///
/// 🔴 <b>[alwaysOn] 是产品底线写进界面</b>：静默期开关常亮且 `opacity: .5` 并明写
/// 「不可关闭」。**不要**把它实现成「可点但点了没反应」—— 那是 bug 的样子，不是规则的样子。
class ShopSwitch extends StatelessWidget {
  const ShopSwitch({
    super.key,
    required this.value,
    required this.onChanged,
    this.large = true,
    this.alwaysOn = false,
    this.semanticLabel,
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final bool large;

  /// 常亮不可关（静默期）。为 true 时忽略 [value] 与 [onChanged]。
  final bool alwaysOn;

  /// 无障碍名称（同 [ShopCheckbox.semanticLabel]）。
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final on = alwaysOn || value;
    final w = large ? 38.0 : 34.0;
    final h = large ? 22.0 : 19.0;
    final knob = large ? 18.0 : 15.0;
    final radius = large ? ShopShape.radiusSwitchLarge : ShopShape.radiusSwitchSmall;
    final pad = (h - knob) / 2;

    final track = Container(
      width: w,
      height: h,
      decoration: BoxDecoration(
        color: on ? ShopColors.purple : ShopColors.border,
        borderRadius: BorderRadius.circular(radius),
      ),
      child: Align(
        alignment: on ? Alignment.centerRight : Alignment.centerLeft,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: pad),
          child: Container(
            width: knob,
            height: knob,
            decoration: const BoxDecoration(
                color: ShopColors.surface, shape: BoxShape.circle),
          ),
        ),
      ),
    );

    return Semantics(
      toggled: on,
      enabled: !alwaysOn && onChanged != null,
      label: semanticLabel,
      child: ShopPressable(
        enabled: !alwaysOn && onChanged != null,
        onTap: () => onChanged!(!value),
        minSize: kShopMinTapTarget,
        child: alwaysOn ? Opacity(opacity: .5, child: track) : track,
      ),
    );
  }
}

// ============================================================
// Chips / Segmented
// ============================================================

/// 分类 chip（Toko 首页品类、地址标签、服务范围城市）。
///
/// 选中墨底白字 700；未选 [ShopColors.bg] 底 + [ShopColors.text2] 字。
class ShopChip extends StatelessWidget {
  const ShopChip({
    super.key,
    required this.label,
    required this.selected,
    this.onTap,
    this.selectedColor = ShopColors.ink,
  });

  final String label;
  final bool selected;

  /// 选中态的实心底色。默认墨色。
  ///
  /// 🔴 Toko 的**分类行**传品牌紫（R-4 ②）：分类是页面级导航，
  /// 选中态要和 D-1 定的顶栏主体色是同一个色，用户才读得出「这一整条是同一组控件」。
  /// ⚠️ 不能直接把默认值改成紫：本组件同时用于**商品详情页的规格选择**（FR-94A），
  /// 那里的选中态属于表单语义、沿用墨色，两者不是一回事。
  final Color selectedColor;

  /// `null` = 纯展示（超服务范围页列已开通城市时就是纯展示，不可点）。
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final chip = Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 6),
      decoration: BoxDecoration(
        color: selected ? selectedColor : ShopColors.bg,
        borderRadius: BorderRadius.circular(ShopShape.radiusChip),
      ),
      child: Text(label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: selected ? FontWeight.w700 : FontWeight.w400,
            color: selected ? ShopColors.surface : ShopColors.text2,
          )),
    );
    if (onTap == null) return chip;
    // 🔴 `selected` 语义不可省：商品详情页用 chip 做**规格选择**，而 FR-94A 的核心
    //    就是「多规格必须由用户显式选择」。没有这个标志，读屏用户听不出选中了哪个规格。
    return Semantics(
      button: true,
      selected: selected,
      inMutuallyExclusiveGroup: true,
      child: ShopPressable(onTap: onTap, child: chip),
    );
  }
}

/// 三档 segmented（推荐频率 `Seperlunya / Jarang / Mati`）。等宽 flex。
class ShopSegmented extends StatelessWidget {
  const ShopSegmented({
    super.key,
    required this.labels,
    required this.selectedIndex,
    required this.onSelected,
  });

  final List<String> labels;
  final int selectedIndex;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        for (var i = 0; i < labels.length; i++) ...[
          if (i > 0) const SizedBox(width: 6),
          Expanded(
            child: Semantics(
              button: true,
              selected: i == selectedIndex,
              inMutuallyExclusiveGroup: true,
              child: ShopPressable(
              onTap: () => onSelected(i),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 9),
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: i == selectedIndex ? ShopColors.ink : ShopColors.bg,
                  borderRadius: BorderRadius.circular(ShopShape.radiusField),
                ),
                child: Text(labels[i],
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: i == selectedIndex ? FontWeight.w700 : FontWeight.w400,
                      color: i == selectedIndex ? ShopColors.surface : ShopColors.text2,
                    )),
              ),
              ),
            ),
          ),
        ],
      ],
    );
  }
}
