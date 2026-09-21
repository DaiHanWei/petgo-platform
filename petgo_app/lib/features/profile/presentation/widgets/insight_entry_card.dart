import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// 横向入口卡（UI 稿 P1 / P2 / P3）：白底 r14 + 柔阴影，「左图标色块 → 右标题 + 副文案」。
///
/// 档案页头部的两张入口卡与「Kenali Hewanmu」聚合页的卡**共用这一个组件** ——
/// V1.3.0 Story 5.1 · AC1 要求两处同一样式，用户才看得出「点进去是同一类东西」。
/// 之前两处各画一份，聚合页一度画成了竖排高卡（2026-09-21 对稿发现）。
///
/// [onTap] 为 null = 置灰（P3：非猫狗的年龄卡）——原地变灰、彻底不可点，不新开页、不弹层。
class InsightEntryCard extends StatelessWidget {
  const InsightEntryCard({
    super.key,
    required this.inkKey,
    required this.onTap,
    required this.icon,
    required this.title,
    required this.sub,
  });

  /// 挂在可点区域（InkWell）上的 key —— 既有测试与埋点对照认的是它。
  final Key inkKey;
  final VoidCallback? onTap;
  final IconData icon;
  final String title;
  final String sub;

  @override
  Widget build(BuildContext context) {
    final bool disabled = onTap == null;
    return Opacity(
      opacity: disabled ? 0.45 : 1,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          boxShadow: const [
            BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8),
          ],
        ),
        child: Material(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(14),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            key: inkKey,
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Container(
                    width: 38,
                    height: 38,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: disabled ? AppColors.line2 : AppColors.mintTint,
                      borderRadius: BorderRadius.circular(11),
                    ),
                    child: Icon(icon,
                        size: 21, color: disabled ? AppColors.muted : AppColors.mint),
                  ),
                  const SizedBox(width: 10),
                  // 文字块吃掉剩余宽度：入口名两语长度差得多（EN 13 / ID 14 字符），
                  // 不给 Expanded 会在印尼语下把卡撑爆。
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(title,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                                fontSize: 13,
                                height: 1.25,
                                fontWeight: FontWeight.w600,
                                color: AppColors.ink)),
                        const SizedBox(height: 3),
                        Text(sub,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                                fontSize: 10.5, height: 1.3, color: AppColors.textTertiary)),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
