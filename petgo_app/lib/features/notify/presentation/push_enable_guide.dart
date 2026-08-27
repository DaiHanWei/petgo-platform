import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../data/push_permission_providers.dart';

/// 「我的」页推送开启引导（Story 6.4 F3）。未授权推送时显示，引导手动「去设置」开启。
///
/// Epic 7 的「我的」页本体挂载本组件（当前 me_page 为占位）。点「去设置」深链系统通知设置页
/// （复用 Story 2.1 `openAppSettings` 统一样式）。拒绝后不再主动弹系统弹窗，仅此被动引导。
class PushEnableGuide extends StatelessWidget {
  const PushEnableGuide({super.key, this.onOpenSettings});

  /// 「去设置」回调（默认 [openPushSettings]；测试可注入）。
  final Future<bool> Function()? onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('pushEnableGuide'),
      margin: const EdgeInsets.all(AppSpacing.md),
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      // 🔴 与通知中心那条**同一观感**（2026-08-26 按设计稿重做）：淡紫柔填充 + 实心紫胶囊按钮。
      //    两处共用同一套文案键，样式再分叉就会出现「同一句话两种长相」。
      decoration: BoxDecoration(
        color: AppColors.mintTint,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          const Text('🔔', style: TextStyle(fontSize: 22)),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(l10n.pushEnableGuideTitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.w800, color: AppColors.ink)),
                const SizedBox(height: 3),
                Text(l10n.pushEnableGuideBody,
                    maxLines: 2,
                    style: const TextStyle(
                        fontSize: 12, height: 1.3, color: AppColors.textSecondary)),
              ],
            ),
          ),
          const SizedBox(width: 10),
          FilledButton(
            key: const ValueKey('pushOpenSettings'),
            onPressed: () => (onOpenSettings ?? openPushSettings)(),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
              minimumSize: Size.zero,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
            ),
            child: Text(l10n.pushEnableGuideCta,
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }
}
