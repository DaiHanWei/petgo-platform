import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

/// 贴键盘上沿的联想面板外壳（V1.4.2 social-topic · Story 4.4 · C2；5.2 的 @ 面板复用）。
///
/// 通栏白底、上沿 1px 描边、向上淡阴影、最高 [maxHeight]（220）、按内容收缩；内容超出在面板内滚动
/// （调用方给 `Flexible + ListView(shrinkWrap)` 或任意 `mainAxisSize.min` 的子树）。
///
/// ⚠️ 只管外观，不管位置：宿主用 `Stack + Positioned(left: 0, right: 0, bottom: 0)` 摆放；
/// 发布页整页已包 `KeyboardInset`，`bottom: 0` 就是键盘上沿（story 待确认第 4 条）。
class KeyboardSuggestPanel extends StatelessWidget {
  const KeyboardSuggestPanel({super.key, required this.child, this.maxHeight = 220});

  final Widget child;
  final double maxHeight;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: BoxConstraints(maxHeight: maxHeight),
      decoration: BoxDecoration(
        color: AppColors.surface,
        border: const Border(top: BorderSide(color: AppColors.border, width: 1)),
        boxShadow: [
          BoxShadow(
            offset: const Offset(0, -4),
            blurRadius: 12,
            color: AppColors.ink.withValues(alpha: 0.06),
          ),
        ],
      ),
      child: Material(
        type: MaterialType.transparency,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [Flexible(child: child)],
        ),
      ),
    );
  }
}
