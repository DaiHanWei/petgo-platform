import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';

/// 胶囊标签（对应 prototype `Tag`）。
///
/// 圆角 pill，默认薄荷绿文字 + 柔填充底；可带前导小部件（emoji / 圆点）。
class PillTag extends StatelessWidget {
  const PillTag({
    super.key,
    required this.label,
    this.color = AppColors.mint700,
    this.background = AppColors.mintTint,
    this.leading,
    this.fontSize = 12.5,
  });

  final String label;
  final Color color;
  final Color background;
  final Widget? leading;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 5),
      decoration: BoxDecoration(color: background, borderRadius: BorderRadius.circular(999)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (leading != null) ...[leading!, const SizedBox(width: 5)],
          Text(
            label,
            style: TextStyle(
              color: color,
              fontSize: fontSize,
              fontWeight: FontWeight.w700,
              height: 1.0,
            ),
          ),
        ],
      ),
    );
  }
}
