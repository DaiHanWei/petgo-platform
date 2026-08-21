/// 电商板块的表单控件：步进器 / 勾选框 / 单选 / 开关 / chips / 三档 segmented。
///
/// 🔴 <b>为什么全部自绘而不用 Material 原生</b>：设计稿把这几个控件的尺寸定死了
/// （勾选框 18×18、单选 15px、开关 38×22 与 34×19、步进器 22×22），而 Material 的
/// `Checkbox`/`Radio`/`Switch` <b>尺寸不可精确控制</b> —— `Switch` 恒 ~52×32 且无尺寸参数，
/// `Checkbox` 靠 `MaterialTapTargetSize` 只能在 48 与 40 之间二选一。
/// 硬套原生的结果是每一处都差几个像素，且没有任何办法对齐。
///
/// ⚠️ 自绘的代价是**触控区**：视觉 15–22px 远小于 44px 的可点最小尺寸。
/// 因此每个控件都用 [_TapTarget] 把命中区域撑到 44×44（视觉不变），
/// 不要为了「紧凑」把它去掉 —— 那会让购物车的勾选框实际点不中。
library;

import 'package:flutter/material.dart';

import '../../../../core/theme/shop_tokens.dart';


/// 把任意小尺寸控件的命中区撑到 44×44，视觉尺寸不变。
class _TapTarget extends StatelessWidget {
  const _TapTarget({required this.child, this.onTap, this.enabled = true});

  final Widget child;
  final VoidCallback? onTap;
  final bool enabled;

  static const double _minTarget = 44;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: enabled ? onTap : null,
      child: SizedBox(
        width: _minTarget,
        height: _minTarget,
        child: Center(child: child),
      ),
    );
  }
}

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
class ShopStepper extends StatelessWidget {
  const ShopStepper({
    super.key,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
  });

  final int value;
  final int min;

  /// 上限（= 当前库存 / 原购数）。
  final int max;
  final ValueChanged<int>? onChanged;

  @override
  Widget build(BuildContext context) {
    final canDec = onChanged != null && value > min;
    final canInc = onChanged != null && value < max;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _box('−', canDec, () => onChanged!(value - 1), 'stepperDec'),
        SizedBox(
          width: 30,
          child: Text('$value',
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 12, fontWeight: FontWeight.w600, color: ShopColors.text)),
        ),
        _box('+', canInc, () => onChanged!(value + 1), 'stepperInc'),
      ],
    );
  }

  Widget _box(String glyph, bool enabled, VoidCallback onTap, String keyName) {
    return _TapTarget(
      enabled: enabled,
      onTap: onTap,
      child: Container(
        width: 22,
        height: 22,
        key: ValueKey(keyName),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          border: Border.all(color: enabled ? ShopColors.border : ShopColors.border2),
          borderRadius: BorderRadius.circular(ShopShape.radiusPayRow),
        ),
        child: Text(glyph,
            style: TextStyle(
                fontSize: 13,
                height: 1,
                color: enabled ? ShopColors.text2 : ShopColors.disabledText)),
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
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final double size;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final interactive = enabled && onChanged != null;
    return Semantics(
      checked: value,
      enabled: interactive,
      child: _TapTarget(
        enabled: interactive,
        onTap: () => onChanged!(!value),
        child: Container(
          width: size,
          height: size,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: !enabled
                ? ShopColors.border2
                : (value ? ShopColors.rose : ShopColors.surface),
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
/// 选中：1.5px 玫红边 + [ShopColors.roseBg] 底 + 文字转 700 + 右侧玫红实心圆 `✓`
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
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(ShopShape.radiusChip),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          decoration: BoxDecoration(
            color: selected ? ShopColors.roseBg : ShopColors.surface,
            borderRadius: BorderRadius.circular(ShopShape.radiusChip),
            border: Border.all(
              color: selected ? ShopColors.rose : ShopColors.border2,
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
                  color: selected ? ShopColors.rose : Colors.transparent,
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
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final bool large;

  /// 常亮不可关（静默期）。为 true 时忽略 [value] 与 [onChanged]。
  final bool alwaysOn;

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
      child: _TapTarget(
        enabled: !alwaysOn && onChanged != null,
        onTap: () => onChanged!(!value),
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
  });

  final String label;
  final bool selected;

  /// `null` = 纯展示（超服务范围页列已开通城市时就是纯展示，不可点）。
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final chip = Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 6),
      decoration: BoxDecoration(
        color: selected ? ShopColors.ink : ShopColors.bg,
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
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(ShopShape.radiusChip),
      child: chip,
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
            child: InkWell(
              onTap: () => onSelected(i),
              borderRadius: BorderRadius.circular(ShopShape.radiusField),
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
        ],
      ],
    );
  }
}
