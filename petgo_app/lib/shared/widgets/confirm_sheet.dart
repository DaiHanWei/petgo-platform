import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

/// 二次确认底部抽屉（P-02 规范，preview-new-splash-auth confirm sheet）。
///
/// 拖拽条 + 圆形图标（neutral 紫 / danger 红）+ 标题 + 说明 + 主按钮（紫/红）+ 次按钮（描边）。
/// 全局统一替换原先散落的 `AlertDialog` 二次确认。返回 `true`=确认，`false`/取消=未确认。
///
/// 沿用 App 既有 token（紫 [AppColors.mint] / 红 [AppColors.popRed]），非新 logo 紫——
/// P-02 是功能 UI，与品牌重塑（splash/login）解耦。
///
/// **V1.1.4 Story 1.2 加了两个可选参数，二者默认值都保持原行为，既有 6+ 处调用零影响**：
/// - [leading]：自定义图标位内容（如头像）。为 null 时走原来的 [icon] 圆形图标路径。
/// - [onConfirm]：异步确认钩子。为 null 时点主按钮立刻 `pop(true)`（原行为）；给了则由抽屉自己
///   持有提交态——提交中主按钮与取消**一并禁用且抽屉不关**，回调返回 true 才 `pop(true)`，
///   返回 false 抽屉**保持打开**让用户直接重试（失败提示由回调自己给）。
///
/// ⚠️ 为什么不另写一个平行的确认抽屉：手柄 36×4、圆角 24、内边距 22/12/22/30、主按钮 radius 14…
/// 两套同义视觉规格日后必然漂移。要什么就往这里加可选参数。
Future<bool> showConfirmSheet(
  BuildContext context, {
  required String title,
  String? message,
  required String confirmLabel,
  required String cancelLabel,
  IconData icon = Icons.help_outline_rounded,
  Widget? leading,
  bool danger = false,
  Future<bool> Function()? onConfirm,
  Key? confirmKey,
  Key? cancelKey,
}) async {
  final result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    // 提交中禁止拖走/点遮罩关掉抽屉（否则请求在飞、界面已没了）。仅在有异步钩子时收紧。
    isDismissible: onConfirm == null,
    enableDrag: onConfirm == null,
    builder: (ctx) => _ConfirmSheet(
      title: title,
      message: message,
      confirmLabel: confirmLabel,
      cancelLabel: cancelLabel,
      icon: icon,
      leading: leading,
      danger: danger,
      onConfirm: onConfirm,
      confirmKey: confirmKey,
      cancelKey: cancelKey,
    ),
  );
  return result ?? false;
}

class _ConfirmSheet extends StatefulWidget {
  const _ConfirmSheet({
    required this.title,
    required this.message,
    required this.confirmLabel,
    required this.cancelLabel,
    required this.icon,
    required this.leading,
    required this.danger,
    required this.onConfirm,
    required this.confirmKey,
    required this.cancelKey,
  });

  final String title;
  final String? message;
  final String confirmLabel;
  final String cancelLabel;
  final IconData icon;
  final Widget? leading;
  final bool danger;
  final Future<bool> Function()? onConfirm;
  final Key? confirmKey;
  final Key? cancelKey;

  @override
  State<_ConfirmSheet> createState() => _ConfirmSheetState();
}

class _ConfirmSheetState extends State<_ConfirmSheet> {
  bool _submitting = false;

  String get title => widget.title;
  String? get message => widget.message;
  String get confirmLabel => widget.confirmLabel;
  String get cancelLabel => widget.cancelLabel;
  IconData get icon => widget.icon;
  bool get danger => widget.danger;
  Key? get confirmKey => widget.confirmKey;
  Key? get cancelKey => widget.cancelKey;

  /// 主按钮动作。无异步钩子 → 原行为（立刻 pop true）；有钩子 → 自持提交态。
  Future<void> _confirm() async {
    final hook = widget.onConfirm;
    if (hook == null) {
      Navigator.of(context).pop(true);
      return;
    }
    if (_submitting) return;
    setState(() => _submitting = true);
    final ok = await hook();
    if (!mounted) return;
    if (ok) {
      Navigator.of(context).pop(true);
      return;
    }
    // 失败：抽屉**保持打开**，只解禁按钮——用户可直接再点，不必重走一遍入口。
    setState(() => _submitting = false);
  }

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
            // 图标位：默认圆形图标（neutral 紫 / danger 红）；给了 leading 就换成它（如头像）。
            // 尺寸 60×60 与下边距 14 两条路径一致，换内容不改版式。
            Container(
              width: 60,
              height: 60,
              margin: const EdgeInsets.only(bottom: 14),
              decoration: widget.leading == null
                  ? BoxDecoration(shape: BoxShape.circle, color: accent.withValues(alpha: 0.12))
                  : null,
              child: widget.leading ?? Icon(icon, size: 28, color: accent),
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
                onPressed: _submitting ? null : _confirm,
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
                // 提交中取消**一并禁用**：请求在飞时关掉抽屉会让成功/失败提示落在没有上下文的界面上。
                onPressed: _submitting ? null : () => Navigator.of(context).pop(false),
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
