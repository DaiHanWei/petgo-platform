import 'package:flutter/material.dart';

import '../../features/auth/domain/user_tag.dart';
import 'anchored_tooltip.dart';

/// 昵称 + 运营标签的一行（V1.1.6 Story 5.1 · FR-74）。
///
/// ## 🛡 昵称完整显示优先，标签放不下就丢
/// 规则写得很死：空间不足时**不截断昵称、不做「+N」折叠**，而是**丢标签**
/// （按分配时间倒序丢，也就是先丢最旧的那个）。
///
/// 所以这里**不能**简单地把昵称和标签塞进一个 Row 让它自己溢出 ——
/// 那样溢出的要么是被裁掉一半的标签，要么是被打点的昵称，两种都违反规则。
/// 做法是：先量昵称需要多宽，剩下多少宽度就放几个标签。
///
/// 昵称**本身**长到一行都放不下时仍会打点（屏幕就那么宽），但此时标签数为 0 ——
/// 也就是"先保昵称"这条仍然成立。
class UserTagRow extends StatelessWidget {
  const UserTagRow({
    super.key,
    required this.name,
    required this.nameStyle,
    required this.tags,
    this.iconSize = 15,
    this.gap = 4,
  });

  final String name;
  final TextStyle nameStyle;

  /// 后端已按分配时间倒序截断到最多 3 个；这里只管"放得下几个"。
  final List<UserTag> tags;

  final double iconSize;

  /// 昵称与标签之间、标签彼此之间的间距。
  final double gap;

  /// 单个标签占的宽度（含它前面的间距）。做成定宽是为了**能算**放得下几个。
  double get _slotWidth => iconSize + gap;

  @override
  Widget build(BuildContext context) {
    if (tags.isEmpty) {
      return Text(name, style: nameStyle, maxLines: 1, overflow: TextOverflow.ellipsis);
    }
    return LayoutBuilder(
      builder: (context, c) {
        final maxWidth = c.maxWidth;
        final nameWidth = _measure(context, name, nameStyle);

        // 昵称先拿它需要的宽度（上限是可用宽度），剩下的才轮到标签。
        final remaining = maxWidth - nameWidth.clamp(0.0, maxWidth);
        var fit = (remaining / _slotWidth).floor();
        if (fit < 0) fit = 0;
        if (fit > tags.length) fit = tags.length;

        // 🛡 按分配时间倒序丢弃 = 保留靠前的（后端已按倒序排好）。
        final shown = tags.take(fit).toList();

        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Flexible(
              child: Text(name,
                  style: nameStyle, maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
            for (final t in shown) ...[
              SizedBox(width: gap),
              _TagIcon(tag: t, size: iconSize),
            ],
          ],
        );
      },
    );
  }

  static double _measure(BuildContext context, String text, TextStyle style) {
    final painter = TextPainter(
      text: TextSpan(text: text, style: style),
      maxLines: 1,
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
    )..layout();
    return painter.width;
  }
}

/// 单个标签图标；点一下弹提示层（四处行为一致）。
class _TagIcon extends StatelessWidget {
  const _TagIcon({required this.tag, required this.size});

  final UserTag tag;
  final double size;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      key: ValueKey('userTag_${tag.code}'),
      behavior: HitTestBehavior.opaque,
      // 🛡 点标签只弹提示，**不触发外层的整块点击**（否则点标签会被跳进详情页）。
      onTap: () => showAnchoredTooltip(context,
          title: tag.name, message: tag.description),
      child: SizedBox(
        width: size,
        height: size,
        // 定宽定高 —— 上面"放得下几个"的计算依赖它。
        child: Center(
          child: Text(tag.icon,
              style: TextStyle(fontSize: size * 0.86, height: 1.0),
              textAlign: TextAlign.center),
        ),
      ),
    );
  }
}
