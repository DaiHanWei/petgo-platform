import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../profile/data/profile_repository.dart';
import '../../profile/domain/pet_profile.dart';
import '../domain/content/ts_dialog_copy.dart';
import '../domain/content/ts_text.dart';
import 'widgets/tailsonality_intro_sheet.dart';
import 'widgets/ts_rich_text.dart';

/// 重测流程（V1.3.2 Story 2.4 / 2.6 · AC3）：结果页 ⋯ 菜单与结果列表页吸底按钮**共用这一份**，不许复制。
///
/// 免费、不限次、无任何次数提示（PRD §3.2）：确认文案取内容表 `kTsRetakeDialog`（内容设计 §2.6）→ 确认 →
/// 发 `tailsonality_retake_confirmed` → 说明抽屉 →「Mulai Tes」→ 答题。取消什么都不发生。
Future<void> startTailsonalityRetake(BuildContext context, WidgetRef ref) async {
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
  // 先拿到宠物再报埋点：档案取不到时抽屉开不了，这次重测并没有发生。
  final pet = await readPetForTailsonality(ref);
  if (!context.mounted) return;
  if (pet == null) {
    showAppToast(context, AppLocalizations.of(context).growthLoadFailed);
    return;
  }
  Analytics.capture('tailsonality_retake_confirmed');
  await showTailsonalityIntroSheet(context, pet);
}

/// 取当前宠物（说明抽屉要确认「测的是谁」）：已有值直接用；加载中带超时等待；失败 → null。
///
/// ⚠️ 不直接 await `.future`：Riverpod 3 对失败的 provider 自动重试，future 会一直挂着。
Future<PetProfile?> readPetForTailsonality(WidgetRef ref) async {
  final state = ref.read(petProfileProvider);
  if (state.hasValue) return state.value;
  if (state.hasError) return null;
  try {
    return await ref.read(petProfileProvider.future).timeout(const Duration(seconds: 10));
  } catch (_) {
    return null;
  }
}
