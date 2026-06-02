import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../domain/app_version_check.dart';

/// App 更新提醒弹窗（Story 6.5 F2/F3）。App 内提示，<b>不走推送、不需推送权限</b>。
///
/// - 推荐（[UpdateDecision.recommended]）：可「稍后」（下次冷启动再提示），可关闭。
/// - 强制（[UpdateDecision.forced]）：**不可跳过**（拦返回键/点遮罩不关，无「稍后」），唯一操作「前往更新」。
class AppUpdateDialog extends StatelessWidget {
  const AppUpdateDialog({super.key, required this.decision, required this.onGoStore});

  final UpdateDecision decision;
  final VoidCallback onGoStore;

  /// 展示更新弹窗。强制态 barrierDismissible=false + PopScope 拦截返回。
  static Future<void> show(BuildContext context, UpdateDecision decision, VoidCallback onGoStore) {
    final forced = decision == UpdateDecision.forced;
    return showDialog<void>(
      context: context,
      barrierDismissible: !forced,
      builder: (_) => AppUpdateDialog(decision: decision, onGoStore: onGoStore),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final forced = decision == UpdateDecision.forced;
    final dialog = AlertDialog(
      key: ValueKey(forced ? 'appUpdateForced' : 'appUpdateRecommended'),
      title: Text(forced ? l10n.updateForceTitle : l10n.updateRecommendTitle),
      content: Text(forced ? l10n.updateForceBody : l10n.updateRecommendBody),
      actions: [
        if (!forced)
          TextButton(
            key: const ValueKey('appUpdateLater'),
            onPressed: () => Navigator.of(context).pop(),
            child: Text(l10n.updateLater),
          ),
        FilledButton(
          key: const ValueKey('appUpdateGoStore'),
          onPressed: onGoStore,
          child: Text(l10n.updateGoStore),
        ),
      ],
    );
    // 强制态拦截系统返回手势/物理键，确保不可跳过。
    return PopScope(canPop: !forced, child: dialog);
  }
}
