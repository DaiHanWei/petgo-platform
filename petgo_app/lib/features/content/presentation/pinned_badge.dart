import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';

/// 顶置角标（V1.1.6 Story 4.2 · FR-68）。
///
/// 🛡 挂在 Story 3.4 交付的**图片区右上角位**上 —— 本组件只是"往角位里塞的东西"，
/// 一行图片区结构都不该改（3.4 有一条契约测试守着这件事）。
///
/// ⚠️ 视觉上**与推广卡片不作区分**（FR-68）：两类顶置对象共用同一个角标。
/// 也**不加"广告 / 推广"字样** —— 本版本按"平台自有活动引导"定位使用。
class PinnedBadge extends StatelessWidget {
  const PinnedBadge({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('feedPinnedBadge'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        // 半透明深底：图片明暗都压得住，不用为每张图挑颜色。
        color: const Color(0x8A000000),
        borderRadius: BorderRadius.circular(11),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.push_pin_rounded, size: 11, color: Colors.white),
          const SizedBox(width: 4),
          Text(
            AppLocalizations.of(context).feedPinnedBadge,
            style: const TextStyle(
                fontSize: 10, fontWeight: FontWeight.w700, color: Colors.white),
          ),
        ],
      ),
    );
  }
}
