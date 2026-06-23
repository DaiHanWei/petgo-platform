import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

/// 二次确认底部抽屉（P-02 规范，preview-new-splash-auth confirm sheet）。
///
/// 拖拽条 + 圆形图标（neutral 紫 / danger 红）+ 标题 + 说明 + 主按钮（紫/红）+ 次按钮（描边）。
/// 全局统一替换原先散落的 `AlertDialog` 二次确认。返回 `true`=确认，`false`/取消=未确认。
///
/// 沿用 App 既有 token（紫 [AppColors.mint] / 红 [AppColors.popRed]），非新 logo 紫——
/// P-02 是功能 UI，与品牌重塑（splash/login）解耦。
Future<bool> showConfirmSheet(
  BuildContext context, {
  required String title,
  String? message,
  required String confirmLabel,
  required String cancelLabel,
  IconData icon = Icons.help_outline_rounded,
  bool danger = false,
  Key? confirmKey,
  Key? cancelKey,
}) async {
  final result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (ctx) => _ConfirmSheet(
      title: title,
      message: message,
      confirmLabel: confirmLabel,
      cancelLabel: cancelLabel,
      icon: icon,
      danger: danger,
      confirmKey: confirmKey,
      cancelKey: cancelKey,
    ),
  );
  return result ?? false;
}

class _ConfirmSheet extends StatelessWidget {
  const _ConfirmSheet({
    required this.title,
    required this.message,
    required this.confirmLabel,
    required this.cancelLabel,
    required this.icon,
    required this.danger,
    required this.confirmKey,
    required this.cancelKey,
  });

  final String title;
  final String? message;
  final String confirmLabel;
  final String cancelLabel;
  final IconData icon;
  final bool danger;
  final Key? confirmKey;
  final Key? cancelKey;

  @override
  Widget build(BuildContext context) {
    final accent = danger ? AppColors.popRed : AppColors.mint;
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      padding: const EdgeInsets.fromLTRB(22, 12, 22, 30),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 拖拽条
            Container(
              width: 36,
              height: 4,
              margin: const EdgeInsets.only(bottom: 18),
              decoration: BoxDecoration(
                color: AppColors.line,
                borderRadius: BorderRadius.circular(99),
              ),
            ),
            // 圆形图标（neutral 紫 / danger 红）
            Container(
              width: 60,
              height: 60,
              margin: const EdgeInsets.only(bottom: 14),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: accent.withValues(alpha: 0.12),
              ),
              child: Icon(icon, size: 28, color: accent),
            ),
            Text(
              title,
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink),
            ),
            if (message != null && message!.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                message!,
                textAlign: TextAlign.center,
                style: const TextStyle(
                    fontSize: 13, height: 1.6, color: AppColors.textSecondary),
              ),
            ],
            const SizedBox(height: 22),
            // 主按钮（确认）
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: confirmKey ?? const ValueKey('confirmSheetConfirm'),
                onPressed: () => Navigator.of(context).pop(true),
                style: FilledButton.styleFrom(
                  backgroundColor: accent,
                  foregroundColor: AppColors.onAccent,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text(confirmLabel,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
              ),
            ),
            const SizedBox(height: 10),
            // 次按钮（取消，描边）
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                key: cancelKey ?? const ValueKey('confirmSheetCancel'),
                onPressed: () => Navigator.of(context).pop(false),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.textSecondary,
                  side: const BorderSide(color: AppColors.line, width: 1.5),
                  padding: const EdgeInsets.symmetric(vertical: 13),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text(cancelLabel,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
