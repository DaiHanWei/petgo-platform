import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../pet_slot/presentation/pet_readonly_block.dart';
import '../domain/content/ts_dialog_copy.dart';
import '../domain/content/ts_text.dart';
import 'tailsonality_routes.dart';
import 'widgets/ts_rich_text.dart';

/// 重测流程（V1.3.2 Story 2.4 / 2.6 · AC3）：结果页 ⋯ 菜单与结果列表页吸底按钮**共用这一份**，不许复制。
///
/// 免费、不限次、无任何次数提示（PRD §3.2）：确认文案取内容表 `kTsRetakeDialog`（内容设计 §2.6）→ 确认 →
/// 发 `tailsonality_retake_confirmed` → 直接进答题页（修复线：说明抽屉已去掉）。取消什么都不发生。
Future<void> startTailsonalityRetake(BuildContext context, WidgetRef ref) async {
  // V1.4.0 multi-pet Story 2.4 · AC5.5：只读宠物不能重测（提交会被后端 409）→ 先弹 E1。
  if (!guardPetWritable(context, ref)) return;
  final locale = Localizations.localeOf(context);
  final d = kTsRetakeDialog;
  final ok = await showConfirmSheet(
    context,
    title: d.title.of(locale),
    // showConfirmSheet 只收纯文本：`**…**` 去标记后显示。
    message: tsPlainText(d.body.of(locale)),
    confirmLabel: d.confirm.of(locale),
    cancelLabel: d.cancel.of(locale),
    icon: Icons.refresh_rounded,
  );
  if (!ok || !context.mounted) return;
  Analytics.capture('tailsonality_retake_confirmed');
  // 修复线：去掉说明抽屉，确认后直接进答题页。
  context.push(TailsonalityRoutes.quiz);
}
