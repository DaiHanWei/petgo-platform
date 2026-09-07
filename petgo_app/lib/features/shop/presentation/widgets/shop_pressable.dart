/// 电商板块的**按下反馈**与**命中区**基座。
///
/// ## 为什么需要这个文件（2026-08-27 审查结论）
///
/// 此前电商子树的所有可点元素都是 `InkWell` 包一个不透明的 `DecoratedBox` /
/// `ColoredBox` / `Container(decoration:)`。Flutter 的水波纹画在最近的 [Material]
/// 的 ink 层上，也就是**子节点下面** —— 子节点一旦不透明，涟漪就被完全盖住。
/// 而 `ShopButton` 又没有任何别的按下态，结果是**从主 CTA 到 chip，按下去屏幕上
/// 一点变化都没有**，直到下一帧数据回来。在支付按钮上，这会让用户重复点击。
///
/// ## 为什么不改用 Material + Ink 把涟漪救回来
///
/// `shop_buttons.dart` 开头写明了这套设计**刻意不用 Material 的按钮**：M3 的
/// elevation、波纹溢出与 40px 最小高度都与设计冲突（设计稿明令产品 UI 内不使用阴影）。
/// 为了拿回涟漪而在每个按钮里插一层 [Material]，等于把刚推开的那套东西请回来，
/// 还会引入一层 `AnimatedDefaultTextStyle` 影响字族继承。
///
/// 改用**整体压不透明度**：无 Material 依赖、不改变布局边界、在任何底色上都可见，
/// 且与「无阴影、扁平」的设计语言一致。时长 90ms 落在 80–150ms 的触觉反馈窗口内。
library;

import 'package:flutter/material.dart';

/// 可点区域的最小尺寸。iOS HIG 44pt / Material 48dp，取 44 作为下限。
const double kShopMinTapTarget = 44;

/// 按下反馈 + 可选的命中区下限。
///
/// 🔴 <b>视觉尺寸不变，只撑命中区</b>：[minSize] 用透明的 padding 把可点范围撑到
/// 44×44，子节点该多大还是多大。不要为了「紧凑」把它去掉 —— 那会让 30×30 的
/// 图上返回按钮和 18×18 的勾选框实际点不中。
class ShopPressable extends StatefulWidget {
  const ShopPressable({
    super.key,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.enabled = true,
    this.minSize,
    this.pressedOpacity = .62,
  });

  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  /// 为 false 时不响应也不反馈（但仍占位）。
  final bool enabled;

  /// 命中区下限。传 [kShopMinTapTarget] 即撑到 44×44；null 表示按子节点实际大小。
  final double? minSize;

  /// 按下时的不透明度。
  final double pressedOpacity;

  bool get _live => enabled && (onTap != null || onLongPress != null);

  @override
  State<ShopPressable> createState() => _ShopPressableState();
}

class _ShopPressableState extends State<ShopPressable> {
  bool _down = false;

  void _set(bool v) {
    if (!widget._live || _down == v) return;
    setState(() => _down = v);
  }

  @override
  Widget build(BuildContext context) {
    Widget content = AnimatedOpacity(
      // 90ms：低于 80 会看不出，高于 150 会拖沓（触觉反馈窗口）。
      duration: const Duration(milliseconds: 90),
      curve: Curves.easeOut,
      opacity: _down ? widget.pressedOpacity : 1,
      child: widget.child,
    );

    final min = widget.minSize;
    if (min != null) {
      content = ConstrainedBox(
        constraints: BoxConstraints(minWidth: min, minHeight: min),
        // 🔴 Center 而非 SizedBox：子节点比 min 大时（整宽主按钮）不能被压回 44。
        child: Center(widthFactor: 1, heightFactor: 1, child: content),
      );
    }

    return GestureDetector(
      // opaque：命中区内的空白同样可点，否则撑出来的那圈是白撑的。
      behavior: HitTestBehavior.opaque,
      onTap: widget._live ? widget.onTap : null,
      onLongPress: widget._live ? widget.onLongPress : null,
      onTapDown: (_) => _set(true),
      onTapUp: (_) => _set(false),
      onTapCancel: () => _set(false),
      child: content,
    );
  }
}
