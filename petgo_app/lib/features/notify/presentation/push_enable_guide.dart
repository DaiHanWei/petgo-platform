import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
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
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          const Icon(Icons.notifications_active_outlined, color: AppColors.accentConsult),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.pushEnableGuideTitle, style: AppTypography.title),
                const SizedBox(height: 2),
                Text(l10n.pushEnableGuideBody, style: AppTypography.caption),
              ],
            ),
          ),
          TextButton(
            key: const ValueKey('pushOpenSettings'),
            onPressed: () => (onOpenSettings ?? openPushSettings)(),
            child: Text(l10n.mediaOpenSettings),
          ),
        ],
      ),
    );
  }
}
